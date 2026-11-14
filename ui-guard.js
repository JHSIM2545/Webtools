/* SIM Tools 공통 UI 보호 (모든 화면에서 불러옴)
   1) 제목·표 머리글에는 글자 선택/텍스트 커서가 들어가지 않게 한다.
   2) 팝업(모달·시트·전체 오버레이)이 뜨면 포커스를 팝업에 가두고 뒤 화면은 스크롤·클릭·탭 이동이 안 되게 잠근다. */
(() => {
  try {
    const st = document.createElement('style');
    st.textContent =
      ':is(h1,h2,h3,h4,h5,h6,th,thead,caption,legend,header,.bar,.tabs,[class*="title"],[class*="ttl"]):not(input):not(textarea):not([contenteditable="true"]){-webkit-user-select:none;user-select:none;cursor:default}' +
      '.modal,.ov,.sheet,#ov,#viewer,#imgov,#busy,#live,#ring,dialog,[role="dialog"]{overscroll-behavior:contain}' +
      /* 전체 창·팝업의 스크롤바를 굵게 직접 그려 항상 보이게(스크롤할 내용이 있을 때). 터치 화면의 기본 스크롤바는 숨겨져 스크롤 가능한지 알 수 없었다 */
      '::-webkit-scrollbar{width:14px;height:14px}' +
      '::-webkit-scrollbar-track{background:rgba(127,127,127,.18)}' +
      '::-webkit-scrollbar-thumb{background:rgba(165,175,195,.7);border-radius:10px;border:3px solid transparent;background-clip:content-box;min-height:44px}' +
      '::-webkit-scrollbar-corner{background:transparent}' +
      '[data-simdrag]{cursor:move}';
    document.head.appendChild(st);
  } catch (e) {}

  const SEL = '.modal,.ov,.sheet,#ov,#viewer,#imgov,#busy,#live,#ring,dialog,[role="dialog"],[aria-modal="true"]';
  const SKIP = /^(SCRIPT|STYLE|LINK|META)$/;
  let inerted = [], saved = null, locked = false, prevHtml = '', prevBody = '';

  const vis = el => { const cs = getComputedStyle(el); return cs.display !== 'none' && cs.visibility !== 'hidden' && el.getClientRects().length > 0; };
  const z = el => parseInt(getComputedStyle(el).zIndex, 10) || 0;
  const shown = () => [...document.querySelectorAll(SEL)].filter(vis);
  const isBusy = el => el.id === 'busy';
  const focusables = root => [...root.querySelectorAll('a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])')].filter(e => !e.disabled && e.getClientRects().length);

  function release() {
    inerted.forEach(e => e.removeAttribute('inert')); inerted = [];
    if (locked) {
      document.documentElement.style.overflow = prevHtml; document.body.style.overflow = prevBody; locked = false;
      if (saved && document.contains(saved)) { try { saved.focus({ preventScroll: true }); } catch (e) {} }
      saved = null;
    }
  }
  function lock(top) {
    // 뒤쪽 화면: 팝업 가지(branch) 밖의 모든 형제를 inert 로 → 클릭·탭 이동·스크롤 불가
    inerted.forEach(e => e.removeAttribute('inert')); inerted = [];
    for (let n = top; n && n !== document.body; n = n.parentElement) {
      const p = n.parentElement; if (!p) break;
      for (const s of p.children) {
        if (s === n || SKIP.test(s.tagName) || s.hasAttribute('inert') || s.id === 'toast' || s.classList.contains('toast')) continue;
        s.setAttribute('inert', ''); inerted.push(s);
      }
    }
    if (!locked) {
      locked = true; saved = document.activeElement;
      prevHtml = document.documentElement.style.overflow; prevBody = document.body.style.overflow;
      document.documentElement.style.overflow = 'hidden'; document.body.style.overflow = 'hidden';
    }
    if (!isBusy(top) && !top.contains(document.activeElement)) {
      const t = top.querySelector('[autofocus]') || top.firstElementChild || top;
      if (!t.hasAttribute('tabindex')) t.setAttribute('tabindex', '-1');
      try { t.focus({ preventScroll: true }); } catch (e) {}
    }
  }
  let raf = 0;
  const update = () => {
    raf = 0;
    const list = shown().sort((a, b) => z(b) - z(a));
    if (list.length) lock(list[0]); else release();
  };
  const sched = () => { if (!raf) raf = requestAnimationFrame(update); };
  const relevant = n => n.nodeType === 1 && (n.matches(SEL) || !!n.querySelector(SEL));
  const start = () => {
    new MutationObserver(ms => { for (const m of ms) { if (relevant(m.target) || [...m.addedNodes].some(relevant) || [...m.removedNodes].some(n => n.nodeType === 1 && n.matches && n.matches(SEL))) { sched(); return; } } })
      .observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style', 'hidden', 'open'], subtree: true, childList: true });
    document.addEventListener('visibilitychange', sched); window.addEventListener('pageshow', sched);
    sched();
  };
  if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);

  // Tab 키가 팝업 밖으로 나가지 않게
  document.addEventListener('keydown', e => {
    if (e.key !== 'Tab' || !locked) return;
    const top = shown().sort((a, b) => z(b) - z(a))[0]; if (!top || isBusy(top)) return;
    const f = focusables(top); if (!f.length) { e.preventDefault(); return; }
    const first = f[0], last = f[f.length - 1], a = document.activeElement;
    if (!top.contains(a)) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && a === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && a === last) { e.preventDefault(); first.focus(); }
  }, true);

  // 터치로 팝업 밖(또는 스크롤할 게 없는 곳)을 밀어도 뒤 화면이 따라 움직이지 않게
  document.addEventListener('touchmove', e => {
    if (!locked) return;
    const top = shown().sort((a, b) => z(b) - z(a))[0]; if (!top) return;
    if (/^(INPUT|TEXTAREA)$/.test(e.target.tagName) && e.target.type === 'range') return;
    for (let n = e.target; n && n !== document.documentElement; n = n.parentElement) {
      if (n.nodeType !== 1) continue;
      const cs = getComputedStyle(n);
      if (/(auto|scroll)/.test(cs.overflowY) && n.scrollHeight > n.clientHeight + 1) { if (top.contains(n) || n === top) return; }
      if (n === top) break;
    }
    if (e.cancelable) e.preventDefault();
  }, { passive: false });
  /* ── 팝업 드래그 이동 ──
     팝업 윗부분(제목 줄, 위 56px)을 잡아 끌면 팝업이 움직인다(마우스·터치). 가려지거나 잘려 보일 때 원하는 곳으로 옮겨 쓴다.
     입력칸·버튼·링크는 그대로 동작하고, 팝업이 화면 밖으로 완전히 나가지는 않는다. */
  const HANDLE = 56, NOHANDLE = 'button,a,input,select,textarea,label,summary,[contenteditable="true"],[role="button"],[role="switch"],canvas,video,svg';
  const boxOf = top => {
    const tr = top.getBoundingClientRect();
    const kid = top.querySelector(':scope > .box, :scope > .card, :scope > .simvm-card') || top.firstElementChild;
    if (kid && kid.getBoundingClientRect().width > 0 && (kid.getBoundingClientRect().width < tr.width - 2 || kid.getBoundingClientRect().height < tr.height - 2)) return kid;
    return (tr.width < innerWidth * 0.95 && tr.height < innerHeight * 0.95) ? top : null;
  };
  let drag = null;
  const pt = e => (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e;
  function dragStart(e) {
    if (drag || (e.type === 'mousedown' && e.button !== 0)) return;
    const top = shown().sort((a, b) => z(b) - z(a))[0]; if (!top || isBusy(top) || !top.contains(e.target)) return;
    const box = boxOf(top); if (!box || !box.contains(e.target)) return;
    const p = pt(e), r = box.getBoundingClientRect();
    if (p.clientY - r.top > HANDLE || (e.target.closest && e.target.closest(NOHANDLE))) return;
    const m = /translate\(\s*(-?[\d.]+)px,\s*(-?[\d.]+)px\)/.exec(box.style.transform || '');
    drag = { box, x0: p.clientX, y0: p.clientY, ox: m ? +m[1] : 0, oy: m ? +m[2] : 0, r };
    box.setAttribute('data-simdrag', '');
    if (e.cancelable && e.type === 'touchstart') e.preventDefault();   // 제목 줄을 잡은 터치는 스크롤이 아니라 이동
  }
  function dragMove(e) {
    if (!drag) return;
    const p = pt(e), r = drag.r;
    let dx = p.clientX - drag.x0, dy = p.clientY - drag.y0;
    const vw = innerWidth, vh = (window.visualViewport && window.visualViewport.height) || innerHeight, keep = 48;
    dx = Math.min(Math.max(dx, keep - r.right), vw - keep - r.left);     // 가로: 팝업이 최소 48px 은 화면에 남게
    dy = Math.min(Math.max(dy, -r.top), vh - keep - r.top);              // 세로: 윗줄은 화면 위로 못 나가고, 아래로는 윗줄이 보이게
    drag.box.style.transform = 'translate(' + Math.round(drag.ox + dx) + 'px,' + Math.round(drag.oy + dy) + 'px)';
    if (e.cancelable) e.preventDefault();
  }
  function dragEnd() { drag = null; }
  document.addEventListener('mousedown', dragStart, true);
  document.addEventListener('touchstart', dragStart, { capture: true, passive: false });
  document.addEventListener('mousemove', dragMove, true);
  document.addEventListener('touchmove', dragMove, { capture: true, passive: false });
  ['mouseup', 'touchend', 'touchcancel', 'blur'].forEach(t => window.addEventListener(t, dragEnd, true));
})();
