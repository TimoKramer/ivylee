(ns ivylee.views
  "Pure hiccup builders for replicant.dom/render. No dispatching here — every
  interactive element emits data actions resolved by ivylee.main's
  execute-actions!/resolve-placeholder, keeping this namespace a straight fn
  of app-db. Markup is Tailwind utilities + daisyUI components; the one bit
  of custom CSS (responsive pane switching) lives in assets/tailwind.css."
  (:require [clojure.string :as str]
            [ivylee.model :as model]))

(defn- dispatch [event]
  [[:action/dispatch event]])

(defn- pad2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- add-days
  "`iso-date` + `n` days, as an ISO date string. Uses the local Date
  constructor (year, month, day) rather than parsing the ISO string
  directly — `(js/Date. \"2026-08-02\")` parses as UTC midnight, which can
  shift a day backward in negative-UTC-offset timezones once you read its
  local getFullYear/getMonth/getDate back out."
  [iso-date n]
  (let [[y m d] (map js/parseInt (str/split iso-date #"-"))
        dt      (js/Date. y (dec m) (+ d n))]
    (str (.getFullYear dt) "-" (pad2 (inc (.getMonth dt))) "-" (pad2 (.getDate dt)))))

(defn- move-dropdown
  "A single → trigger that expands (CSS-only, :focus-within) into the given
  [label list-id rank] tap-move targets."
  [id moves]
  [:div.dropdown.dropdown-end
   [:div.btn.btn-ghost.btn-xs {:tabIndex 0 :role "button"} "→"]
   [:ul.dropdown-content.menu.menu-sm.bg-base-100.rounded-box.z-10.w-32.shadow-sm
    {:tabIndex -1}
    (for [[label move-list-id move-rank] moves]
      [:li {:replicant/key label}
       [:a {:on {:click (dispatch [:task/move {:id id :list-id move-list-id :rank move-rank}])}}
        label]])]])

(defn- task-row
  "One task row. `moves` is a seq of [label list-id rank] tap-move targets,
  offered behind a single → dropdown."
  [[id task] moves]
  (let [title (model/fval task :title)
        done? (model/fval task :done?)]
    [:li.task.flex.items-center.gap-2.rounded-box.px-3.py-2
     {:replicant/key id :class "bg-base-200/60"}
     [:input.checkbox.checkbox-sm
      {:type "checkbox" :checked done?
       :on {:change (dispatch [:task/toggle-done {:id id}])}}]
     [:span.flex-1 {:class (when done? "line-through opacity-60")} title]
     (move-dropdown id moves)
     [:button.btn.btn-ghost.btn-xs.text-error
      {:on {:click [[:action/delete-task id]]}} "✕"]]))

(defn- quick-capture [list-id]
  [:input.input.input-bordered.w-full.mb-3
   {:type "text" :placeholder "Add a task…"
    :on {:keydown [[:action/add-task list-id :event/key :event/target.value :event/target]]}}])

(defn- longlist-pane [doc today tomorrow]
  [:section#pane-longlist
   [:h2.text-sm.font-semibold.mb-2 "Longlist"]
   (quick-capture :longlist)
   [:ul.tasks.flex.flex-col.gap-1
    (for [entry (model/tasks-in doc :longlist)]
      (task-row entry [["Today" today (model/rank-at-end doc today)]
                       ["Tomorrow" tomorrow (model/rank-at-end doc tomorrow)]]))]])

(defn- day-tasks
  "Task list for `date`, plus its quick-capture — shared by today and the
  future-day rail. `moves` are each row's tap-move targets (see task-row)."
  [doc date moves]
  (list
   (quick-capture date)
   [:ul.tasks.flex.flex-col.gap-1
    (for [entry (model/tasks-in doc date)]
      (task-row entry moves))]))

(defn- future-day-section
  "A compact, collapsed-by-default day card for the rail under today."
  [doc date]
  [:div.collapse.collapse-arrow.rounded-box
   {:replicant/key date :class "bg-base-200/60"}
   [:input {:type "checkbox"}]
   [:div.collapse-title.text-sm.font-medium.py-2.min-h-0
    (str date " · " (count (model/tasks-in doc date)) "/" model/max-day-tasks)]
   [:div.collapse-content
    ;; re-triaged tasks always land at the top of the longlist (surfaces for
    ;; the next planning pass, see rank-at-top)
    (day-tasks doc date [["Longlist" :longlist (model/rank-at-top doc :longlist)]])]])

(defn- today-pane [doc today tomorrow future-days]
  [:section#pane-today
   [:h2.text-sm.font-semibold.mb-2
    (str "Today · " today " · " (count (model/tasks-in doc today)) "/" model/max-day-tasks)]
   (day-tasks doc today [["Longlist" :longlist (model/rank-at-top doc :longlist)]
                         ["Tomorrow" tomorrow (model/rank-at-end doc tomorrow)]])
   [:div.mt-4.flex.flex-col.gap-2
    (for [date future-days]
      (future-day-section doc date))]])

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
    (today-pane doc last-seen-day (add-days last-seen-day 1)
                [(add-days last-seen-day 1) (add-days last-seen-day 2)])
    (longlist-pane doc last-seen-day (add-days last-seen-day 1))]
   (flash-toast flash)
   (settings-dialog remote-config sync-state)])
