(ns ivylee.events
  "The single funnel through which every state change flows. Handlers are
  pure: (handle db event now-ms) -> db'. HLC stamps are minted here and
  nowhere else; one event = one stamp."
  (:require [ivylee.crdt :as crdt]
            [ivylee.hlc :as hlc]
            [ivylee.model :as model]))

(defn- stamp
  "Advance the db's clock for one event; returns [db' stamp]."
  [db now-ms]
  (let [clock (hlc/tick (:clock db) now-ms)]
    [(assoc db :clock clock) clock]))

(defmulti handle (fn [_db [event-id] _now-ms] event-id))

(defmethod handle :default [_db [event-id] _now]
  (throw (ex-info "Unknown event" {:event event-id})))

(defmethod handle :task/add
  [db [_ {:keys [id title list-id]}] now]
  (let [list-id (or list-id :longlist)]
    (if (and (model/day-list? list-id) (model/day-full? (:doc db) list-id))
      (assoc db :flash {:type :day-full :list-id list-id})
      (let [[db' t] (stamp db now)
            id      (or id (random-uuid))
            rank    (model/rank-at-end (:doc db) list-id)]
        (update db' :doc model/add-task id title list-id rank t)))))

(defmethod handle :task/set-title
  [db [_ {:keys [id title]}] now]
  (let [[db' t] (stamp db now)]
    (update db' :doc model/set-title id title t)))

(defmethod handle :task/toggle-done
  [db [_ {:keys [id]}] now]
  (let [[db' t] (stamp db now)
        done?   (model/fval (get-in db [:doc :tasks id]) :done?)]
    (update db' :doc model/set-done id (not done?) t)))

(defmethod handle :task/delete
  [db [_ {:keys [id]}] now]
  (let [title   (model/fval (get-in db [:doc :tasks id]) :title)
        [db' t] (stamp db now)]
    (-> db'
        (update :doc model/delete-task id t)
        (assoc :flash {:type :undo-delete :id id :title title}))))

(defmethod handle :task/undelete
  [db [_ {:keys [id]}] now]
  (let [[db' t] (stamp db now)]
    (update db' :doc model/undelete-task id t)))

(defmethod handle :task/move
  [db [_ {:keys [id list-id rank]}] now]
  (let [[db' t] (stamp db now)]
    (if-let [doc' (model/move-task (:doc db') id list-id rank t)]
      (assoc db' :doc doc')
      ;; hard limit hit: discard the stamped db, surface the rejection
      (assoc db :flash {:type :day-full :list-id list-id}))))

(defmethod handle :day/rollover
  [db [_ {:keys [today]}] now]
  (if (= today (:last-seen-day db))
    db
    (let [[db' t] (stamp db now)]
      (-> db'
          (update :doc model/carry-over today t)
          (update :doc model/renormalize-ranks today t)
          (assoc :last-seen-day today)))))

(defmethod handle :remote/merged
  [db [_ {:keys [doc]}] now]
  (let [merged (crdt/merge-docs (:doc db) doc)
        clock  (if-let [remote-t (crdt/latest-stamp doc)]
                 (hlc/recv (:clock db) remote-t now)
                 (:clock db))]
    (assoc db :doc merged :clock clock)))

(defmethod handle :flash/clear
  [db _ _]
  (dissoc db :flash))

;; Sync state is ephemeral (not part of the CRDT doc), so these handlers
;; don't stamp the clock — mirrors :flash/clear.

(defmethod handle :sync/start
  [db _ _]
  (assoc db :sync-state :syncing))

(defmethod handle :sync/success
  [db _ _]
  (-> db (assoc :sync-state :idle) (dissoc :sync-error)))

(defmethod handle :sync/error
  [db [_ {:keys [error]}] _]
  (assoc db :sync-state :error :sync-error error))

(defmethod handle :sync/offline
  [db _ _]
  (assoc db :sync-state :offline))
