(ns ivylee.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [ivylee.events :as e]
            [ivylee.hlc :as hlc]
            [ivylee.model :as m]))

(def db0
  {:doc m/empty-doc :clock (hlc/init "A") :node-id "A" :last-seen-day nil})

(defn- add [db id title list-id]
  (e/handle db [:task/add {:id id :title title :list-id list-id}] 1000))

(deftest add-task-stamps-and-adds
  (let [db (add db0 :a "capture" nil)]
    (is (= [:a] (map first (m/tasks-in (:doc db) :longlist)))
        "defaults to the longlist")
    (is (hlc/before? (:clock db0) (:clock db))
        "every event advances the clock")))

(deftest hard-limit-via-funnel
  (let [full (reduce #(add %1 %2 (str %2) "2026-06-12") db0 (range 6))
        db   (add full :seventh "nope" "2026-06-12")]
    (is (= :day-full (get-in db [:flash :type])))
    (is (= (:doc full) (:doc db)) "doc untouched on rejection")
    (testing "same for moves"
      (let [with-extra (add full :extra "later" nil)
            db'        (e/handle with-extra
                                 [:task/move {:id :extra :list-id "2026-06-12" :rank 99.0}]
                                 2000)]
        (is (= :day-full (get-in db' [:flash :type])))
        (is (= (:doc with-extra) (:doc db')))))))

(deftest toggle-done-flips
  (let [db  (add db0 :a "task" "2026-06-12")
        on  (e/handle db [:task/toggle-done {:id :a}] 2000)
        off (e/handle on [:task/toggle-done {:id :a}] 3000)]
    (is (true? (m/fval (get-in on [:doc :tasks :a]) :done?)))
    (is (some? (m/fval (get-in on [:doc :tasks :a]) :completed-at)))
    (is (false? (m/fval (get-in off [:doc :tasks :a]) :done?)))))

(deftest rollover-carries-and-is-guarded
  (let [db    (-> db0
                  (add :a "left over" "2026-06-11")
                  (assoc :last-seen-day "2026-06-11"))
        db'   (e/handle db [:day/rollover {:today "2026-06-12"}] 2000)
        again (e/handle db' [:day/rollover {:today "2026-06-12"}] 3000)]
    (is (= [:a] (map first (m/tasks-in (:doc db') "2026-06-12"))))
    (is (= "2026-06-12" (:last-seen-day db')))
    (is (= db' again) "second rollover on the same day is a no-op")))

(deftest remote-merge-advances-clock-past-remote
  (let [remote-t [99999 5 "B"]
        remote   (m/add-task m/empty-doc :r "from phone" :longlist 1.0 remote-t)
        db       (e/handle (add db0 :a "local" nil)
                           [:remote/merged {:doc remote}] 2000)]
    (is (= #{:a :r} (set (map first (m/tasks-in (:doc db) :longlist)))))
    (is (hlc/before? remote-t (:clock db))
        "local stamps issued after a merge sort after everything seen")
    (is (= "A" (last (:clock db))) "node identity survives recv")))
