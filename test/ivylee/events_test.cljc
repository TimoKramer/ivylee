(ns ivylee.events-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [ivylee.events :as e]
    [ivylee.hlc :as hlc]
    [ivylee.model :as m]))


(def db0
  {:doc m/empty-doc :clock (hlc/init "A") :node-id "A" :last-seen-day nil})


(defn- add
  [db id title list-id]
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


(deftest sync-state-transitions-dont-stamp
  (let [starting (e/handle db0 [:sync/start] 1000)
        ok       (e/handle (assoc starting :sync-error "boom") [:sync/success] 2000)
        err      (e/handle db0 [:sync/error {:error "boom"}] 3000)
        offline  (e/handle db0 [:sync/offline] 4000)]
    (is (= :syncing (:sync-state starting)))
    (is (= :idle (:sync-state ok)))
    (is (not (contains? ok :sync-error)) "success clears a stale error")
    (is (= :error (:sync-state err)))
    (is (= "boom" (:sync-error err)))
    (is (= :offline (:sync-state offline)))
    (is (= (:clock db0) (:clock starting) (:clock err))
        "ephemeral sync state never advances the clock")))


(deftest remote-configure-and-disconnect-dont-stamp
  (let [spec       {:endpoint "https://example.r2.cloudflarestorage.com"
                    :bucket "ivylee" :access-key "ak" :secret "sk" :id "store-1"}
        configured (e/handle db0 [:remote/configure {:spec spec}] 1000)
        gone       (e/handle (assoc configured :sync-state :idle)
                             [:remote/disconnect] 2000)]
    (is (= spec (:remote-config configured)))
    (is (not (contains? gone :remote-config)))
    (is (= :not-configured (:sync-state gone)))
    (is (= (:clock db0) (:clock configured) (:clock gone))
        "ephemeral remote config never advances the clock")))


(deftest sw-update-available-doesnt-stamp
  (let [db (e/handle db0 [:sw/update-available] 1000)]
    (is (= :sw-update (get-in db [:flash :type])))
    (is (= (:clock db0) (:clock db)) "ephemeral flash never advances the clock")))


(deftest editing-and-menu-state-dont-stamp
  (let [editing (e/handle db0 [:ui/edit-task {:id :a}] 1000)
        stopped (e/handle editing [:ui/stop-editing] 2000)
        opened  (e/handle db0 [:ui/open-menu {:id :a :x 10 :y 20}] 3000)
        closed  (e/handle opened [:ui/close-menu] 4000)]
    (is (= :a (:editing-id editing)))
    (is (not (contains? stopped :editing-id)))
    (is (= :a (:menu-id opened)))
    (is (= {:x 10 :y 20} (:menu-pos opened)))
    (is (not (contains? closed :menu-id)))
    (is (not (contains? closed :menu-pos)))
    (is (= (:clock db0) (:clock editing) (:clock stopped) (:clock opened) (:clock closed))
        "ephemeral UI state never advances the clock")))


(deftest remote-merge-advances-clock-past-remote
  (let [remote-t [99999 5 "B"]
        remote   (m/add-task m/empty-doc :r "from phone" :longlist 1.0 remote-t)
        db       (e/handle (add db0 :a "local" nil)
                           [:remote/merged {:doc remote}] 2000)]
    (is (= #{:a :r} (set (map first (m/tasks-in (:doc db) :longlist)))))
    (is (hlc/before? remote-t (:clock db))
        "local stamps issued after a merge sort after everything seen")
    (is (= "A" (last (:clock db))) "node identity survives recv")))
