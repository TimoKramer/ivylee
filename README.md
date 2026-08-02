# ivylee

A local-first, Ivy Lee-style personal task manager. ClojureScript PWA that
works fully offline and optionally syncs to Cloudflare R2. See `PLAN.md` for
the design and milestones.

## Dev setup

Install JS dependencies first (Tailwind/daisyUI, shadow-cljs):

```sh
npm install
```

Then start the dev environment — this runs `shadow-cljs watch app` and the
Tailwind CSS watcher together:

```sh
npm run dev
```

This serves the app at http://localhost:8080 and writes the nREPL port to
`.shadow-cljs/nrepl.port` for Conjure/CIDER to connect to. A new nREPL
session starts in plain Clojure mode — jack into the ClojureScript build
once per session with:

```clojure
(shadow/repl :app)
```

CSS is compiled from `assets/tailwind.css` into `public/css/app.css`
(gitignored build artifact, like `public/js/`). To rebuild it once without
the watcher (e.g. before a deploy):

```sh
npm run css:build
```

## Tests

```sh
clj -X:test
```
