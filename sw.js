// SIM Tools service worker: 앱 껍데기(HTML·아이콘)만 캐시. 방송 스트림 등 외부 요청은 건드리지 않음.
// 새 툴 파일을 추가하면 SHELL 목록에 넣고 CACHE 숫자를 올리세요.
const CACHE = 'simwebtools-shell-v120';
const SHELL = ['./', './index.html', './ui-guard.js', './tv.html', './note.html', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png', './tv-icon-192.png', './note-icon-192.png', './phone.html', './phone-icon-192.png', './aitel.html', './aitel-icon-192.png', './assistant.html', './qr.min.js', './sync.js', './assistant-icon-192.png', './calendar.html', './calendar-icon-192.png', './pin-icon.svg', './folder.html', './folder-icon-192.png', './message.html', './message-icon-192.png', './orchestra.html', './orchestra-icon-192.png', './radio.html', './radio-icon-192.png', './calc.html', './calc-icon-192.png', './kbo.html', './kbo-icon-192.png', './kbo-bg.png', './ai-gemini.png', './ai-claude.png', './ai-groq.png', './map.html', './map-icon-192.png', './pad.html', './pad-icon-192.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k.indexOf('simwebtools-shell') === 0 && k !== CACHE).map(k => caches.delete(k))))   // 공유 보관함(simphone-share)은 지우지 않음
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);
  // 다른 앱 → 공유 → SIM Tools: 연락처(vCard)는 SIM Phone 으로, 일정(.ics)은 SIM Calendar 로 보관함에 넣고 넘긴다 (manifest의 share_target 하나를 같이 씀)
  if (req.method === 'POST' && url.origin === location.origin && /\/share-target$/.test(url.pathname)) {
    e.respondWith((async () => {
      const vcards = [], icals = [];
      try {
        const fd = await req.formData();
        const sort = t => { if (/BEGIN:VCARD/i.test(t)) vcards.push(t); else if (/BEGIN:V(CALENDAR|EVENT)/i.test(t)) icals.push(t); };
        for (const [, v] of fd.entries()) { if (typeof v === 'string') sort(v); else if (v && v.size < 8e6 && typeof v.text === 'function') sort(await v.text()); }
      } catch (err) {}
      try {
        if (icals.length && !vcards.length) {
          const c = await caches.open('simcal-share');
          await c.put('./share-pending', new Response(JSON.stringify({ t: Date.now(), items: icals.map(text => ({ text })) }), { headers: { 'Content-Type': 'application/json' } }));
          return Response.redirect(new URL('./calendar.html?import=1', self.registration.scope).href, 303);
        }
        const c = await caches.open('simphone-share');
        await c.put(new URL('./share-inbox', self.registration.scope).href, new Response(JSON.stringify(vcards), { headers: { 'Content-Type': 'application/json' } }));
      } catch (err) {}
      return Response.redirect(new URL('./phone.html?shared=1', self.registration.scope).href, 303);
    })());
    return;
  }
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
