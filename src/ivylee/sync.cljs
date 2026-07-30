(ns ivylee.sync
  "Optional remote reconcile over konserve-s3-cljs (own library, consumed via
  deps.edn :local/root — not yet on Clojars). app-db + IndexedDB stay the
  source of truth (see ivylee.persist); this only folds a remote merge back
  in through the ordinary event funnel, never in the critical path of a user
  action. No settings UI yet — see ivylee.main/configure-remote! for the
  REPL/console entry point milestone 3's settings screen will eventually
  replace."
  (:require [clojure.core.async :refer [go <!]]
            [ivylee.app :as app]
            [ivylee.crdt :as crdt]
            [konserve.core :as k]
            [konserve-s3.core :refer [connect-s3-store]]))

(def remote-doc-key
  "Key under which the whole CRDT doc lives in the remote store, matching
  the local IndexedDB store's :doc key (see ivylee.persist)."
  :doc)

(defn connect
  "Connect to (creating if absent) the configured remote store. `s3-spec` is
  konserve-s3.core/connect-s3-store's spec map (:endpoint :bucket :access-key
  :secret :id, plus optional :region/:path-style?/:session-token). Sets
  :optimistic-locking-retries unless the caller already supplied one, so a
  conflicting write from another device retries the merge (see reconcile!)
  instead of throwing — the whole point of CAS + LWW merge is to never need
  a human to resolve a conflict."
  [s3-spec]
  (connect-s3-store
   (update s3-spec :config #(merge {:optimistic-locking-retries 5} %))))

(defn reconcile!
  "One push/pull cycle against an already-connected remote store: merge the
  local doc into the remote doc — the backend's ETag CAS (ETag → PUT
  If-Match → on 412 retry, re-applying the merge) makes this safe against a
  concurrent write from another device — then fold the reconciled result
  back into app-db via :remote/merged so the clock advances at its one
  stamping point. Dispatches :sync/start, then :sync/success or :sync/error;
  never throws."
  [system remote-store]
  (go
    (app/dispatch! system [:sync/start])
    (let [local-doc (:doc @(:app-db system))
          result    (<! (k/update-in remote-store [remote-doc-key]
                                     #(crdt/merge-docs % local-doc)
                                     {:sync? false}))
          _ (tap> result)]
      (if (instance? js/Error result)
        (app/dispatch! system [:sync/error {:error result}])
        (let [[_ merged-doc] result]
          (app/dispatch! system [:remote/merged {:doc merged-doc}])
          (app/dispatch! system [:sync/success])))
      nil)))
