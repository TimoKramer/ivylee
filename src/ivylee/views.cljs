(ns ivylee.views
  "Pure hiccup builders for replicant.dom/render. No dispatching here — every
  interactive element emits data actions resolved by ivylee.main's
  execute-actions!/resolve-placeholder, keeping this namespace a straight fn
  of app-db. Markup is Tailwind utilities + daisyUI components; the one bit
  of custom CSS (responsive pane switching) lives in assets/tailwind.css."
  (:require
    [clojure.string :as str]
    [ivylee.model :as model]))


(defn- dispatch
  [event]
  [[:action/dispatch event]])


(defn- pad2
  [n]
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


(defn- task-menu
  "Edit / Move to <label> / Delete, positioned at `pos`. Rendered at
  app-view's top level rather than inside the row — .collapse's
  `isolation: isolate` would trap a nested menu's z-index behind the
  dismiss backdrop even though it's :fixed."
  [id moves pos]
  [:ul.menu.menu-sm.bg-base-100.rounded-box.shadow-sm.w-36.fixed.z-50
   {:style {:left (:x pos) :top (:y pos)}}
   [:li [:a {:on {:click [[:action/dispatch [:ui/edit-task {:id id}]]
                           [:action/dispatch [:ui/close-menu]]]}}
     "Edit"]]
   (for [[label move-list-id move-rank] moves]
     [:li {:replicant/key label}
      [:a {:on {:click [[:action/dispatch [:task/move {:id id :list-id move-list-id :rank move-rank}]]
                         [:action/dispatch [:ui/close-menu]]]}}
       (str "Move to " label)]])
   [:li [:a.text-error {:on {:click [[:action/delete-task id]
                                      [:action/dispatch [:ui/close-menu]]]}}
     "Delete"]]])


(defn- task-title
  "Opens the menu on long-press/right-click, or an editable input once
  editing? (commits on Enter/blur, discards on Escape)."
  [id title done? editing?]
  (if editing?
    [:input.input.input-ghost.input-sm.flex-1.w-full
     {:value title :autofocus true
      :on {:keydown [[:action/set-title id :event/key :event/target.value]]
           :blur    [[:action/set-title id "blur" :event/target.value]]}}]
    [:span.flex-1.select-none
     {:class (when done? "line-through opacity-60")
      :on {:contextmenu [[:action/open-menu id :event/client-x :event/client-y]]
           :touchstart  [[:action/long-press-start id :event/client-x :event/client-y]]
           :touchend    [[:action/long-press-cancel]]
           :touchmove   [[:action/long-press-cancel]]
           :touchcancel [[:action/long-press-cancel]]}}
     title]))


(defn- task-row
  "One task row."
  [[id task] editing?]
  (let [title (model/fval task :title)
        done? (model/fval task :done?)]
    [:li.task.flex.items-center.gap-2.rounded-box.px-3.py-2
     {:replicant/key id :class "bg-base-200/60"}
     [:input.checkbox.checkbox-sm
      {:type "checkbox" :checked done?
       :on {:change (dispatch [:task/toggle-done {:id id}])}}]
     (task-title id title done? editing?)]))


(defn- quick-capture
  [list-id]
  [:input.input.input-bordered.w-full.mb-3
   {:type "text" :placeholder "Add a task…"
    :on {:keydown [[:action/add-task list-id :event/key :event/target.value :event/target]]}}])


(defn- day-moves
  "Move targets for a task currently on `date`: every other named day (skip
  `date` itself — moving to your own list is a no-op) plus Longlist, which
  always lands at the top (re-triaged tasks surface for the next planning
  pass, see rank-at-top). `days` is the ordered [label date] pairs for
  Today/Tomorrow/Day after tomorrow."
  [doc date days]
  (concat
    (for [[label d] days :when (not= d date)]
      [label d (model/rank-at-end doc d)])
    [["Longlist" :longlist (model/rank-at-top doc :longlist)]]))


(defn- moves-for
  "Move targets for the open task-menu, looked up fresh by id."
  [doc id days]
  (let [current (model/fval (get-in doc [:tasks id]) :list)]
    (if (= current :longlist)
      (for [[label d] days] [label d (model/rank-at-end doc d)])
      (day-moves doc current days))))


(defn- longlist-pane
  [doc editing-id]
  [:section#pane-longlist
   [:h2.text-sm.font-semibold.mb-2 "Longlist"]
   (quick-capture :longlist)
   [:ul.tasks.flex.flex-col.gap-1
    (for [[id :as entry] (model/tasks-in doc :longlist)]
      (task-row entry (= editing-id id)))]])


(defn- day-tasks
  "Task list for `date`, plus its quick-capture."
  [doc date editing-id]
  (list
    (quick-capture date)
    [:ul.tasks.flex.flex-col.gap-1
     (for [[id :as entry] (model/tasks-in doc date)]
       (task-row entry (= editing-id id)))]))


(defn- day-pane
  [doc label date editing-id]
  [:section {:id (str "pane-" (str/lower-case label))}
   [:h2.text-sm.font-semibold.mb-2
    (str label " · " date " · " (count (model/tasks-in doc date)) "/" model/max-day-tasks)]
   (day-tasks doc date editing-id)])


(defn- flash-toast
  "day-full/undo-delete auto-dismiss after a few seconds (see ivylee.main's
  ::flash-auto-dismiss watch); sw-update sticks around until acted on — this
  only renders the current one, if any."
  [{:keys [type id title list-id]}]
  (when type
    [:div.toast.toast-end.toast-bottom {:replicant/key (or id type)}
     [:div.alert {:class (case type
                           :day-full "alert-warning"
                           :sw-update "alert-success"
                           "alert-info")}
      [:span (case type
               :day-full (str "Today is full (max " model/max-day-tasks " tasks) — "
                              (name list-id) " unchanged")
               :undo-delete (str "Deleted \"" title "\"")
               :sw-update "A new version of ivylee is available.")]
      (when (= type :undo-delete)
        [:button.btn.btn-sm {:on {:click [[:action/undo-delete id]]}} "Undo"])
      (when (= type :sw-update)
        [:button.btn.btn-sm {:on {:click [[:action/reload-for-update]]}} "Reload"])
      [:button.btn.btn-ghost.btn-sm.btn-circle
       {:on {:click [[:action/dismiss-flash]]}} "✕"]]]))


(defn- sync-indicator
  [sync-state]
  [:span.badge
   {:class (case sync-state
             :idle "badge-success"
             :syncing "badge-warning"
             :error "badge-error"
             "badge-ghost")}
   (name sync-state)])


(defn- connect-form
  []
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


(defn- connected-panel
  [{:keys [bucket id]} sync-state]
  [:div.flex.flex-col.gap-3
   [:div.text-sm.opacity-70 (str "bucket \"" bucket "\" · store " id)]
   (sync-indicator sync-state)
   [:button.btn.btn-error.btn-sm.self-start
    {:on {:click [[:action/disconnect-remote]]}} "Disconnect"]])


(defn- settings-dialog
  [remote-config sync-state]
  [:dialog#settings-dialog.modal
   [:div.modal-box
    [:h3.font-bold.text-lg.mb-4 "Sync settings"]
    (if remote-config
      (connected-panel remote-config sync-state)
      (connect-form))
    [:div.modal-action
     [:button.btn {:on {:click [[:action/close-settings]]}} "Close"]]]])


(defn app-view
  [{:keys [doc last-seen-day flash sync-state remote-config editing-id menu-id menu-pos]}]
  (let [tomorrow (add-days last-seen-day 1)
        days     [["Today" last-seen-day] ["Tomorrow" tomorrow]]]
    [:div.max-w-4xl.mx-auto.p-4
     [:div.navbar.bg-base-200.rounded-box.mb-4
      [:div.flex-1 [:h1.text-xl.font-bold.px-2 "ivylee"]]
      [:div.flex-none.gap-2.px-2
       (sync-indicator sync-state)
       [:button.btn.btn-ghost.btn-circle.btn-sm
        {:on {:click [[:action/open-settings]]}} "⚙"]]]
     [:div#tabs.tabs.tabs-boxed.mb-4
      [:input#tab-longlist.tab {:type "radio" :name "pane" :aria-label "Longlist" :checked true}]
      [:input#tab-today.tab {:type "radio" :name "pane" :aria-label "Today"}]
      [:input#tab-tomorrow.tab {:type "radio" :name "pane" :aria-label "Tomorrow"}]]
     [:div#panes.grid.gap-6.md:grid-cols-3
      (day-pane doc "Today" last-seen-day editing-id)
      (day-pane doc "Tomorrow" tomorrow editing-id)
      (longlist-pane doc editing-id)]
     ;; see task-menu for why this renders here, not nested in the row
     (when menu-id
       (list
         [:div.fixed.inset-0.z-40 {:on {:click (dispatch [:ui/close-menu])}}]
         (task-menu menu-id (moves-for doc menu-id days) menu-pos)))
     (flash-toast flash)
     (settings-dialog remote-config sync-state)]))
