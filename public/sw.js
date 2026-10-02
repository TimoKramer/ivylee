// Cache-first app shell. Only the code needs to load offline — data offline
// is IndexedDB's job (see ivylee.persist). No file list to maintain: every
// same-origin GET response gets cached the first time it's fetched online,
// then served from cache thereafter.
const CACHE = "ivylee-shell-v2";

self.addEventListener("install", (event) => {
  // Relative to this script's own URL, so it precaches the right thing
  // whether served at the origin root or under a subpath (e.g. GitHub
  // Pages project sites at /<repo>/).
  event.waitUntil(caches.open(CACHE).then((cache) => cache.add("./")));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

// Lets a waiting worker take over immediately when the user clicks "Reload"
// on the update toast (see ivylee.main/register-service-worker!).
self.addEventListener("message", (event) => {
  if (event.data === "SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  if (new URL(event.request.url).origin !== self.location.origin) return;
  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) return cached;
      return fetch(event.request).then((resp) => {
        if (resp.ok) {
          const copy = resp.clone();
          caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        }
        return resp;
      });
    })
  );
});
