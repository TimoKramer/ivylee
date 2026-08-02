(ns ivylee.views
  "Pure hiccup builders for replicant.dom/render. No dispatching here — every
  interactive element emits data actions resolved by ivylee.main's
  execute-actions!/resolve-placeholder, keeping this namespace a straight fn
  of app-db. Markup is Tailwind utilities + daisyUI components; the one bit
  of custom CSS (responsive pane switching) lives in assets/tailwind.css."
  (:require [ivylee.model :as model]))

(defn- dispatch [event]
  [[:action/dispatch event]])

(defn- task-row
  "One task row. `move-to` is the [list-id rank] tap-move target — the other
  pane from wherever this row is rendered."
  [[id task] [move-list-id move-rank]]
  (let [title (model/fval task :title)
        done? (model/fval task :done?)]
    [:li.task.flex.items-center.gap-2.rounded-box.px-3.py-2
     {:replicant/key id :class "bg-base-200/60"}
     [:input.checkbox.checkbox-sm
      {:type "checkbox" :checked done?
       :on {:change (dispatch [:task/toggle-done {:id id}])}}]
     [:span.flex-1 {:class (when done? "line-through opacity-60")} title]
     [:button.btn.btn-ghost.btn-xs
      {:on {:click (dispatch [:task/move {:id id :list-id move-list-id :rank move-rank}])}}
      (if (model/day-list? move-list-id) "→ today" "→ longlist")]
     [:button.btn.btn-ghost.btn-xs.text-error
      {:on {:click [[:action/delete-task id]]}} "✕"]]))

(defn- quick-capture [list-id]
  [:input.input.input-bordered.w-full.mb-3
   {:type "text" :placeholder "Add a task…"
    :on {:keydown [[:action/add-task list-id :event/key :event/target.value :event/target]]}}])

(defn- longlist-pane [doc today]
  [:section#pane-longlist
   [:h2.text-sm.font-semibold.mb-2 "Longlist"]
   (quick-capture :longlist)
   [:ul.tasks.flex.flex-col.gap-1
    (for [entry (model/tasks-in doc :longlist)]
      (task-row entry [today (model/rank-at-end doc today)]))]])

(defn- today-pane [doc today]
  [:section#pane-today
   [:h2.text-sm.font-semibold.mb-2
    (str "Today · " today " · " (count (model/tasks-in doc today)) "/" model/max-day-tasks)]
   (quick-capture today)
   [:ul.tasks.flex.flex-col.gap-1
    (for [entry (model/tasks-in doc today)]
      (task-row entry [:longlist (model/rank-at-top doc :longlist)]))]])

(defn- flash-toast
  "Both flash types auto-dismiss after a few seconds (see ivylee.main's
  ::flash-auto-dismiss watch) — this only renders the current one, if any."
  [{:keys [type id title list-id]}]
  (when type
    [:div.toast.toast-end.toast-bottom {:replicant/key (or id type)}
     [:div.alert {:class (if (= type :day-full) "alert-warning" "alert-info")}
      [:span (case type
               :day-full (str "Today is full (max " model/max-day-tasks " tasks) — "
                             (name list-id) " unchanged")
               :undo-delete (str "Deleted \"" title "\""))]
      (when (= type :undo-delete)
        [:button.btn.btn-sm {:on {:click [[:action/undo-delete id]]}} "Undo"])
      [:button.btn.btn-ghost.btn-sm.btn-circle
       {:on {:click [[:action/dismiss-flash]]}} "✕"]]]))

(defn- sync-indicator [sync-state]
  [:span.badge
   {:class (case sync-state
             :idle "badge-success"
             :syncing "badge-warning"
             :error "badge-error"
             "badge-ghost")}
   (name sync-state)])

(defn- connect-form []
  [:form.flex.flex-col.gap-2
   {:on {:submit [[:action/configure-remote :event/target]]}}
   [:input.input.input-bordered
    {:name "endpoint" :required true
     :placeholder "Endpoint (https://<account>.r2.cloudflarestorage.com)"}]
   [:input.input.input-bordered {:name "bucket" :required true :placeholder "Bucket"}]
   [:input.input.input-bordered {:name "access-key" :required true :placeholder "Access key"}]
   [:input.input.input-bordered
    {:name "secret" :type "password" :required true :placeholder "Secret"}]
   [:input.input.input-bordered
    {:name "id" :placeholder "Store id — same value on every synced device (blank = generate new)"}]
   [:button.btn.btn-primary.mt-2 {:type "submit"} "Connect"]])

(defn- connected-panel [{:keys [bucket id]} sync-state]
  [:div.flex.flex-col.gap-3
   [:div.text-sm.opacity-70 (str "bucket \"" bucket "\" · store " id)]
   (sync-indicator sync-state)
   [:button.btn.btn-error.btn-sm.self-start
    {:on {:click [[:action/disconnect-remote]]}} "Disconnect"]])

(defn- settings-dialog [remote-config sync-state]
  [:dialog#settings-dialog.modal
   [:div.modal-box
    [:h3.font-bold.text-lg.mb-4 "Sync settings"]
    (if remote-config
      (connected-panel remote-config sync-state)
      (connect-form))
    [:div.modal-action
     [:button.btn {:on {:click [[:action/close-settings]]}} "Close"]]]])

(defn app-view
  [{:keys [doc last-seen-day flash sync-state remote-config]}]
  [:div.max-w-4xl.mx-auto.p-4
   [:div.navbar.bg-base-200.rounded-box.mb-4
    [:div.flex-1 [:h1.text-xl.font-bold.px-2 "ivylee"]]
    [:div.flex-none.gap-2.px-2
     (sync-indicator sync-state)
     [:button.btn.btn-ghost.btn-circle.btn-sm
      {:on {:click [[:action/open-settings]]}} "⚙"]]]
   [:div#tabs.tabs.tabs-boxed.mb-4
    [:input#tab-longlist.tab {:type "radio" :name "pane" :aria-label "Longlist" :checked true}]
    [:input#tab-today.tab {:type "radio" :name "pane" :aria-label "Today"}]]
   [:div#panes.grid.gap-6.md:grid-cols-2
    (today-pane doc last-seen-day)
    (longlist-pane doc last-seen-day)]
   (flash-toast flash)
   (settings-dialog remote-config sync-state)])
