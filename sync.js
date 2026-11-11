/* SIM Tools 기기 간 동기화 (런처 전용)
   같은 주소(origin)의 모든 도구 데이터 — IndexedDB(simmemo·simphone·simaitel·simcalendar·simassistant)와 설정(localStorage)을 모바일↔PC로 맞춘다.
   한쪽이 QR을 보여주고 다른 쪽이 찍으면, QR 안의 일회용 키로 데이터를 압축·암호화해 임시 우편함(assistant-sync, 10분 뒤 삭제)으로 주고받는다. 서버는 키를 몰라 내용을 읽을 수 없다.
   합치기: 마지막 동기화 이후의 변화를 기준(base)으로 3-way 병합 — 한쪽만 지운 항목은 지우고, 양쪽이 고친 항목은 더 최근 수정본을 남기며, 지운 쪽과 고친 쪽이 갈리면 고친 쪽을 남긴다. */
(() => {
  const URL_ = 'https://gpqlximhoqdybcewdfde.supabase.co/functions/v1/assistant-sync';
  const DBS = ['simmemo', 'simphone', 'simaitel', 'simcalendar', 'simassistant'];
  const LS_JSON = ['onlineTv.v1'], LS_FILL = ['SIMAS_AI_PHONE', 'S1_KAKAO_MAP_KEY'], LS_KB = 'simas_memory', BASE_KEY = 'SIM_SYNC_BASE', CHUNK = 380000;
  const $ = id => document.getElementById(id);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const lget = k => { try { return localStorage.getItem(k); } catch (e) { return null; } };
  const lset = (k, v) => { try { localStorage.setItem(k, v); } catch (e) {} };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const hash = v => { const s = v == null ? '' : typeof v === 'string' ? v : JSON.stringify(v); let a = 0x811c9dc5, b = 5381; for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); a = Math.imul(a ^ c, 16777619) >>> 0; b = (Math.imul(b, 33) + c) >>> 0; } return a.toString(16) + b.toString(16) + s.length.toString(16); };
  const TSF = ['updated', 'updated_at', 'updatedAt', 'modified', 'at', 'created', 'created_at'];
  const ts = r => { for (const f of TSF) { const v = r && r[f]; if (v == null) continue; const n = typeof v === 'number' ? v : Date.parse(v); if (n > 0) return n; } return 0; };

  /* ── 암호·압축·전송 ── */
  const b64 = u => { let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); };
  const unb64 = t => { const s = atob(String(t).replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - String(t).length % 4) % 4)), u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; };
  const aes = raw => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  const pipe = async (u, S) => { const s = new S('gzip'), w = s.writable.getWriter(); w.write(u); w.close(); return new Uint8Array(await new Response(s.readable).arrayBuffer()); };
  async function pack(raw, obj) {
    let u = new TextEncoder().encode(JSON.stringify(obj)), flag = 0;
    if (typeof CompressionStream !== 'undefined') { try { u = await pipe(u, CompressionStream); flag = 1; } catch (e) {} }
    const p = new Uint8Array(u.length + 1); p[0] = flag; p.set(u, 1);
    const iv = crypto.getRandomValues(new Uint8Array(12)), ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aes(raw), p)), o = new Uint8Array(12 + ct.length); o.set(iv); o.set(ct, 12);
    return b64(o);
  }
  async function unpack(raw, s) {
    const u = unb64(s), pt = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: u.slice(0, 12) }, await aes(raw), u.slice(12)));
    let body = pt.slice(1); if (pt[0] === 1) body = await pipe(body, DecompressionStream);
    return JSON.parse(new TextDecoder().decode(body));
  }
  async function call(b) {
    const ctl = new AbortController(), to = setTimeout(() => ctl.abort(), 30000);
    try { const r = await fetch(URL_, { method: 'POST', signal: ctl.signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) }); const j = await r.json().catch(() => ({})); if (!r.ok) throw new Error(j.error || 'http ' + r.status); return j; }
    catch (e) { throw e && e.name === 'AbortError' ? new Error('응답이 늦어요') : e; } finally { clearTimeout(to); }
  }
  async function upload(sid, who, raw, obj, onP) {
    const s = await pack(raw, obj), n = Math.max(1, Math.ceil(s.length / CHUNK));
    if (n > 40) throw new Error('데이터가 너무 커요 (노트 이미지가 많아요)');
    for (let i = 0; i < n; i++) { await call({ op: 'put', sid, who, seq: i, total: n, blob: s.slice(i * CHUNK, (i + 1) * CHUNK) }); if (onP) onP(i + 1, n); }
  }
  async function download(sid, who, raw, onP) {   // 모든 조각이 올라오기 전이면 null
    const j = await call({ op: 'get', sid, who, seq: 0 }); if (!j.blob) return null;
    let s = j.blob; for (let i = 1; i < j.total; i++) { const k = await call({ op: 'get', sid, who, seq: i }); if (!k.blob) return null; s += k.blob; if (onP) onP(i + 1, j.total); }
    return unpack(raw, s);
  }

  /* ── 저장소 읽기·쓰기 ── */
  const openRO = name => new Promise(res => { let r; try { r = indexedDB.open(name); } catch (e) { return res(null); } r.onupgradeneeded = () => { try { r.transaction.abort(); } catch (e) {} }; r.onerror = () => res(null); r.onblocked = () => res(null); r.onsuccess = () => res(r.result); });
  async function collect() {
    const snap = { idb: {}, ls: {} };
    for (const name of DBS) {
      const db = await openRO(name); if (!db) continue;
      const d = {};
      for (const sn of [...db.objectStoreNames]) {
        try {
          const st = db.transaction(sn, 'readonly').objectStore(sn), kp = st.keyPath; if (typeof kp !== 'string') continue;
          const rows = await new Promise((ok, no) => { const q = st.getAll(); q.onsuccess = () => ok(q.result); q.onerror = () => no(q.error); });
          d[sn] = { kp, rows: rows.filter(r => r && r[kp] != null).map(r => JSON.parse(JSON.stringify(r))) };
        } catch (e) {}
      }
      db.close(); if (Object.keys(d).length) snap.idb[name] = d;
    }
    [...LS_JSON, ...LS_FILL, LS_KB].forEach(k => { const v = lget(k); if (v != null) snap.ls[k] = v; });
    return snap;
  }
  const hashes = snap => { const m = {}; for (const d in snap.idb) for (const s in snap.idb[d]) { const { kp, rows } = snap.idb[d][s]; rows.forEach(r => { m[d + '/' + s + '/' + r[kp]] = hash(r); }); } for (const k in snap.ls) m['ls/' + k] = hash(snap.ls[k]); return m; };
  const getBase = () => { try { return JSON.parse(lget(BASE_KEY) || '{}') || {}; } catch (e) { return {}; } };
  const openRW = (name, spec) => new Promise(res => {   // 없으면 버전 1로 만든다(다른 도구도 버전 1로 연다). 이미 있는 DB의 버전은 올리지 않는다.
    let r; try { r = indexedDB.open(name, 1); } catch (e) { return res(null); }
    r.onupgradeneeded = () => { for (const s in spec) if (!r.result.objectStoreNames.contains(s)) r.result.createObjectStore(s, { keyPath: spec[s].kp }); };
    r.onsuccess = () => { r.result.onversionchange = () => r.result.close(); res(r.result); }; r.onerror = () => res(null); r.onblocked = () => res(null);
  });
  async function apply(F, local) {   // F: 최종 상태. local: 적용 전 이 기기 상태. 바뀐 건수를 돌려준다
    const st = { put: 0, del: 0 };
    for (const name in F.idb) {
      const db = await openRW(name, F.idb[name]); if (!db) continue;
      for (const sn in F.idb[name]) {
        if (!db.objectStoreNames.contains(sn)) continue;
        const { kp, rows } = F.idb[name][sn], cur = new Map(((local.idb[name] || {})[sn] || { rows: [] }).rows.map(r => [r[kp], hash(r)])), keep = new Set(rows.map(r => r[kp]));
        await new Promise(ok => {
          const tx = db.transaction(sn, 'readwrite'), os = tx.objectStore(sn);
          rows.forEach(r => { if (cur.get(r[kp]) !== hash(r)) { os.put(r); st.put++; } });
          [...cur.keys()].forEach(k => { if (!keep.has(k)) { os.delete(k); st.del++; } });
          tx.oncomplete = ok; tx.onerror = ok; tx.onabort = ok;
        });
      }
      db.close();
    }
    for (const k in F.ls) if (F.ls[k] !== lget(k)) { lset(k, F.ls[k]); st.put++; }
    return st;
  }

  /* ── 병합 (3-way) ── */
  const GROUPS = [['집', '우리집', '본가', '내집'], ['사무실', '회사', '직장', '일터'], ['어머니댁', '어머님댁', '엄마집', '어머니집', '엄마네', '친정', '친정집'], ['아버지댁', '아버님댁', '아빠집', '아버지집'], ['부모님댁', '부모님집'], ['처갓집', '장인댁', '시댁']];
  const canon = s => { const k = String(s || '').replace(/\s+/g, '').replace(/^(우리|내)(?=.)/, '').replace(/(의)?(주소|위치)$/, '').replace(/(으로|에서|은|는|이|가|을|를)$/, ''); for (const g of GROUPS) if (g.includes(k) || g.includes('우리' + k)) return g[0]; return k; };
  function mergeKB(ls, ps) {   // 어시스턴트 기억 공간: 항목별 최신 + 삭제 표시
    let l = {}, p = {}; try { l = JSON.parse(ls || '{}') || {}; } catch (e) {} try { p = JSON.parse(ps || '{}') || {}; } catch (e) {}
    const cut = new Date(Date.now() - 90 * 864e5).toISOString(), del = {};
    for (const src of [l.del || {}, p.del || {}]) for (const k in src) if (String(src[k]) > cut && (!del[k] || src[k] > del[k])) del[k] = src[k];
    const out = { del, counts: {} };
    for (const t of ['places', 'facts']) {
      const byId = new Map(); for (const it of [...(l[t] || []), ...(p[t] || [])]) { if (!it || !it.id) continue; const o = byId.get(it.id); if (!o || String(it.at) > String(o.at)) byId.set(it.id, it); }
      const byKey = new Map();
      for (const it of byId.values()) { if (del[it.id] && del[it.id] > String(it.at)) continue; const k = (t === 'places' ? canon(it.name) : (it.key ? canon(it.key) : '')) || 'id:' + it.id, o = byKey.get(k); if (!o || String(it.at) > String(o.at) || (it.at === o.at && it.id > o.id)) byKey.set(k, it); }
      out[t] = [...byKey.values()].sort((a, b) => String(a.at).localeCompare(String(b.at)) || String(a.id).localeCompare(String(b.id)));
    }
    for (const src of [l.counts || {}, p.counts || {}]) for (const k in src) { const c = src[k] || {}, o = out.counts[k] || { n: 0, no: 0 }; out.counts[k] = { n: Math.max(o.n || 0, c.n || 0), no: Math.max(o.no || 0, c.no || 0) }; }
    return JSON.stringify(out);
  }
  function pick(l, p, bl, bp) {   // 한 항목의 3-way 결정. undefined = 삭제
    const hl = l === undefined ? null : hash(l), hp = p === undefined ? null : hash(p);
    if (l !== undefined && p !== undefined) {
      if (hl === hp) return l;
      const tl = ts(l), tp = ts(p); if (tl && tp && tl !== tp) return tl > tp ? l : p;
      const cl = hl !== bl, cp = hp !== bp; return cp && !cl ? p : l;
    }
    if (l !== undefined) return bp !== undefined && hl === bl ? undefined : l;   // 상대가 지움(내가 안 고쳤으면 삭제)
    if (p !== undefined) return bl !== undefined && hp === bp ? undefined : p;   // 내가 지움
    return undefined;
  }
  function merge(L, P, bl, bp) {
    const F = { idb: {}, ls: {} };
    const names = new Set([...Object.keys(L.idb), ...Object.keys(P.idb)]);
    for (const d of names) for (const s of new Set([...Object.keys(L.idb[d] || {}), ...Object.keys(P.idb[d] || {})])) {
      const ls_ = (L.idb[d] || {})[s], ps_ = (P.idb[d] || {})[s], kp = (ls_ || ps_).kp;
      const lm = new Map(((ls_ && ls_.rows) || []).map(r => [String(r[kp]), r])), pm = new Map(((ps_ && ps_.rows) || []).map(r => [String(r[kp]), r])), rows = [];
      for (const k of new Set([...lm.keys(), ...pm.keys()])) { const fk = d + '/' + s + '/' + k, r = pick(lm.get(k), pm.get(k), bl[fk], bp[fk]); if (r !== undefined) rows.push(r); }
      (F.idb[d] = F.idb[d] || {})[s] = { kp, rows };
    }
    for (const k of new Set([...Object.keys(L.ls), ...Object.keys(P.ls)])) {
      const l = L.ls[k], p = P.ls[k];
      if (k === LS_KB) F.ls[k] = l != null && p != null ? mergeKB(l, p) : (l != null ? l : p);
      else if (LS_FILL.includes(k)) F.ls[k] = l || p;
      else { const r = pick(l, p, bl['ls/' + k], bp['ls/' + k]); if (r !== undefined) F.ls[k] = r; }
    }
    return F;
  }

  /* ── 화면 ── */
  let ov = null, abort = null;
  const close = () => { if (abort) { abort(); abort = null; } if (ov) { ov.remove(); ov = null; } };
  function modal(title, html) {
    if (abort) { abort(); abort = null; }
    if (!ov) { ov = document.createElement('div'); ov.style.cssText = 'position:fixed;inset:0;z-index:99;background:#000a;display:flex;align-items:center;justify-content:center;padding:16px'; document.body.appendChild(ov); }
    ov.innerHTML = '<div style="background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:18px;max-width:400px;width:100%;max-height:92vh;overflow:auto"><b style="font-size:16px">' + esc(title) + '</b><div id="syBody" style="margin-top:10px;font-size:14px;line-height:1.6">' + html + '</div></div>';
    return ov.querySelector('#syBody');
  }
  const sub = 'color:var(--sub);font-size:12.5px';
  const okBtn = 'margin-top:12px;text-align:right';
  function done(st, back) {
    const b = modal('동기화 완료', '<p>' + (st.put || st.del ? '받은 항목 ' + st.put + '건 반영' + (st.del ? ', 삭제 ' + st.del + '건' : '') + '했어요.' : '두 기기의 데이터가 이미 같아요.') + '</p><p style="' + sub + ';margin-top:6px">메모·연락처·일정·알람·AI-Tel·어시스턴트 기억·TV 설정이 맞춰졌어요. 다른 도구가 열려 있으면 새로고침해 주세요.</p><div style="' + okBtn + '"><button id="syOk">확인</button></div>');
    b.querySelector('#syOk').onclick = close;
  }
  function fail(msg) { const b = modal('동기화 실패', '<p>' + esc(msg) + '</p><div style="' + okBtn + '"><button id="syOk">닫기</button></div>'); b.querySelector('#syOk').onclick = close; }
  const loadQR = () => window.qrcode ? Promise.resolve() : new Promise((ok, no) => { const s = document.createElement('script'); s.src = 'qr.min.js'; s.onload = ok; s.onerror = () => no(new Error('QR 모듈을 불러오지 못했어요')); document.head.appendChild(s); });
  const parse = s => { const m = String(s || '').match(/([0-9a-f]{32})\.([A-Za-z0-9_-]{43})/); return m ? { sid: m[1], key: unb64(m[2]) } : null; };
  const prog = t => { const e = document.getElementById('syMsg'); if (e) e.textContent = t; };

  async function show() {   // 이 기기가 QR을 보여주고 상대가 찍기를 기다린다
    try {
      await loadQR();
      const a = crypto.getRandomValues(new Uint8Array(16)), key = crypto.getRandomValues(new Uint8Array(32)), sid = [...a].map(x => x.toString(16).padStart(2, '0')).join('');
      modal('동기화 준비 중…', '<p id="syMsg">이 기기의 데이터를 모으는 중…</p>');
      const local = await collect(), mine = { v: 2, idb: local.idb, ls: local.ls, base: getBase() };
      await upload(sid, 'a', key, mine, (i, n) => prog('올리는 중 ' + i + '/' + n));
      const url = location.origin + location.pathname.replace(/[^/]*$/, 'index.html') + '#sync=' + sid + '.' + b64(key), q = qrcode(0, 'M'); q.addData(url); q.make();
      const b = modal('다른 기기로 QR 찍기', '<p>동기화할 다른 기기의 <b>카메라</b>(또는 런처의 QR 스캔)로 아래 QR을 찍어 주세요. 10분 안에 찍어야 해요.</p><div style="background:#fff;padding:10px;border-radius:10px;margin:10px auto;width:max-content;max-width:100%">' + q.createSvgTag({ cellSize: 5, margin: 0, scalable: true }) + '</div><p id="syMsg" style="' + sub + ';text-align:center">상대 기기를 기다리는 중…</p><div style="margin-top:10px;text-align:right"><button id="syX">취소</button></div>');
      const svg = b.querySelector('svg'); if (svg) { svg.style.width = '240px'; svg.style.height = '240px'; }
      b.querySelector('#syX').onclick = () => { call({ op: 'done', sid }).catch(() => {}); close(); };
      let stop = false; abort = () => { stop = true; call({ op: 'done', sid }).catch(() => {}); };
      const end = Date.now() + 10 * 60e3;
      while (!stop && Date.now() < end) {
        await sleep(2000); if (stop) return;
        let F; try { F = await download(sid, 'b', key, (i, n) => prog('받는 중 ' + i + '/' + n)); } catch (e) { if (e.message === 'gone') break; if (/Operation|decrypt/i.test(e.message || '') || e.name === 'OperationError') return fail('데이터를 풀지 못했어요. 처음부터 다시 해 주세요.'); continue; }
        if (F) { abort = null; prog('적용하는 중…'); const st = await apply(F, local); lset(BASE_KEY, JSON.stringify(hashes(F))); call({ op: 'done', sid }).catch(() => {}); return done(st); }
      }
      if (!stop) fail('시간이 지나 연결이 끊겼어요. 다시 시도해 주세요.');
    } catch (e) { fail('동기화를 시작하지 못했어요 (' + (e.message || e) + ')'); }
  }
  async function join(code) {   // QR을 찍은 기기: 상대 데이터를 받아 합친 뒤 결과를 돌려보낸다
    const c = parse(code); if (!c) return fail('QR 코드를 읽지 못했어요. 다시 찍어 주세요.');
    modal('동기화 중…', '<p id="syMsg">상대 기기의 데이터를 받는 중…</p>');
    try {
      let P; try { P = await download(c.sid, 'a', c.key, (i, n) => prog('받는 중 ' + i + '/' + n)); } catch (e) { if (e.message === 'gone') throw e; throw new Error('QR 키가 맞지 않거나 데이터가 손상됐어요'); }
      if (!P || P.v !== 2) throw new Error('gone');
      prog('합치는 중…'); const L = await collect(), F = merge(L, P, getBase(), P.base || {});
      const st = await apply(F, L); lset(BASE_KEY, JSON.stringify(hashes(F)));
      await upload(c.sid, 'b', c.key, { v: 2, idb: F.idb, ls: F.ls }, (i, n) => prog('돌려보내는 중 ' + i + '/' + n));
      done(st);
    } catch (e) { fail(e.message === 'gone' ? 'QR 유효시간(10분)이 지났거나 이미 사용됐어요. 상대 기기에서 QR을 다시 만들어 주세요.' : '동기화하지 못했어요 (' + (e.message || e) + ')'); }
  }
  async function scan() {
    if (!('BarcodeDetector' in window) || !navigator.mediaDevices) return fail('이 브라우저는 앱 안 스캔을 지원하지 않아요. 기기의 기본 카메라 앱으로 QR을 찍으면 이 앱이 열려요.');
    let st = null, stop = false;
    try {
      st = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
      const b = modal('QR 스캔', '<video id="syV" playsinline muted style="width:100%;border-radius:10px;background:#000"></video><p style="' + sub + ';margin-top:6px">상대 기기 화면의 QR을 비춰 주세요.</p><div style="margin-top:10px;text-align:right"><button id="syX">취소</button></div>');
      const v = b.querySelector('#syV'); v.srcObject = st; await v.play();
      const stopAll = () => { stop = true; st.getTracks().forEach(t => t.stop()); }; abort = stopAll; b.querySelector('#syX').onclick = close;
      const det = new BarcodeDetector({ formats: ['qr_code'] });
      while (!stop) { await sleep(300); if (stop) return; let r = []; try { r = await det.detect(v); } catch (e) {} const hit = r.find(x => parse(x.rawValue)); if (hit) { stopAll(); abort = null; return join(hit.rawValue); } }
    } catch (e) { if (st) st.getTracks().forEach(t => t.stop()); fail('카메라를 사용할 수 없어요 (' + (e.name === 'NotAllowedError' ? '권한 거부' : e.message || e) + ')'); }
  }
  function menu() {
    const b = modal('기기 동기화', '<p>모바일과 PC의 <b>모든 데이터</b>(메모·연락처·일정·알람·AI-Tel·어시스턴트 기억·TV 설정)를 서로 맞춰요. 한쪽에서 QR을 보여주고 다른 쪽에서 찍으면 같은 사용자의 기기로 확인돼요. 내용은 QR 안의 키로 암호화돼서 서버도 읽을 수 없어요.</p><div style="display:flex;flex-direction:column;gap:8px;margin-top:12px"><button id="syA" style="background:var(--accent2);border-color:var(--accent2);font-weight:700">QR 보여주기 (이 기기에서 시작)</button><button id="syB">QR 스캔하기 (다른 기기 QR 찍기)</button></div><div style="margin-top:12px"><input id="syC" placeholder="또는 QR 주소/코드를 붙여넣기" style="width:100%;font:inherit;font-size:13px;color:var(--text);background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:9px 10px"><button id="syD" style="margin-top:6px;width:100%">붙여넣은 코드로 연결</button></div><p style="' + sub + ';margin-top:10px">동기화 전에 다른 도구 창은 닫아 주세요. 열려 있으면 나중에 예전 내용으로 덮어쓸 수 있어요.</p><div style="margin-top:6px;text-align:right"><button id="syX">닫기</button></div>');
    b.querySelector('#syA').onclick = show; b.querySelector('#syB').onclick = scan; b.querySelector('#syX').onclick = close; b.querySelector('#syD').onclick = () => join(b.querySelector('#syC').value);
  }
  function boot() {
    const hd = document.querySelector('header'), ins = $('btnInstall');
    if (hd && !$('btnAcct')) { const a = document.createElement('button'); a.id = 'btnAcct'; a.title = '계정 로그인·자동 백업'; a.textContent = '👤 로그인'; a.onclick = acctMenu; hd.insertBefore(a, ins || null); cloudBoot(); }
    if (hd && !$('btnMore')) {   // ⋯ 더보기 메뉴 (기기 동기화)
      const w = document.createElement('div'); w.style.cssText = 'position:relative;flex:none';
      const mb = document.createElement('button'); mb.id = 'btnMore'; mb.title = '더보기'; mb.setAttribute('aria-label', '더보기'); mb.textContent = '⋯'; mb.style.cssText = 'font-size:18px;line-height:1;padding:6px 11px';
      const pop = document.createElement('div'); pop.id = 'morePop'; pop.style.cssText = 'display:none;position:absolute;right:0;top:calc(100% + 6px);min-width:170px;background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:6px;box-shadow:0 10px 28px rgba(0,0,0,.55);z-index:60';
      const row = 'display:flex;align-items:center;justify-content:space-between;gap:14px;width:100%;text-align:left;border:0;background:transparent;padding:10px 12px;border-radius:8px;white-space:nowrap;font:inherit;color:inherit;cursor:pointer';
      const mk = (id, html, fn) => { const x = document.createElement('button'); x.id = id; x.style.cssText = row; x.innerHTML = html; x.onclick = fn; pop.appendChild(x); return x; };
      const L = () => window.simLauncher || {};
      mk('btnAiSet', '<span>🤖 AI 연결 설정</span>', () => { pop.style.display = 'none'; if (L().openAi) L().openAi(); });
      mk('btnOrderReset', '<span>↺ 카드 순서 초기화</span>', () => { pop.style.display = 'none'; if (L().resetOrder) L().resetOrder(); });
      const sw = on => '<span style="position:relative;display:inline-block;width:38px;height:22px;border-radius:99px;background:' + (on ? 'var(--ok)' : '#3a4056') + ';transition:.15s;flex:none"><span style="position:absolute;top:2px;left:' + (on ? '18px' : '2px') + ';width:18px;height:18px;border-radius:50%;background:#fff;transition:.15s"></span></span>';
      const mv = mk('btnMoveSw', '', null); mv.setAttribute('role', 'switch');
      const paintMv = () => { const on = !!(L().moveOn && L().moveOn()); mv.setAttribute('aria-checked', on ? 'true' : 'false'); mv.innerHTML = '<span>↕ 카드 이동</span>' + sw(on); };
      mv.onclick = e => { e.stopPropagation(); if (L().setMove) L().setMove(!(L().moveOn && L().moveOn())); paintMv(); };
      mb.addEventListener('click', paintMv);
      const hr = document.createElement('div'); hr.style.cssText = 'height:1px;background:var(--line);margin:4px 6px'; pop.appendChild(hr);
      const it = mk('btnSync', '<span>🔄 기기 동기화</span>', () => { pop.style.display = 'none'; menu(); });
      paintMv();
      mb.onclick = e => { e.stopPropagation(); pop.style.display = pop.style.display === 'block' ? 'none' : 'block'; };
      document.addEventListener('click', e => { if (!w.contains(e.target)) pop.style.display = 'none'; });
      hd.appendChild(w);
    }
    const m = /#sync=([0-9a-f]{32}\.[A-Za-z0-9_-]{43})/.exec(location.hash || '');
    if (m) { history.replaceState(null, '', location.pathname + location.search); join(m[1]); }
    else if (/[?&]sync=1/.test(location.search)) { history.replaceState(null, '', location.pathname); menu(); }
  }
  /* ══ 계정 로그인 + 서버 자동 백업/복원 (Supabase Auth: 휴대폰 OTP · 구글) ══
     데이터는 계정별 비공개 저장소(sim-sync/<user_id>/snapshot.gz, RLS로 본인만 접근)에 압축해 보관한다.
     앱을 지우고 다시 설치해도 같은 계정으로 로그인하면 3-way 병합으로 되살아난다. */
  const C_URL = 'https://rgamgjdnhunzzwzsbnbs.supabase.co', C_KEY = 'sb_publishable_hnBcVEuMXFRdFRqJhOM0OQ_rp72JywU', C_BUCKET = 'sim-sync', C_BASE = 'SIM_CLOUD_BASE', C_LAST = 'SIM_CLOUD_LAST';
  let sb = null, cUser = null, cBusy = false, cTimer = null;
  const loadSB = () => window.supabase ? Promise.resolve() : new Promise((ok, no) => { const s = document.createElement('script'); s.src = 'https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2'; s.onload = ok; s.onerror = () => no(new Error('로그인 모듈을 불러오지 못했어요 (인터넷 확인)')); document.head.appendChild(s); });
  async function client() { if (sb) return sb; await loadSB(); sb = supabase.createClient(C_URL, C_KEY, { auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true } }); return sb; }
  const normPhone = v => { let d = String(v || '').replace(/[^\d+]/g, ''); if (d.startsWith('+')) return d; if (d.startsWith('00')) return '+' + d.slice(2); if (d.startsWith('0')) return '+82' + d.slice(1); return d ? '+82' + d : ''; };
  const who = u => { if (!u) return ''; const m = u.user_metadata || {}; return String(m.display_name || m.full_name || m.name || (u.email ? u.email.split('@')[0] : '') || '로그인됨').trim().slice(0, 12); };
  const gz = async o => { let u = new TextEncoder().encode(JSON.stringify(o)); if (typeof CompressionStream !== 'undefined') u = await pipe(u, CompressionStream); return u; };
  const ungz = async blob => { let u = new Uint8Array(await blob.arrayBuffer()); if (u[0] === 0x1f && u[1] === 0x8b) u = await pipe(u, DecompressionStream); return JSON.parse(new TextDecoder().decode(u)); };
  async function cloudSync(opt) {   // 서버 스냅샷 받기 → 병합 → 이 기기에 반영 → 변화가 있으면 다시 올리기
    if (cBusy || !cUser) return null; cBusy = true;
    try {
      const c = await client(), path = cUser.id + '/snapshot.gz', L = await collect();
      let P = null; const dl = await c.storage.from(C_BUCKET).download(path);
      if (dl.data) P = await ungz(dl.data); else if (dl.error && !/not.?found|404|Object/i.test(dl.error.message || '') && dl.error.statusCode !== '404') throw dl.error;
      let F = L, st = { put: 0, del: 0 };
      if (P && P.v === 2) { F = merge(L, P, getBase_(), getBase_()); st = await apply(F, L); }
      const hF = hashes(F), cloudSame = P && JSON.stringify(hashes({ idb: P.idb || {}, ls: P.ls || {} })) === JSON.stringify(hF);
      if (!cloudSame) { const body = await gz({ v: 2, t: Date.now(), idb: F.idb, ls: F.ls }); const up = await c.storage.from(C_BUCKET).upload(path, new Blob([body], { type: 'application/octet-stream' }), { upsert: true, contentType: 'application/octet-stream' }); if (up.error) throw up.error; }
      lset(C_BASE, JSON.stringify(hF)); lset(C_LAST, String(Date.now()));
      return st;
    } finally { cBusy = false; }
  }
  const getBase_ = () => { try { return JSON.parse(lget(C_BASE) || '{}') || {}; } catch (e) { return {}; } };
  const photo = u => { const m = (u && u.user_metadata) || {}, p = m.avatar_url || m.picture || ''; return /^https:\/\//.test(p) ? p : ''; };
  const avatar = (u, px) => photo(u) ? '<img src="' + esc(photo(u)) + '" alt="" referrerpolicy="no-referrer" style="width:' + px + 'px;height:' + px + 'px;border-radius:50%;object-fit:cover;vertical-align:middle;flex:none" onerror="this.replaceWith(document.createTextNode(\'👤\'))">' : '👤';
  function acctBtn() { const b = $('btnAcct'); if (!b) return; if (cUser) { b.innerHTML = avatar(cUser, 20) + ' <span style="vertical-align:middle">' + esc(who(cUser)) + '</span>'; } else b.textContent = '👤 로그인'; }
  function schedule() { clearInterval(cTimer); if (cUser) cTimer = setInterval(() => { if (!document.hidden) cloudSync().catch(() => {}); }, 5 * 60e3); }
  async function setUser(u, first) {
    cUser = u || null; acctBtn(); schedule();
    if (cUser && first) { try { const st = await cloudSync(); if (st && (st.put || st.del)) { const b = modal('내 데이터 복원 완료', '<p>서버에 저장된 데이터 ' + st.put + '건을 이 기기에 복원했어요.</p><p style="' + sub + ';margin-top:6px">열려 있는 다른 도구는 새로고침해야 보여요.</p><div style="' + okBtn + '"><button id="syOk">확인</button></div>'); b.querySelector('#syOk').onclick = close; } } catch (e) {} }
  }
  function authView(msg) {
    const inp = 'width:100%;padding:10px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--text);font:inherit;margin-top:6px';
    const b = modal('로그인', '<p style="' + sub + '">로그인하면 데이터가 내 계정에 자동 백업돼요. 앱을 지우고 다시 깔아도 같은 계정으로 로그인하면 그대로 돌아와요. (SIM Folder와 같은 계정이에요)</p>' +
      '<label style="' + sub + ';display:block;margin-top:10px">이메일</label><input id="acEm" type="email" inputmode="email" placeholder="name@example.com" autocomplete="email" style="' + inp + '">' +
      '<label style="' + sub + ';display:block;margin-top:10px">비밀번호 (6자 이상)</label><input id="acPw" type="password" autocomplete="current-password" style="' + inp + '">' +
      '<p id="acMsg" style="margin-top:8px;font-size:13px;color:' + (msg ? '#ff8a8a' : 'var(--sub)') + '">' + esc(msg || '') + '</p>' +
      '<div style="margin-top:8px;display:flex;gap:8px"><button id="acIn" class="primary" style="flex:1">로그인</button><button id="acUp" style="flex:1">처음이면 가입</button></div>' +
      '<div style="margin:12px 0 4px;text-align:center;color:var(--sub);font-size:12px">또는</div><button id="acGoogle" style="width:100%">Google로 계속하기</button>' +
      '<div style="' + okBtn + '"><button id="acX">닫기</button></div>');
    const say = (t, bad) => { const e = b.querySelector('#acMsg'); e.textContent = t; e.style.color = bad ? '#ff8a8a' : 'var(--sub)'; };
    b.querySelector('#acX').onclick = close;
    const go = async signup => {
      const email = b.querySelector('#acEm').value.trim().toLowerCase(), password = b.querySelector('#acPw').value;
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || password.length < 6) return say('이메일과 6자 이상의 비밀번호를 입력해 주세요.', 1);
      say(signup ? '가입하는 중…' : '로그인하는 중…');
      try {
        const c = await client(), r = signup ? await c.auth.signUp({ email, password }) : await c.auth.signInWithPassword({ email, password });
        if (r.error) throw r.error;
        if (r.data.session) { close(); await setUser(r.data.session.user, true); }
        else say('가입했어요. 받은 편지함(스팸함 포함)의 확인 메일 링크를 누른 뒤 로그인해 주세요.');
      } catch (e) { const m = (e.message || '').toLowerCase(); say(m.includes('not confirmed') ? '이메일 인증이 필요해요. 확인 메일의 링크를 누른 뒤 로그인해 주세요.' : m.includes('invalid login') ? '이메일 또는 비밀번호가 맞지 않아요. 처음이면 "처음이면 가입"을 눌러 주세요.' : m.includes('already') ? '이미 가입된 이메일이에요. 로그인을 눌러 주세요.' : m.includes('rate') || m.includes('seconds') ? '요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.' : '실패했어요: ' + (e.message || e), 1); }
    };
    b.querySelector('#acIn').onclick = () => go(false); b.querySelector('#acUp').onclick = () => go(true);
    b.querySelector('#acPw').addEventListener('keydown', e => { if (e.key === 'Enter') go(false); });
    b.querySelector('#acGoogle').onclick = async () => { try { const c = await client(), r = await c.auth.signInWithOAuth({ provider: 'google', options: { redirectTo: location.origin + location.pathname.replace(/[^/]*$/, 'index.html') } }); if (r.error) throw r.error; } catch (e) { say('구글 로그인을 시작하지 못했어요: ' + (e.message || e), 1); } };
  }
  function acctMenu() {
    if (!cUser) return authView();
    const last = +lget(C_LAST) || 0;
    const b = modal('내 계정', '<div style="display:flex;align-items:center;gap:12px">' + avatar(cUser, 52) + '<div><b>' + esc(who(cUser)) + '</b><br><span style="' + sub + '">' + esc(cUser.email || '') + '</span></div></div><label style="' + sub + ';display:block;margin-top:8px">표시 이름</label><div style="display:flex;gap:6px;margin-top:4px"><input id="acNm" maxlength="12" value="' + esc(who(cUser)) + '" style="flex:1;padding:8px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--text);font:inherit"><button id="acNmSave">변경</button></div><p id="acMsg" style="' + sub + ';margin-top:6px">' + (last ? '마지막 백업: ' + new Date(last).toLocaleString('ko-KR') : '아직 백업 전이에요.') + '<br>데이터는 이 계정으로 자동 백업·복원돼요.</p>' +
      '<div style="margin-top:12px;display:flex;gap:8px;flex-wrap:wrap"><button id="acNow" class="primary" style="flex:1">지금 동기화</button><button id="acOut">로그아웃</button></div><div style="' + okBtn + '"><button id="acX">닫기</button></div>');
    b.querySelector('#acX').onclick = close;
    b.querySelector('#acNmSave').onclick = async () => { const v = b.querySelector('#acNm').value.trim().slice(0, 12), e = b.querySelector('#acMsg'); if (!v) { e.textContent = '이름을 입력해 주세요.'; return; } try { const c = await client(), r = await c.auth.updateUser({ data: { display_name: v } }); if (r.error) throw r.error; cUser = r.data.user; acctBtn(); e.textContent = '이름을 "' + v + '"(으)로 바꿨어요.'; } catch (x) { e.textContent = '변경하지 못했어요: ' + (x.message || x); } };
    b.querySelector('#acNow').onclick = async () => { const e = b.querySelector('#acMsg'); e.textContent = '동기화 중…'; try { const st = await cloudSync(); e.textContent = '완료 — ' + (st && (st.put || st.del) ? '서버에서 ' + st.put + '건 반영' : '변경 없음/서버에 저장') + '. (' + new Date().toLocaleTimeString('ko-KR') + ')'; } catch (x) { e.textContent = '실패: ' + (x.message || x); } };
    b.querySelector('#acOut').onclick = async () => { try { await cloudSync(); } catch (e) {} try { const c = await client(); await c.auth.signOut(); } catch (e) {} close(); await setUser(null); toast_('로그아웃했어요 (이 기기 데이터는 그대로예요)'); };
  }
  const toast_ = t => { const e = $('toast'); if (e) { e.textContent = t; e.classList.add('show'); setTimeout(() => e.classList.remove('show'), 2200); } };
  async function cloudBoot() {
    try { const c = await client(), { data } = await c.auth.getSession(); if (data.session) await setUser(data.session.user, false), cloudSync().catch(() => {});
      c.auth.onAuthStateChange((ev, s) => { if (ev === 'SIGNED_IN' && s && !cUser) setUser(s.user, true); else if (ev === 'SIGNED_OUT') setUser(null); });
      document.addEventListener('visibilitychange', () => { if (document.hidden && cUser) cloudSync().catch(() => {}); });
      window.addEventListener('pagehide', () => { if (cUser) cloudSync().catch(() => {}); });
    } catch (e) {}
  }
  window.simSync = { menu, collect, merge, hash, pick };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
  window.addEventListener('hashchange', boot);
})();
