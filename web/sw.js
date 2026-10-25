// Offline support: precache the app shell, then serve cache-first and refresh in the background.
const VERSION = 'puzhe-2.2.2';
const SHELL = [
  './', 'index.html', 'styles/app.css', 'manifest.webmanifest', 'assets/icon.svg',
  'src/main.js', 'src/core/chess.js', 'src/core/book.js',
  'src/engine/client.js', 'src/engine/worker.js', 'src/engine/search.js', 'src/engine/personality.js', 'src/engine/review.js',
  'src/ui/board.js', 'src/ui/pieces.js', 'src/ui/graph.js', 'src/ui/icons.js', 'src/ui/sound.js',
  'data/openings.txt', 'data/pieces.json',
  'vendor/stockfish/stockfish-19-lite-single.js', 'vendor/stockfish/stockfish-19-lite-single.wasm',
];

self.addEventListener('install', e => e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()),
));
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(caches.open(VERSION).then(async cache => {
    const hit = await cache.match(e.request, { ignoreSearch: true });
    const fresh = fetch(e.request).then(r => { if (r.ok) cache.put(e.request, r.clone()); return r; }).catch(() => hit);
    return hit || fresh;
  }));
});
