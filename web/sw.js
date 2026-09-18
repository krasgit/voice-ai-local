// Minimal service worker: cache the static app shell so the app is installable
// and loads offline. WebSocket/LLM traffic is never cached.
const CACHE = "voiceai-v1";
const SHELL = ["/", "/index.html", "/app.js", "/style.css", "/worklet.js", "/manifest.json", "/icon.svg"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys =>
    Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.pathname === "/ws") return;   // never cache WS/dynamic
  e.respondWith(
    caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
      // Runtime-cache same-origin static assets.
      if (res.ok && url.origin === location.origin){
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy));
      }
      return res;
    }).catch(() => hit))
  );
});
