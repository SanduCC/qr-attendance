// Minimal service worker: stale-while-revalidate for static assets ONLY.
// HTML pages, /a/*, /sessions/*, /login and OAuth are auth-sensitive and token-bound,
// so they are never intercepted and always go straight to the network.
// Bump CACHE when shipping changed CSS/JS so clients drop the old copies.
const CACHE = 'qa-static-v5';
const PRECACHE = [
  '/css/app.css',
  '/js/sw-register.js',
  '/js/landing.js',
  '/js/display.js',
  '/js/checkin-location.js',
  '/js/start-options.js',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      // One missing asset must not break installation.
      .then((cache) => Promise.allSettled(PRECACHE.map((url) => cache.add(url))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isStaticAsset(url) {
  const p = url.pathname;
  return p.startsWith('/css/') || p.startsWith('/js/') || p.startsWith('/icons/')
    || p === '/manifest.webmanifest';
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || !isStaticAsset(url)) return; // network, untouched

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(req);
    const network = fetch(req)
      .then((res) => {
        if (res.ok && res.type === 'basic') cache.put(req, res.clone());
        return res;
      })
      .catch(() => null);
    if (cached) {
      event.waitUntil(network);
      return cached;
    }
    const res = await network;
    return res || Response.error();
  })());
});
