/* SIM Tools 공통 ⋯ 메뉴 (모든 도구 화면에서 불러옴)
   · 상단 바 오른쪽 끝에 ⋯ 버튼 → [버전 정보] [권한 정보]
   · 버전 정보: 앱 버전·빌드 번호·빌드 날짜·화면(웹) 버전, 업데이트 확인과 수동 업데이트
   · 권한 정보: 앱이 쓰는 권한을 켜고 끄기
   안드로이드 앱(APK)에서는 앱이 제공하는 SimApp 창구를 쓰고, 일반 브라우저에서는 웹 정보만 보여 준다.
   런처(index.html)는 자체 ⋯ 메뉴에서 window.simAppMenu.open('ver' | 'perm') 으로 같은 창을 연다. */
(() => {
  if (window.simAppMenu) return;
  const NA = window.SimApp && typeof window.SimApp.info === 'function' ? window.SimApp : null;
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const fmt = ms => { try { return ms ? new Date(Number(ms)).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) + ' (KST)' : '—'; } catch (e) { return '—'; } };

  /* ── 스타일 ── */
  function css() {
    if (document.getElementById('simvmCss')) return;
    const st = document.createElement('style'); st.id = 'simvmCss';
    st.textContent =
      '.simvm-btn{flex:none;display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;padding:0;margin-left:2px;border:0;border-radius:10px;background:transparent;color:var(--text,#fff);cursor:pointer;-webkit-tap-highlight-color:transparent}' +
      '.simvm-btn:active{background:rgba(127,127,127,.25)}.simvm-btn svg{width:20px;height:20px;fill:currentColor;pointer-events:none}' +
      '.simvm-pop{position:fixed;z-index:2147482000;min-width:176px;background:var(--panel,#1c1f2a);color:var(--text,#fff);border:1px solid var(--line,#333a4d);border-radius:12px;padding:6px;box-shadow:0 10px 28px rgba(0,0,0,.55)}' +
      '.simvm-pop button{display:flex;align-items:center;gap:10px;width:100%;text-align:left;border:0;background:transparent;color:inherit;font:inherit;font-size:15px;padding:11px 12px;border-radius:8px;cursor:pointer;white-space:nowrap}' +
      '.simvm-pop button:active{background:rgba(127,127,127,.22)}' +
      '.simvm-ov{position:fixed;inset:0;z-index:2147482500;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;padding:16px}' +
      '.simvm-card{width:min(420px,100%);max-height:calc(100vh - 32px);display:flex;flex-direction:column;background:var(--panel,#1c1f2a);color:var(--text,#fff);border:1px solid var(--line,#333a4d);border-radius:16px;box-shadow:0 18px 50px rgba(0,0,0,.6);overflow:hidden}' +
      '.simvm-hd{display:flex;align-items:center;gap:8px;padding:14px 16px;border-bottom:1px solid var(--line,#333a4d)}.simvm-hd b{flex:1;font-size:17px}' +
      '.simvm-x{border:0;background:transparent;color:var(--sub,#9aa3b5);font-size:20px;line-height:1;padding:4px 8px;cursor:pointer}' +
      '.simvm-bd{padding:6px 16px 14px;overflow:auto;-webkit-overflow-scrolling:touch}' +
      '.simvm-row{display:flex;justify-content:space-between;gap:14px;padding:10px 0;border-bottom:1px solid var(--line,#333a4d);font-size:14.5px}.simvm-row:last-child{border-bottom:0}' +
      '.simvm-row span:first-child{color:var(--sub,#9aa3b5);flex:none}.simvm-row span:last-child{text-align:right;word-break:break-all;font-variant-numeric:tabular-nums}' +
      '.simvm-st{margin:12px 0 4px;padding:10px 12px;border-radius:10px;background:rgba(127,127,127,.14);font-size:14px;line-height:1.55;white-space:pre-line}' +
      '.simvm-st.ok{background:rgba(48,190,110,.16)}.simvm-st.up{background:rgba(255,170,40,.18)}.simvm-st.err{background:rgba(255,91,91,.16)}' +
      '.simvm-act{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}' +
      '.simvm-act button{flex:1 1 140px;border:1px solid var(--line,#333a4d);background:rgba(127,127,127,.16);color:inherit;font:inherit;font-size:14.5px;font-weight:600;padding:11px 12px;border-radius:10px;cursor:pointer}' +
      '.simvm-act button.pri{background:var(--accent,#4a8cff);border-color:transparent;color:#fff}.simvm-act button:disabled{opacity:.55}' +
      '.simvm-pr{display:flex;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid var(--line,#333a4d)}.simvm-pr:last-of-type{border-bottom:0}' +
      '.simvm-pr .tx{flex:1;min-width:0}.simvm-pr .tx b{display:block;font-size:15px}.simvm-pr .tx small{display:block;color:var(--sub,#9aa3b5);font-size:12.5px;margin-top:2px}' +
      '.simvm-sw{position:relative;flex:none;width:46px;height:26px;border-radius:99px;border:0;padding:0;background:#3a4056;cursor:pointer;transition:background .15s}.simvm-sw::after{content:"";position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;transition:left .15s}' +
      '.simvm-sw.on{background:var(--ok,#30be6e)}.simvm-sw.on::after{left:23px}.simvm-sw[disabled]{opacity:.5;cursor:default}' +
      '.simvm-note{margin-top:10px;color:var(--sub,#9aa3b5);font-size:12.5px;line-height:1.55}' +
      '.simvm-toast{position:fixed;left:50%;bottom:calc(28px + env(safe-area-inset-bottom));transform:translateX(-50%);z-index:2147483200;max-width:86vw;background:rgba(20,22,30,.95);color:#fff;border:1px solid #3b4258;border-radius:12px;padding:11px 16px;font-size:14px;text-align:center}';
    document.head.appendChild(st);
  }
  function toast(t) {
    css(); const e = document.createElement('div'); e.className = 'simvm-toast'; e.textContent = t; document.body.appendChild(e);
    setTimeout(() => e.remove(), 3200);
  }

  /* ── 팝업 창 ── */
  let cur = null;
  function closeModal() { if (cur) { cur.remove(); cur = null; } window.__simAppPerm = null; window.__simAppUpd = null; window.__simAppUpdSt = null; }
  function modal(title) {
    css(); closeModal();
    const ov = document.createElement('div'); ov.className = 'simvm-ov'; ov.setAttribute('role', 'dialog'); ov.setAttribute('aria-modal', 'true');
    ov.innerHTML = '<div class="simvm-card"><div class="simvm-hd"><b>' + esc(title) + '</b><button class="simvm-x" aria-label="닫기">✕</button></div><div class="simvm-bd"></div></div>';
    ov.addEventListener('click', e => { if (e.target === ov) closeModal(); });
    ov.querySelector('.simvm-x').onclick = closeModal;
    document.body.appendChild(ov); cur = ov;
    return ov.querySelector('.simvm-bd');
  }

  /* ── 화면(웹) 버전: 서비스 워커 캐시 이름의 번호 ── */
  async function webVer() {
    try { const ks = await caches.keys(); return ks.map(k => /^simwebtools-shell-v(\d+)$/.exec(k)).filter(Boolean).map(m => +m[1]).sort((a, b) => b - a)[0] || 0; } catch (e) { return 0; }
  }
  async function webLatest() {
    try {
      const r = await fetch(new URL('sw.js', location.href).href + '?t=' + Date.now(), { cache: 'no-store' });
      const m = /simwebtools-shell-v(\d+)/.exec(await r.text()); return m ? +m[1] : 0;
    } catch (e) { return 0; }
  }
  async function webApply() {   // 저장된 데이터는 건드리지 않고, 화면 파일 캐시만 지우고 새로 받는다
    try { const rs = await navigator.serviceWorker.getRegistrations(); await Promise.all(rs.map(r => r.unregister())); } catch (e) {}
    try { const ks = await caches.keys(); await Promise.all(ks.filter(k => /^simwebtools-shell/.test(k)).map(k => caches.delete(k))); } catch (e) {}
    location.reload();
  }
  const nativeInfo = () => { try { return NA ? JSON.parse(NA.info()) : null; } catch (e) { return null; } };
  const appName = () => { const h = document.querySelector('header h1, .bar h1'); return h ? h.textContent.replace(/\s+/g, ' ').trim() : document.title; };

  /* ── 버전 정보 ── */
  async function openVer() {
    const bd = modal('버전 정보');
    bd.innerHTML = '<div class="simvm-st">불러오는 중…</div>';
    const info = nativeInfo(), wv = await webVer();
    const rows = info ? [
      ['앱', info.name], ['버전', info.versionName || '—'], ['빌드 번호', '#' + info.build], ['빌드 날짜', fmt(info.buildTime)],
      ['화면(웹) 버전', wv ? 'v' + wv : '—'], ['패키지', info.pkg], ['안드로이드', 'API ' + info.sdk]
    ] : [
      ['앱', appName()], ['실행 방식', '웹(브라우저·설치형 웹앱)'], ['화면(웹) 버전', wv ? 'v' + wv : '—']
    ];
    bd.innerHTML = rows.map(r => '<div class="simvm-row"><span>' + esc(r[0]) + '</span><span>' + esc(r[1]) + '</span></div>').join('') +
      '<div class="simvm-st" id="svSt">"업데이트 확인"을 눌러 새 버전이 있는지 확인하세요.</div>' +
      '<div class="simvm-act"><button id="svChk" class="pri">🔄 업데이트 확인</button><button id="svApp" class="pri" hidden>⬇ 앱 업데이트</button><button id="svWeb" class="pri" hidden>♻ 화면 새로 받기</button></div>' +
      '<div class="simvm-note">' + (info ? '앱 업데이트는 새 APK 를 내려받아 설치합니다(설치 화면에서 "설치"를 눌러 주세요). ' : '') + '화면 새로 받기는 저장된 데이터는 그대로 두고 화면 파일만 새로 받습니다.</div>';
    const $ = id => bd.querySelector('#' + id), st = (t, k) => { const e = $('svSt'); if (e) { e.className = 'simvm-st' + (k ? ' ' + k : ''); e.textContent = t; } };
    $('svWeb').onclick = () => { st('화면을 새로 받는 중…'); webApply(); };
    $('svApp').onclick = () => { if (NA) NA.installUpdate(); };
    window.__simAppUpdSt = s => st(s === 'start' ? '새 버전을 내려받는 중… (알림창에서 진행 상황을 볼 수 있어요)' : s === 'done' ? '내려받았어요. 설치 화면에서 "설치"를 눌러 주세요.' : s === 'perm' ? '설정에서 "이 출처 허용"을 켠 뒤 돌아와 다시 눌러 주세요.' : '내려받기·설치에 실패했어요. 인터넷을 확인하고 다시 시도해 주세요.', s === 'fail' || s === 'perm' ? 'err' : 'up');
    $('svChk').onclick = async () => {
      const b = $('svChk'); b.disabled = true; st('확인하는 중…');
      const nat = NA ? new Promise(res => { const t = setTimeout(() => res({ ok: false, err: '응답 없음' }), 15000); window.__simAppUpd = r => { clearTimeout(t); res(r || { ok: false }); }; NA.checkUpdate(); }) : Promise.resolve(null);
      const [wl, nr] = await Promise.all([webLatest(), nat]);
      b.disabled = false; const lines = []; let need = false, bad = false;
      if (nr) {
        if (nr.ok) { if (nr.need) { need = true; lines.push('앱: 새 빌드 #' + nr.latest + ' 있음 (현재 #' + nr.current + ')'); } else lines.push('앱: 최신 빌드예요 (#' + nr.current + ')'); }
        else { bad = true; lines.push('앱: 확인하지 못했어요 (인터넷 확인)'); }
      }
      let webNeed = false;
      if (wl) { if (wv && wl > wv) { webNeed = true; lines.push('화면: 새 버전 v' + wl + ' 있음 (현재 v' + wv + ')'); } else lines.push('화면: 최신이에요 (v' + (wv || wl) + ')'); }
      else { bad = true; lines.push('화면: 확인하지 못했어요 (인터넷 확인)'); }
      $('svApp').hidden = !need; $('svWeb').hidden = !webNeed && !need && !(wl && !wv);
      if (!$('svWeb').hidden && !webNeed) $('svWeb').textContent = '♻ 화면 새로 받기';
      st(lines.join('\n') + (need ? '\n\n⬇ "앱 업데이트"를 눌러 설치하세요.' : webNeed ? '\n\n♻ "화면 새로 받기"를 눌러 주세요.' : ''), bad ? 'err' : (need || webNeed) ? 'up' : 'ok');
    };
  }

  /* ── 권한 정보 ── */
  async function webPerms() {
    const list = [['camera', '카메라', '명함·문서 스캔, 사진 촬영', 'camera'], ['mic', '마이크', '음성 입력·받아쓰기', 'microphone'], ['location', '위치', '현재 위치 등록·길안내', 'geolocation'], ['notif', '알림', '새 소식 알림', 'notifications']];
    const out = [];
    for (const [id, label, desc, name] of list) {
      let state = 'unknown'; try { state = (await navigator.permissions.query({ name })).state; } catch (e) {}
      out.push({ id, label, desc, granted: state === 'granted', state });
    }
    return out;
  }
  async function openPerm() {
    const bd = modal('권한 정보');
    let list = [], busy = false, ask = '';
    const load = async () => {
      if (NA) { try { list = JSON.parse(NA.perms() || '[]'); } catch (e) { list = []; } }
      else list = await webPerms();
      paint();
    };
    const cur13 = NA && (nativeInfo() || {}).sdk >= 33;
    const paint = () => {
      bd.innerHTML = (list.length ? list.map(p =>
        '<div class="simvm-pr"><div class="tx"><b>' + esc(p.label) + '</b><small>' + (ask === p.id ? '끌까요? ' + (cur13 ? '(앱이 잠시 종료돼요)' : '(폰 설정 화면이 열려요)') + ' <a href="#" data-y="' + esc(p.id) + '" style="color:var(--accent,#4a8cff);font-weight:700">끄기</a> · <a href="#" data-n="1" style="color:inherit">취소</a>' : esc(p.desc) + ' · ' + (p.granted ? '허용됨' : p.state === 'denied' ? '거절됨' : '꺼짐')) + '</small></div>' +
        '<button class="simvm-sw' + (p.granted ? ' on' : '') + '" role="switch" aria-checked="' + !!p.granted + '" aria-label="' + esc(p.label) + '" data-id="' + esc(p.id) + '"' + (NA ? '' : ' disabled') + '></button></div>').join('')
        : '<div class="simvm-st">이 앱이 사용하는 권한이 없어요.</div>') +
        (NA ? '<div class="simvm-act"><button id="pvSet">⚙ 폰 앱 설정 열기</button></div><div class="simvm-note">스위치를 켜면 권한을 요청하고, 끄면 권한을 해제합니다(안드로이드 13 이상은 앱이 잠시 종료되니 다시 열어 주세요). 요청창이 더 뜨지 않는 "다시 묻지 않음" 상태는 폰 설정에서 켜야 합니다.</div>'
          : '<div class="simvm-note">웹 브라우저에서는 여기서 바꿀 수 없어요. 주소창 왼쪽 자물쇠 → 사이트 설정에서 바꾸세요. 안드로이드 앱에서는 이 화면에서 켜고 끌 수 있어요.</div>');
      const set = bd.querySelector('#pvSet'); if (set) set.onclick = () => NA.openSettings();
    };
    bd.addEventListener('click', e => {
      const y = e.target.closest && e.target.closest('a[data-y]'), n = e.target.closest && e.target.closest('a[data-n]');
      if (y) { e.preventDefault(); ask = ''; NA.revokePerm(y.dataset.y); return; }
      if (n) { e.preventDefault(); ask = ''; paint(); return; }
      const b = e.target.closest && e.target.closest('.simvm-sw'); if (!b || !NA || busy) return;
      const p = list.find(x => x.id === b.dataset.id); if (!p) return;
      if (!p.granted) NA.requestPerm(p.id);
      else if (p.special) { toast('폰 설정에서 끌 수 있어요'); NA.requestPerm(p.id); }
      else { ask = p.id; paint(); }
    });
    window.__simAppPerm = (id, ok, perma) => { load(); if (perma) { toast('"다시 묻지 않음" 상태예요. 폰 설정에서 켜 주세요'); setTimeout(() => NA && NA.openSettings(), 900); } else if (!ok) toast('허용하지 않았어요'); };
    const back = () => { if (document.visibilityState === 'visible' && cur) load(); };
    document.addEventListener('visibilitychange', back); window.addEventListener('focus', back);
    await load();
  }

  /* ── ⋯ 버튼과 작은 메뉴 ── */
  let pop = null;
  const closePop = () => { if (pop) { pop.remove(); pop = null; } };
  function openPop(btn) {
    closePop(); css();
    const r = btn.getBoundingClientRect();
    pop = document.createElement('div'); pop.className = 'simvm-pop'; pop.setAttribute('role', 'menu');
    pop.style.top = Math.round(r.bottom + 6) + 'px'; pop.style.right = Math.max(8, Math.round(innerWidth - r.right)) + 'px';
    pop.innerHTML = '<button role="menuitem" data-k="ver"><span>ⓘ</span><span>버전 정보</span></button><button role="menuitem" data-k="perm"><span>🔐</span><span>권한 정보</span></button>';
    pop.addEventListener('click', e => { const b = e.target.closest('button[data-k]'); if (!b) return; closePop(); open(b.dataset.k); });
    document.body.appendChild(pop);
  }
  function open(kind) { closePop(); return kind === 'perm' ? openPerm() : openVer(); }
  document.addEventListener('click', e => { if (pop && !pop.contains(e.target) && !(e.target.closest && e.target.closest('.simvm-btn'))) closePop(); }, true);
  document.addEventListener('keydown', e => { if (e.key === 'Escape') { closePop(); closeModal(); } });

  function mountBar(back) {
    const bar = back.closest('header, .bar'); if (!bar || bar.querySelector(':scope > .simvm-btn')) return;
    css();
    const b = document.createElement('button'); b.type = 'button'; b.className = 'simvm-btn'; b.title = '더보기'; b.setAttribute('aria-label', '더보기'); b.setAttribute('aria-haspopup', 'menu');
    b.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="5" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="12" cy="19" r="1.9"/></svg>';
    b.onclick = e => { e.stopPropagation(); pop ? closePop() : openPop(b); };
    bar.appendChild(b);
    // 좁은 화면에서 상단 바가 넘치면 간격·제목 글자를 조금 줄인다(보일 때만 측정할 수 있다)
    if (bar.clientWidth && bar.scrollWidth > bar.clientWidth + 1) {
      bar.style.gap = '4px';
      const h = bar.querySelector('h1');
      if (bar.scrollWidth > bar.clientWidth + 1 && h) h.style.fontSize = '15px';
    }
  }
  function mount() {   // 런처(index.html)는 상단 바에 '‹ 뒤로'가 없고 자체 ⋯ 메뉴에서 연다. 폴더처럼 상단 바를 나중에 그리는 화면도 따라간다.
    document.querySelectorAll('header a.back[href="index.html"], .bar a.back[href="index.html"]').forEach(mountBar);
  }
  let mo = 0;
  const watch = () => { if (mo) return; mo = requestAnimationFrame(() => { mo = 0; mount(); }); };
  window.simAppMenu = { open, mount };
  const start = () => { mount(); try { new MutationObserver(watch).observe(document.body, { childList: true, subtree: true }); } catch (e) {} };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
