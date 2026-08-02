(ns ivylee.main
  (:require [clojure.core.async :refer [go <!]]
            [clojure.string :as str]
            [ivylee.app :as app]
            [ivylee.persist :as persist]
            [ivylee.sync :as sync]
            [ivylee.views :as views]
            [replicant.dom :as r]))

;; Composition root: the one global reference, required by shadow-cljs's
;; init-fn/after-load hooks and handy at the REPL:
;;   (-> @ivylee.main/!system :app-db deref)
(defonce !system (atom nil))

;; Debounce id for the after-write sync trigger; survives hot-reload like
;; !system does.
(defonce reconcile-timeout (atom nil))

;; Auto-dismiss id for the undo-delete toast; survives hot-reload like
;; !system does.
(defonce flash-timeout (atom nil))
(def flash-timeout-ms 5000)

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
  "Connect this session to a remote store, remember the spec for next boot
  (see ivylee.persist), and switch on sync. Callable from the settings screen,
  or the browser console/REPL, e.g.
  (ivylee.main/configure-remote!
    {:endpoint \"https://<account>.r2.cloudflarestorage.com\"
     :bucket \"ivylee\" :access-key \"…\" :secret \"…\" :id \"<store-uuid>\"})
  Connects, reconciles once immediately, then the focus/online/debounced-
  write triggers registered in init! take over for the rest of the session."
  [s3-spec]
  (go
    (let [system       @!system
          remote-store (<! (sync/connect s3-spec))]
      (if (instance? js/Error remote-store)
        (app/dispatch! system [:sync/error {:error remote-store}])
        (do (reset! (:remote system) remote-store)
            (app/dispatch! system [:remote/configure {:spec s3-spec}])
            (<! (sync/reconcile! system remote-store)))))))

(defn disconnect-remote!
  "Forgets the remote config and switches sync off; the next boot won't
  auto-reconnect."
  []
  (let [system @!system]
    (reset! (:remote system) nil)
    (app/dispatch! system [:remote/disconnect])))

(defn- pad [n]
  (if (< n 10) (str "0" n) (str n)))

(defn today-str
  "Local-timezone ISO date — day rollover follows the wall clock on the wall."
  []
  (let [d (js/Date.)]
    (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d)))))

(defn- render!
  [{:keys [app-db]}]
  (when-let [el (js/document.getElementById "app")]
    (r/render el (views/app-view @app-db))))

(defn- resolve-placeholder
  "Substitutes ivylee.views' :event/... placeholders with the live DOM
  event's data at dispatch time (Replicant does not do this for you — see
  https://replicant.fun/event-handlers/)."
  [dom-event x]
  (case x
    :event/key          (.-key dom-event)
    :event/target       (.-target dom-event)
    :event/target.value (.. dom-event -target -value)
    x))

(defn- execute-actions!
  [system dom-event actions]
  (doseq [[action & args] actions
          :let [args (map (partial resolve-placeholder dom-event) args)]]
    (case action
      :action/dispatch
      (let [[event] args]
        (app/dispatch! system event))

      :action/add-task
      (let [[list-id key title target] args]
        (when (and (= key "Enter") (seq (str/trim title)))
          (app/dispatch! system [:task/add {:title (str/trim title) :list-id list-id}])
          (set! (.-value target) "")))

      :action/delete-task
      (let [[id] args]
        (app/dispatch! system [:task/delete {:id id}])
        (some-> @flash-timeout js/clearTimeout)
        (reset! flash-timeout
                (js/setTimeout #(app/dispatch! system [:flash/clear]) flash-timeout-ms)))

      :action/undo-delete
      (let [[id] args]
        (some-> @flash-timeout js/clearTimeout)
        (app/dispatch! system [:task/undelete {:id id}])
        (app/dispatch! system [:flash/clear]))

      :action/dismiss-flash
      (do (some-> @flash-timeout js/clearTimeout)
          (app/dispatch! system [:flash/clear]))

      :action/configure-remote
      (let [[form] args
            field  #(.. form -elements (namedItem %) -value)
            id     (field "id")]
        (configure-remote! {:endpoint   (field "endpoint")
                            :bucket     (field "bucket")
                            :access-key (field "access-key")
                            :secret     (field "secret")
                            :id         (if (seq id) id (str (random-uuid)))}))

      :action/disconnect-remote
      (disconnect-remote!)

      :action/open-settings
      (some-> (js/document.getElementById "settings-dialog") .showModal)

      :action/close-settings
      (some-> (js/document.getElementById "settings-dialog") .close))))

(defn init! []
  (go
    (let [store  (<! (persist/connect))
          system (app/new-system store (<! (persist/load-db store)))]
      (reset! !system system)
      (r/set-dispatch! (fn [{:replicant/keys [dom-event]} actions]
                         (when (= "submit" (.-type dom-event))
                           (.preventDefault dom-event))
                         (execute-actions! system dom-event actions)))
      (when-let [spec (:remote-config @(:app-db system))]
        (configure-remote! spec))
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

(comment
  (configure-remote!
    {:endpoint "https://6082a4bec6fcf0524b9292cb1d91fbb7.eu.r2.cloudflarestorage.com"
     :bucket "ivylee"
     :access-key "…"
     :secret "…"}))

(comment
  ;; cljs debugging: opens an in-page Portal overlay (ctrl/cmd+shift+o) that
  ;; every subsequent tap> call sends values to.
  (require '[portal.web :as p])
  (def p (p/open))
  (add-tap p/submit)
  (tap> :foo)

  (-> @ivylee.main/!system :app-db deref :sync-error .-stack))
