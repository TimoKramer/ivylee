(ns ivylee.app
  "dispatch! over an explicit system map — no namespace-level state.
  Rendering hangs off a watch on the system's app-db; persistence happens as
  a write-behind effect of dispatch!."
  (:require [ivylee.events :as events]
            [ivylee.persist :as persist]))

(defn new-system
  "Runtime state bundle, created once at boot and passed explicitly. `:remote`
  holds the connected konserve-s3 store once sync is configured (nil until
  then — sync is optional, see ivylee.sync)."
  [store initial-db]
  {:store  store
   :app-db (atom initial-db)
   :remote (atom nil)})

(defn dispatch!
  [{:keys [app-db store]} event]
  (let [before @app-db
        after  (events/handle before event (js/Date.now))]
    (when (not= before after)
      (reset! app-db after)
      (persist/save-changed! store before after))
    after))

(comment
  (dispatch! @ivylee.main/!system [:task/add {:title "foobar"}]))
