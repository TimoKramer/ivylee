(ns ivylee.model
  "Pure operations on the task document. Every mutating fn takes the HLC stamp
  `t` as an argument — stamps are minted in one place (the event funnel), never
  here.")


(def max-day-tasks 6)

(def empty-doc {:tasks {}})


(defn- reg
  [v t]
  {:v v :t t})


(defn fval
  "Current value of a task's field."
  [task k]
  (get-in task [k :v]))


(defn- set-field
  [doc id k v t]
  (assoc-in doc [:tasks id k] (reg v t)))


(defn alive?
  [task]
  (not (fval task :deleted)))


(defn day-list?
  "Lists are either :longlist or an ISO date string."
  [list-id]
  (string? list-id))


(defn tasks-in
  "Alive tasks in a list as [id task] pairs, sorted by [rank id]."
  [doc list-id]
  (->> (:tasks doc)
       (filter (fn [[_ task]]
                 (and (alive? task) (= list-id (fval task :list)))))
       (sort-by (fn [[id task]] [(fval task :rank) (str id)]))))


(defn day-full?
  [doc day]
  (>= (count (tasks-in doc day)) max-day-tasks))


;; ranks ---------------------------------------------------------------------

(defn rank-between
  "A rank strictly between lo and hi; either side may be nil (open end)."
  [lo hi]
  (cond
    (and lo hi) (/ (+ lo hi) 2.0)
    lo          (inc lo)
    hi          (dec hi)
    :else       1.0))


(defn rank-at-end
  [doc list-id]
  (rank-between (some-> (last (tasks-in doc list-id)) second (fval :rank)) nil))


(defn rank-at-top
  [doc list-id]
  (rank-between nil (some-> (first (tasks-in doc list-id)) second (fval :rank))))


(defn- ranks-at-top
  "n ascending ranks that all sort before the current head of the list."
  [doc list-id n]
  (let [head (or (some-> (first (tasks-in doc list-id)) second (fval :rank)) 1.0)]
    (map #(- head (- n %)) (range n))))


(defn renormalize-ranks
  "Reset a list's ranks to 1.0..n in current order (run on day rollover so
  fractional ranks never degenerate)."
  [doc list-id t]
  (reduce (fn [d [i [id _]]] (set-field d id :rank (double (inc i)) t))
          doc
          (map-indexed vector (tasks-in doc list-id))))


;; task operations ------------------------------------------------------------

(defn add-task
  [doc id title list-id rank t]
  (assoc-in doc [:tasks id]
            {:title        (reg title t)
             :list         (reg list-id t)
             :rank         (reg rank t)
             :done?        (reg false t)
             :completed-at (reg nil t)
             :deleted      (reg false t)}))


(defn set-title
  [doc id title t]
  (set-field doc id :title title t))


(defn set-done
  [doc id done? t]
  (-> doc
      (set-field id :done? done? t)
      (set-field id :completed-at (when done? (first t)) t)))


(defn delete-task
  [doc id t]
  (set-field doc id :deleted true t))


(defn undelete-task
  [doc id t]
  (set-field doc id :deleted false t))


(defn move-task
  "Move a task to list-id at rank. Returns the new doc, or nil when the move
  would put a 7th task on a day (hard limit). Reordering within the same list
  is always allowed."
  [doc id list-id rank t]
  (let [current (fval (get-in doc [:tasks id]) :list)]
    (when (or (= current list-id)
              (not (day-list? list-id))
              (not (day-full? doc list-id)))
      (-> doc
          (set-field id :list list-id t)
          (set-field id :rank rank t)))))


;; carry-over ------------------------------------------------------------------

(defn- unfinished-days
  [doc today]
  (->> (vals (:tasks doc))
       (filter alive?)
       (remove #(fval % :done?))
       (map #(fval % :list))
       (filter #(and (day-list? %) (neg? (compare % today))))
       distinct
       sort))


(defn carry-over
  "Move unfinished tasks from the most recent day before `today` to the top of
  today, preserving their order. Tasks that don't fit under the six-task limit
  fall back to the top of the longlist for re-triage. Idempotent: it only acts
  on past days, and moved tasks no longer match."
  [doc today t]
  (if-let [day (last (unfinished-days doc today))]
    (let [carried        (->> (tasks-in doc day)
                              (remove (fn [[_ task]] (fval task :done?)))
                              (map first))
          slots          (- max-day-tasks (count (tasks-in doc today)))
          [fits overflow] (split-at slots carried)
          fit-ranks      (ranks-at-top doc today (count fits))
          overflow-ranks (ranks-at-top doc :longlist (count overflow))
          place          (fn [d [id list-id rank]]
                           (-> d
                               (set-field id :list list-id t)
                               (set-field id :rank rank t)))]
      (reduce place doc
              (concat (map vector fits (repeat today) fit-ranks)
                      (map vector overflow (repeat :longlist) overflow-ranks))))
    doc))
