(ns ivylee.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [ivylee.crdt :as crdt]
            [ivylee.model :as m]))

(defn- t
  "Shorthand HLC stamp."
  ([ms] (t ms "A"))
  ([ms node] [ms 0 node]))

(defn- day-with-tasks [doc day n start-ms]
  (reduce (fn [d i]
            (m/add-task d (keyword (str "task" day i))
                        (str "task " i) day (double i) (t (+ start-ms i))))
          doc
          (range n)))

(deftest hard-six-task-limit
  (let [doc (-> m/empty-doc
                (day-with-tasks "2026-06-12" 6 100)
                (m/add-task :extra "one more" :longlist 1.0 (t 200)))]
    (testing "moving a 7th task onto a full day is rejected"
      (is (m/day-full? doc "2026-06-12"))
      (is (nil? (m/move-task doc :extra "2026-06-12" 99.0 (t 300)))))
    (testing "reordering within a full day is allowed"
      (is (some? (m/move-task doc :task2026-06-120 "2026-06-12" 9.0 (t 300)))))
    (testing "moving to the longlist is never limited"
      (is (some? (m/move-task doc :task2026-06-120 :longlist 1.0 (t 300)))))))

(deftest tombstones-survive-merge
  (let [base    (m/add-task m/empty-doc :a "task" :longlist 1.0 (t 100))
        deleted (m/delete-task base :a (t 200 "B"))]
    (testing "merging with a replica from before the delete keeps it deleted"
      (let [merged (crdt/merge-docs base deleted)]
        (is (false? (m/alive? (get-in merged [:tasks :a]))))
        (is (empty? (m/tasks-in merged :longlist)))))
    (testing "undelete after delete wins"
      (let [restored (m/undelete-task deleted :a (t 300))]
        (is (m/alive? (get-in (crdt/merge-docs deleted restored) [:tasks :a])))))))

(deftest carry-over-basics
  (let [doc (-> m/empty-doc
                (m/add-task :a "first"  "2026-06-11" 1.0 (t 100))
                (m/add-task :b "second" "2026-06-11" 2.0 (t 101))
                (m/add-task :c "done"   "2026-06-11" 3.0 (t 102))
                (m/set-done :c true (t 103)))
        after (m/carry-over doc "2026-06-12" (t 200))]
    (testing "unfinished tasks move to today, order preserved"
      (is (= [:a :b] (map first (m/tasks-in after "2026-06-12")))))
    (testing "completed tasks stay on their day"
      (is (= [:c] (map first (m/tasks-in after "2026-06-11")))))
    (testing "idempotent: a second carry-over is a no-op"
      (is (= after (m/carry-over after "2026-06-12" (t 300)))))))

(deftest carry-over-respects-limit
  (let [doc   (-> m/empty-doc
                  (day-with-tasks "2026-06-11" 4 100)
                  (day-with-tasks "2026-06-12" 4 200))
        after (m/carry-over doc "2026-06-12" (t 300))]
    (testing "only the fitting tasks land on today, at the top"
      (is (= 6 (count (m/tasks-in after "2026-06-12"))))
      (is (m/day-full? after "2026-06-12")))
    (testing "overflow falls back to the longlist"
      (is (= 2 (count (m/tasks-in after :longlist)))))))

(deftest carry-over-skips-to-most-recent-active-day
  (let [doc   (-> m/empty-doc
                  (m/add-task :old "stale" "2026-06-01" 1.0 (t 100))
                  (m/add-task :new "fresh" "2026-06-10" 1.0 (t 101)))
        after (m/carry-over doc "2026-06-12" (t 200))]
    (is (= [:new] (map first (m/tasks-in after "2026-06-12"))))
    (is (= [:old] (map first (m/tasks-in after "2026-06-01"))))))

(deftest carry-over-converges-across-devices
  (let [doc (-> m/empty-doc
                (m/add-task :a "first"  "2026-06-11" 1.0 (t 100))
                (m/add-task :b "second" "2026-06-11" 2.0 (t 101)))
        ;; both devices roll over independently before syncing
        dev-a  (m/carry-over doc "2026-06-12" (t 200 "A"))
        dev-b  (m/carry-over doc "2026-06-12" (t 201 "B"))
        merged (crdt/merge-docs dev-a dev-b)]
    (is (= [:a :b] (map first (m/tasks-in merged "2026-06-12"))))
    (is (empty? (m/tasks-in merged "2026-06-11")))))

(deftest done-tracks-completion-time
  (let [doc (-> m/empty-doc
                (m/add-task :a "task" "2026-06-12" 1.0 (t 100))
                (m/set-done :a true (t 555)))]
    (is (= 555 (m/fval (get-in doc [:tasks :a]) :completed-at)))
    (is (nil? (-> doc (m/set-done :a false (t 600))
                  (get-in [:tasks :a]) (m/fval :completed-at))))))

(deftest rank-helpers
  (is (= 2.5 (m/rank-between 2.0 3.0)))
  (is (= 3.0 (m/rank-between 2.0 nil)))
  (is (= 1.0 (m/rank-between nil nil)))
  (let [doc  (day-with-tasks m/empty-doc "2026-06-12" 3 100)
        doc' (m/renormalize-ranks doc "2026-06-12" (t 200))]
    (is (= [1.0 2.0 3.0]
           (map (fn [[_ task]] (m/fval task :rank))
                (m/tasks-in doc' "2026-06-12"))))))
