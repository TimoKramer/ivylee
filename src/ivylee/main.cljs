(ns ivylee.main
  (:require [clojure.core.async :refer [go <!]]
            [ivylee.app :as app]
            [ivylee.model :as model]
            [ivylee.persist :as persist]))

;; Composition root: the one global reference, required by shadow-cljs's
;; init-fn/after-load hooks and handy at the REPL:
;;   (-> @ivylee.main/!system :app-db deref)
(defonce !system (atom nil))

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
    (let [{:keys [doc node-id last-seen-day flash]} @app-db]
      (set! (.-textContent el)
            (str "ivylee · node " node-id
                 " · day " last-seen-day
                 " · today " (count (model/tasks-in doc last-seen-day)) "/6"
                 " · longlist " (count (model/tasks-in doc :longlist))
                 (when flash (str " · ⚠ " (:type flash))))))))

(defn init! []
  (go
    (let [store  (<! (persist/connect))
          system (app/new-system store (<! (persist/load-db store)))]
      (reset! !system system)
      (app/dispatch! system [:day/rollover {:today (today-str)}])
      (add-watch (:app-db system) ::render (fn [_ _ _ _] (render! system)))
      ;; re-check rollover when the app regains focus (left open overnight)
      (.addEventListener js/window "focus"
                         #(app/dispatch! system [:day/rollover {:today (today-str)}]))
      (render! system)
      (js/console.log "ivylee booted — state: (-> @ivylee.main/!system :app-db deref)"))))

(defn reload! []
  (some-> @!system render!))
