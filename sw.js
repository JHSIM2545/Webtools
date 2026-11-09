// SIM Tools service worker: 앱 껍데기(HTML·아이콘)만 캐시. 방송 스트림 등 외부 요청은 건드리지 않음.
// 새 툴 파일을 추가하면 SHELL 목록에 넣고 CACHE 숫자를 올리세요.
const CACHE = 'simwebtools-shell-v56';
const SHELL = ['./', './index.html', './tv.html', './note.html', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png', './tv-icon-192.png', './note-icon-192.png', './phone.html', './phone-icon-192.png', './aitel.html', './aitel-icon-192.png', './assistant.html', './assistant-icon-192.png', './calendar.html', './calendar-icon-192.png', './folder.html', './folder-icon-192.png', './message.html', './message-icon-192.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return; // 외부(스트림·CDN)는 통과
  if (req.mode === 'navigate') {
    // 페이지는 네트워크 우선 → 실패 시 같은 페이지의 캐시 → 없으면 런처
    e.respondWith(fetch(req, { cache: 'no-cache' }).then(res => {   // GitHub Pages의 10분 HTTP 캐시를 건너뛰고 항상 최신 확인
      const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return res;
    }).catch(() => caches.match(req).then(hit => hit || caches.match('./index.html'))));
    return;
  }
  e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => {
    const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); return res;
  })));
});
