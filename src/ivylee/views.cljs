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

(defn- flash-banner [{:keys [type list-id]}]
  (when (= type :day-full)
    [:div.alert.alert-warning.mb-4
     [:span (str "Today is full (max " model/max-day-tasks " tasks) — "
                (name list-id) " unchanged")]
     [:button.btn.btn-ghost.btn-xs.btn-circle
      {:on {:click [[:action/dismiss-flash]]}} "✕"]]))

(defn- undo-toast [{:keys [type id title]}]
  (when (= type :undo-delete)
    [:div.toast.toast-end.toast-bottom {:replicant/key id}
     [:div.alert.alert-info
      [:span (str "Deleted \"" title "\"")]
      [:button.btn.btn-sm {:on {:click [[:action/undo-delete id]]}} "Undo"]
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

(defn app-view
  [{:keys [doc last-seen-day flash sync-state]}]
  [:div.max-w-4xl.mx-auto.p-4
   [:div.navbar.bg-base-200.rounded-box.mb-4
    [:div.flex-1 [:h1.text-xl.font-bold.px-2 "ivylee"]]
    [:div.flex-none.px-2 (sync-indicator sync-state)]]
   (flash-banner flash)
   [:div#tabs.tabs.tabs-boxed.mb-4
    [:input#tab-longlist.tab {:type "radio" :name "pane" :aria-label "Longlist" :checked true}]
    [:input#tab-today.tab {:type "radio" :name "pane" :aria-label "Today"}]]
   [:div#panes.grid.gap-6.md:grid-cols-2
    (today-pane doc last-seen-day)
    (longlist-pane doc last-seen-day)]
   (undo-toast flash)])
