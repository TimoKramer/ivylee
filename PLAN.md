# ivylee — Plan

A local-first, Ivy Lee-style personal task manager. ClojureScript PWA that works
fully offline and optionally syncs to Cloudflare R2.

## The method

- A **longlist** holds all captured tasks (the backlog).
- Each day has a **shortlist** of at most **six** tasks (hard limit), picked from
  the longlist, ordered by priority, worked top-down.
- Planning happens in the morning: unfinished tasks from the most recent active
  day are **carried over** to today (counting against the limit), then the
  remaining slots are filled from the longlist.

## Architecture decisions

| Decision | Choice | Rationale |
|---|---|---|
| Build tooling | shadow-cljs | Only option with first-class nREPL; npm interop; hot reload |
| UI | Replicant | Data-driven rendering, no React dependency, small |
| Runtime truth | single app-db atom | Synchronous reads for rendering, watch-based reactivity, REPL-inspectable (`@app-db`) |
| Local persistence | konserve (IndexedDB backend) | Maintained, EDN-native; durability only — read once at boot, write-behind after edits; uniform API with the remote side |
| Remote storage | Cloudflare R2 via S3 API | Dumb object storage, no server to run |
| Remote access | **konserve-s3-cljs** — own browser backend for konserve (see sub-project below); SigV4 via aws4fetch or SubtleCrypto, CORS on the bucket | One storage API for local and remote; sync collapses into a single `update-in`; a thin Cloudflare Worker proxy remains a later option |
| Conflict model | CRDT: per-field LWW registers with hybrid logical clocks | Deterministic merge under clock skew, late syncs, multiple devices/users; ~100 lines instead of Automerge |
| Delivery | PWA (manifest + service worker) | One codebase for desktop and mobile; installable; code available offline |
| Sync posture | optional and on-demand | App is complete with no remote configured; sync is a background reconcile, never in the critical path of a user action |

Explicitly **not** building: tags, projects, recurring tasks, reminders,
search, websocket/realtime push. The method's value is its austerity.

## Data model

One CRDT document (`tasks.edn`) containing all tasks. Lists are **views**, not
containers: a task's location is a property of the task, so moving a task is a
single LWW write (atomic, idempotent, no cross-document consistency problems).

```clojure
{:tasks
 {#uuid "a3f…"
  {:title   {:v "Write sync layer" :t [1749722400123 0 "node-id"]}
   :list    {:v "2026-06-13"       :t hlc}   ; or :longlist
   :rank    {:v 3.0                :t hlc}   ; fractional, order within list
   :done?   {:v false              :t hlc}
   :completed-at {:v nil           :t hlc}
   :deleted {:v false              :t hlc}}}} ; tombstone — never dissoc
```

- A list = `(filter #(= list-id (:list %)) tasks)` sorted by `[rank task-id]`.
- Insert between ranks 3 and 4 ⇒ 3.5; renormalize ranks to integers on day
  rollover.
- Deletes are tombstones (LWW `:deleted` flag) so merges can't resurrect tasks;
  this also makes undo-delete one register flip. Compact old tombstones
  later if ever needed (likely never at this scale).

### Hybrid logical clock

Triple `[physical-ms logical-counter node-id]`, compared lexicographically.

- Local write: `pt = max(now, last.pt)`; same `pt` ⇒ increment counter, else 0.
- On merge with remote data: advance local clock to max of all observed clocks.
- `node-id`: random UUID per device, generated once, stored locally — the
  deterministic tie-breaker.
- Persist the last-issued HLC so a backwards clock jump across reload can't
  issue stale timestamps.
- **Exactly one place stamps HLCs**: the event-handler funnel where writes
  enter app-db.

### Merge

Pure function: field-wise, keep the value with the greater HLC; tasks present
on one side only are kept. Commutative, associative, idempotent — verified
with test.check generative tests (merge in any order ⇒ same doc).

Accepted trade-off: concurrent edits to the *same field* of the *same task*
lose one side silently (LWW by definition). Fine at per-field granularity for
a personal app.

## Sync (when configured)

```
app-db atom  ⇄  konserve/IndexedDB        (always)
     ⇅ reconcile loop
remote client → R2                        (only if configured)
```

- Push/pull is one operation:
  `(k/update-in remote-store ["tasks"] #(crdt/merge-docs % local-doc))` —
  the backend's optimistic locking (GET etag → PUT `If-Match` → on 412 retry,
  re-applying the update fn) re-merges automatically. CAS only prevents
  overwriting an unseen write; merge handles all conflict resolution.
  After: merge the result back into app-db, advance HLC via `latest-stamp`.
- Fallback if konserve-s3-cljs stalls: a minimal 3-fn client
  (`get-obj` / `put-obj` with if-match / `list-objs`) behind the same seam.
- Triggers: app open, window focus, `online` event, debounced after writes
  (konserve write hooks mark dirty).
- Sync state machine in app-db: `:not-configured | :idle | :syncing |
  :offline | :conflict | :error` → drives a small status indicator.
- Settings screen: R2 endpoint + credentials (or Worker URL + token),
  device name.
- Consider client-side encryption (WebCrypto / konserve's AES layer) before
  upload — decide before any server-side logic ever reads the data.

## App behavior

- **Hard six-task limit**: dropping onto a full day is rejected.
- **Rollover on app open** (not a midnight timer): if last-seen date < today,
  carry unfinished tasks from the most recent day that had any, preserving
  relative order at the top of today, then land on the planning view.
  Carry-over is just `:list := today` per task ⇒ idempotent across devices.
- Completed tasks stay struck-through on their day (history for free).
- Quick capture: always-reachable keyboard-first "add to longlist" input.
- Undo for delete (tombstone flip).

## UI

- **Planning view is the main screen**: longlist + day side by side.
  Responsive: two panes on desktop, single pane with tab/swipe on mobile —
  same view, CSS-only difference.
- **Tap-first**: "→ today / → tomorrow / → longlist" affordances are primary;
  pointer-event drag-and-drop is the desktop enhancement (HTML5 DnD is
  unusable on touch).
- Future days: today + next N days rail.

## PWA

- `manifest.json` + ~30-line cache-first service worker for the app shell.
  Data offline is IndexedDB's job; the service worker only makes the *code*
  load offline.
- Update flow: new SW installs in background → "reload for update" toast.
- iOS: Safari may evict storage after ~7 days unused; call
  `navigator.storage.persist()` on first run; installed (home screen) PWAs are
  largely exempt; R2 sync is the durability backstop.

## Sub-project: konserve-s3-cljs (own library)

A browser-capable konserve S3/R2 backend doesn't exist (konserve-s3 is
JVM-only). Decision: **write it ourselves** (in parallel to the app) and use
it as the app's remote side; konserve becomes the storage API for both local
and remote. Signing via aws4fetch (decided — no hand-rolled SigV4).

Detailed implementation plan: **[PLAN-konserve-s3-cljs.md](PLAN-konserve-s3-cljs.md)**
(moves into the library's own repo once created). konserve's encryption
layer then covers client-side encryption for free.

## Milestones

1. **Core domain (pure CLJC, no browser)**: HLC, merge, task model, rank
   logic, carry-over — with test.check generative tests. Fully REPL-driven.
2. **State + persistence**: app-db, event funnel (single HLC-stamping point),
   konserve/IndexedDB load-on-boot + write-behind.
3. **UI**: Replicant views — planning view, day rail, quick capture,
   tap-to-move, then desktop drag.
4. **konserve-s3-cljs**: the browser S3/R2 backend as its own library —
   SigV4 signing, backing-store protocol, optimistic locking, compliance
   suite against R2.
5. **Sync**: reconcile loop on top of konserve-s3-cljs, settings + status
   indicator.
6. **PWA**: manifest, service worker, update toast, `storage.persist()`.
