// SIM Tools service worker: 앱 껍데기(HTML·아이콘)만 캐시. 방송 스트림 등 외부 요청은 건드리지 않음.
// 새 툴 파일을 추가하면 SHELL 목록에 넣고 CACHE 숫자를 올리세요.
const CACHE = 'simwebtools-shell-v244';
const SHELL = ['./brief-sounds/brief_s_chime.wav', './brief-sounds/brief_s_pop.wav', './brief-sounds/brief_s_bell.wav', './brief-sounds/brief_s_harp.wav', './brief-sounds/brief_s_game.wav', './brief-sounds/brief_s_morning.wav', './brief-sounds/brief_s_synth.wav', './', './index.html', './ui-guard.js', './aiphone.js', './tv.html', './note.html', './manifest.webmanifest',
  './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png', './tv-icon-192.png', './note-icon-192.png', './phone.html', './phone-icon-192.png', './aitel.html', './aitel-icon-192.png', './assistant.html', './qr.min.js', './sync.js', './appmenu.js', './assistant-icon-192.png', './calendar.html', './calendar.webmanifest', './calendar-icon-192.png', './calendar-icon-512.png', './calendar-icon-maskable-512.png', './pin-icon.svg', './folder.html', './folder-icon-192.png', './message.html', './message-icon-192.png', './orchestra.html', './orchestra-icon-192.png', './radio.html', './radio-icon-192.png', './calc.html', './calc-icon-192.png', './kbo.html', './kbo-icon-192.png', './kbo-bg.png', './ai-gemini.png', './ai-groq.png', './map.html', './map-icon-192.png', './cat-food.png', './cat-hospital.png', './pad.html', './pad-icon-192.png', './teams.html', './teams-icon-192.png', './scan.html', './scan-icon-192.png', './recipe.html', './recipe-icon-192.png', './brief.html', './brief-icon-192.png', './price.html', './price-icon-192.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k.indexOf('simwebtools-shell') === 0 && k !== CACHE).map(k => caches.delete(k))))   // 공유 보관함(simphone-share·simnote-share)은 지우지 않음
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  const url = new URL(req.url);
  // 다른 앱 → 공유 → SIM Tools: 연락처(vCard)는 SIM Phone 으로, 일정(.ics)은 SIM Calendar 로 보관함에 넣고 넘긴다 (manifest의 share_target 하나를 같이 씀)
  if (req.method === 'POST' && url.origin === location.origin && /\/(share-target|calendar\.html)$/.test(url.pathname)) {   // calendar.html 로 직접 POST 되는 것은 설치한 'SIM Calendar' 앱이 받은 일정 공유
    e.respondWith((async () => {
      const vcards = [], icals = [], docs = [];
      // PDF·그림·Word/Excel/PowerPoint 파일은 SIM Note 로 (글자로 읽지 않고 파일 그대로 보관)
      const isDoc = f => /^(image\/|application\/pdf)/.test(f.type || '') || /\.(pdf|jpe?g|png|webp|gif|bmp|heic|heif|docx|xlsx|xls|pptx)$/i.test(f.name || '') || /(officedocument|ms-excel)/.test(f.type || '');
      try {
        const fd = await req.formData();
        const sort = t => { if (/BEGIN:VCARD/i.test(t)) vcards.push(t); else if (/BEGIN:V(CALENDAR|EVENT)/i.test(t)) icals.push(t); };
        for (const [, v] of fd.entries()) { if (typeof v === 'string') sort(v); else if (v && isDoc(v)) docs.push(v); else if (v && v.size < 8e6 && typeof v.text === 'function') sort(await v.text()); }
      } catch (err) {}
      try {
        if (docs.length && !vcards.length && !icals.length) {
          const c = await caches.open('simnote-share');
          for (const k of await c.keys()) await c.delete(k);
          for (let i = 0; i < docs.length; i++) await c.put(new URL('./share-file-' + String(i).padStart(3, '0'), self.registration.scope).href, new Response(docs[i], { headers: { 'Content-Type': docs[i].type || 'application/octet-stream', 'X-Name': encodeURIComponent(docs[i].name || ('file' + i)) } }));
          return Response.redirect(new URL('./note.html?shared=1', self.registration.scope).href, 303);
        }
        if (/calendar\.html$/.test(url.pathname) || (icals.length && !vcards.length)) {
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
  if (/\/__share\//.test(url.pathname)) return;   // 앱이 받아 둔 공유 파일은 서비스워커가 건드리지 않음
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

/* 출발 알림(웹푸시): assistant-trip 함수가 보낸다 */
self.addEventListener('push', e => {
  let d = {}; try { d = e.data.json(); } catch (_) { d = { body: e.data ? e.data.text() : '' }; }
  e.waitUntil(self.registration.showNotification(d.title || 'SIM Assistant', { body: d.body || '', tag: d.tag, icon: 'assistant-icon-192.png', requireInteraction: true, vibrate: [300, 150, 300], data: { url: d.url || 'assistant.html' } }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close(); const u = (e.notification.data && e.notification.data.url) || 'assistant.html';
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(l => { for (const c of l) if ('focus' in c) return c.focus(); return self.clients.openWindow(u); }));
});
