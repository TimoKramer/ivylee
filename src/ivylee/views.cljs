(ns ivylee.views
  "Pure hiccup builders for replicant.dom/render. No dispatching here — every
  interactive element emits data actions resolved by ivylee.main's
  execute-actions/resolve-placeholder, keeping this namespace a straight fn of
  app-db."
  (:require [ivylee.model :as model]))

(defn- dispatch [event]
  [[:action/dispatch event]])

(defn- task-row
  "One task row. `move-to` is the [list-id rank] tap-move target — the other
  pane from wherever this row is rendered."
  [[id task] [move-list-id move-rank]]
  (let [title (model/fval task :title)
        done? (model/fval task :done?)]
    [:li.task {:replicant/key id}
     [:input {:type "checkbox" :checked done?
              :on {:change (dispatch [:task/toggle-done {:id id}])}}]
     [:span.task-title {:class (when done? "done")} title]
     [:button.task-move
      {:on {:click (dispatch [:task/move {:id id :list-id move-list-id :rank move-rank}])}}
      (if (model/day-list? move-list-id) "→ today" "→ longlist")]
     [:button.task-delete {:on {:click (dispatch [:task/delete {:id id}])}} "✕"]]))

(defn- quick-capture [list-id]
  [:input.quick-capture
   {:type "text" :placeholder "Add a task…"
    :on {:keydown [[:action/add-task list-id :event/key :event/target.value :event/target]]}}])

(defn- longlist-pane [doc today]
  [:section#pane-longlist.pane
   [:h2 "Longlist"]
   (quick-capture :longlist)
   [:ul.tasks
    (for [entry (model/tasks-in doc :longlist)]
      (task-row entry [today (model/rank-at-end doc today)]))]])

(defn- today-pane [doc today]
  [:section#pane-today.pane
   [:h2 (str "Today · " today " · " (count (model/tasks-in doc today)) "/" model/max-day-tasks)]
   (quick-capture today)
   [:ul.tasks
    (for [entry (model/tasks-in doc today)]
      (task-row entry [:longlist (model/rank-at-top doc :longlist)]))]])

(defn- flash-banner [{:keys [type list-id]}]
  (when type
    [:div.flash
     [:span (case type
              :day-full (str "Today is full (max " model/max-day-tasks " tasks) — "
                             (name list-id) " unchanged")
              (str type))]
     [:button {:on {:click (dispatch [:flash/clear])}} "✕"]]))

(defn- sync-indicator [sync-state]
  [:span.sync {:class (name sync-state)} (name sync-state)])

(defn app-view
  [{:keys [doc last-seen-day flash sync-state]}]
  [:div.app
   [:header
    [:h1 "ivylee"]
    (sync-indicator sync-state)]
   (flash-banner flash)
   [:nav.pane-tabs
    [:input#tab-longlist {:type "radio" :name "pane" :checked true}]
    [:label {:for "tab-longlist"} "Longlist"]
    [:input#tab-today {:type "radio" :name "pane"}]
    [:label {:for "tab-today"} "Today"]]
   [:div.panes
    (today-pane doc last-seen-day)
    (longlist-pane doc last-seen-day)]])
