(ns ivylee.main
  (:require [clojure.core.async :refer [go <!]]
            [ivylee.app :as app]
            [ivylee.model :as model]
            [ivylee.persist :as persist]
            [ivylee.sync :as sync]))

;; Composition root: the one global reference, required by shadow-cljs's
;; init-fn/after-load hooks and handy at the REPL:
;;   (-> @ivylee.main/!system :app-db deref)
(defonce !system (atom nil))

;; Debounce id for the after-write sync trigger; survives hot-reload like
;; !system does.
(defonce reconcile-timeout (atom nil))

(defn- reconcile-if-configured!
  [system]
  (when-let [remote-store @(:remote system)]
    (sync/reconcile! system remote-store)))

(defn- debounced-reconcile!
  "Coalesces bursts of writes into one reconcile call ~1s after the last one."
  [system]
  (when @(:remote system)
    (some-> @reconcile-timeout js/clearTimeout)
    (reset! reconcile-timeout
            (js/setTimeout #(reconcile-if-configured! system) 1000))))

(defn configure-remote!
  "Connect this session to a remote store and switch on sync. Stands in for
  milestone 3's settings screen — call from the browser console/REPL, e.g.
  (ivylee.main/configure-remote!
    {:endpoint \"https://<account>.r2.cloudflarestorage.com\"
     :bucket \"ivylee\" :access-key \"…\" :secret \"…\" :id \"<store-uuid>\"})
  Connects, reconciles once immediately, then the focus/online/debounced-
  write triggers registered in init! take over for the rest of the session."
  [s3-spec]
  (go
    (let [system       @!system
          remote-store (<! (sync/connect s3-spec))]
      (reset! (:remote system) remote-store)
      (<! (sync/reconcile! system remote-store)))))

(defn- pad [n]
  (if (< n 10) (str "0" n) (str n)))

(defn today-str
  "Local-timezone ISO date — day rollover follows the wall clock on the wall."
  []
  (let [d (js/Date.)]
    (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d)))))

(defn- render!
  "Placeholder until Replicant lands in milestone 3."
  [{:keys [app-db]}]
  (when-let [el (js/document.getElementById "app")]
    (let [{:keys [doc node-id last-seen-day flash sync-state]} @app-db]
      (set! (.-textContent el)
            (str "ivylee · node " node-id
                 " · day " last-seen-day
                 " · today " (count (model/tasks-in doc last-seen-day)) "/6"
                 " · longlist " (count (model/tasks-in doc :longlist))
                 " · sync " (name sync-state)
                 (when flash (str " · ⚠ " (:type flash))))))))

(defn init! []
  (go
    (let [store  (<! (persist/connect))
          system (app/new-system store (<! (persist/load-db store)))]
      (reset! !system system)
      (app/dispatch! system [:day/rollover {:today (today-str)}])
      (add-watch (:app-db system) ::render (fn [_ _ _ _] (render! system)))
      (add-watch (:app-db system) ::reconcile-on-write
                 (fn [_ _ before after]
                   (when (not= (:doc before) (:doc after))
                     (debounced-reconcile! system))))
      ;; re-check rollover when the app regains focus (left open overnight);
      ;; also a good moment to reconcile if sync is configured
      (.addEventListener js/window "focus"
                         #(do (app/dispatch! system [:day/rollover {:today (today-str)}])
                              (reconcile-if-configured! system)))
      (.addEventListener js/window "online" #(reconcile-if-configured! system))
      (.addEventListener js/window "offline" #(app/dispatch! system [:sync/offline]))
      (reconcile-if-configured! system)
      (render! system)
      (js/console.log "ivylee booted — state: (-> @ivylee.main/!system :app-db deref)"))))

(defn reload! []
  (some-> @!system render!))
