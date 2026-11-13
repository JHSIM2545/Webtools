package kr.simtools.simpad;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 폰을 블루투스 HID 장치(마우스+키보드+미디어키 복합 장치)로 등록하고,
 * pad.html 이 보내는 신호(이동·버튼·스크롤·키·글자)를 HID 리포트로 바꿔 연결된 차량(호스트)에 보낸다.
 *
 * 리포트 ID: 1 = 키보드(8바이트), 2 = 마우스(5바이트: 버튼,X,Y,휠,좌우휠), 3 = 미디어(2바이트)
 */
public class HidController {
    interface Listener { void onChange(); }

    private static final int[] DESC = {
        // ── 키보드 (Report ID 1)
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x29, 0xFF, 0x81, 0x00,
        0xC0,
        // ── 마우스 (Report ID 2)
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
        // ── 미디어 키 (Report ID 3)
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03, 0x15, 0x00, 0x26, 0xFF, 0x03,
        0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0
    };

    private final Context ctx;
    private final Listener listener;
    private final ExecutorService cb = Executors.newSingleThreadExecutor();
    private final ExecutorService out = Executors.newSingleThreadExecutor();   // 전송 순서 보장
    private final Executor cbExec = cb;

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private volatile boolean registered = false, supported = true, started = false;

    // 주변 기기 검색 · 페어링
    private final java.util.LinkedHashMap<String, String> found = new java.util.LinkedHashMap<>();   // 주소 → 이름
    private volatile boolean scanning = false;
    private volatile String pairing = null;          // 페어링 요청 중인 주소 (완료되면 자동 연결)
    private boolean rxOn = false;
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            try {
                if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    String n = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    if (n == null) n = safeName(d);
                    synchronized (found) { found.put(d.getAddress(), n); }
                } else if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(a)) {
                    // 검색 허용 상태가 바뀜 → 화면 갱신
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                    scanning = false;
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (d != null && st == BluetoothDevice.BOND_BONDED) {
                        synchronized (found) { found.remove(d.getAddress()); }
                        if (d.getAddress().equals(pairing)) { pairing = null; connect(d.getAddress()); }   // 페어링 끝나면 바로 연결 시도
                    } else if (d != null && st == BluetoothDevice.BOND_NONE && d.getAddress().equals(pairing)) {
                        pairing = null;
                    }
                }
            } catch (Exception e) { /* 무시 */ }
            listener.onChange();
        }
    };

    synchronized void scan() {
        try {
            if (adapter == null) return;
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            synchronized (found) { found.clear(); }
            scanning = adapter.startDiscovery();
        } catch (SecurityException e) { scanning = false; }
        listener.onChange();
    }

    void stopScan() {
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (SecurityException e) { /* 무시 */ }
        scanning = false; listener.onChange();
    }

    void pair(String addr) {
        try {
            if (adapter == null) return;
            stopScan();
            BluetoothDevice d = adapter.getRemoteDevice(addr);
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) { connect(addr); return; }
            pairing = addr;
            d.createBond();
        } catch (Exception e) { pairing = null; }
    }

    /** 폰에 등록된 기기를 지운다 (잘못 등록된 항목을 다시 페어링하려고) */
    boolean unpair(String addr) {
        try {
            BluetoothDevice d = adapter.getRemoteDevice(addr);
            java.lang.reflect.Method m = d.getClass().getMethod("removeBond");
            boolean ok = (Boolean) m.invoke(d);
            listener.onChange();
            return ok;
        } catch (Exception e) { return false; }
    }

    // 키·마우스 현재 상태
    private int mods = 0, buttons = 0;
    private final int[] keys = new int[6];
    private double wAcc = 0, pAcc = 0;

    HidController(Context c, Listener l) { ctx = c; listener = l; }

    // ───────────────── 시작·종료 ─────────────────
    synchronized void start() {
        if (started) return;
        try {
            BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = m == null ? null : m.getAdapter();
            if (adapter == null) { supported = false; listener.onChange(); return; }
            started = true;
            if (!rxOn) {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                f.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
                f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
                else ctx.registerReceiver(rx, f);
                rxOn = true;
            }
            adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    hid = (BluetoothHidDevice) proxy;
                    register();
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return;
                    hid = null; registered = false; host = null; listener.onChange();
                }
            }, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            started = false;
        } catch (Exception e) {
            supported = false; started = false;
        }
        listener.onChange();
    }

    synchronized void stop() {
        try { stopScan(); if (rxOn) { ctx.unregisterReceiver(rx); rxOn = false; } } catch (Exception e) { /* 무시 */ }
        try {
            if (hid != null) { hid.unregisterApp(); if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); }
        } catch (Exception e) { /* 무시 */ }
        hid = null; registered = false; host = null; started = false;
        cb.shutdownNow(); out.shutdownNow();
    }

    private void register() {
        try {
            byte[] d = new byte[DESC.length];
            for (int i = 0; i < d.length; i++) d[i] = (byte) DESC[i];
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "SIM Control", "터치패드·키보드", "SIM Tools", BluetoothHidDevice.SUBCLASS1_COMBO, d);
            boolean ok = hid.registerApp(sdp, null, null, cbExec, new BluetoothHidDevice.Callback() {
                @Override public void onAppStatusChanged(BluetoothDevice dev, boolean reg) {
                    registered = reg; listener.onChange();
                }
                @Override public void onConnectionStateChanged(BluetoothDevice dev, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED) host = dev;
                    else if (host != null && dev != null && host.getAddress().equals(dev.getAddress())) { host = null; releaseAll(); }
                    listener.onChange();
                }
                @Override public void onGetReport(BluetoothDevice dev, byte type, byte id, int size) {
                    try { hid.reportError(dev, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ); } catch (Exception e) { /* 무시 */ }
                }
                @Override public void onSetReport(BluetoothDevice dev, byte type, byte id, byte[] data) {
                    try { hid.reportError(dev, BluetoothHidDevice.ERROR_RSP_SUCCESS); } catch (Exception e) { /* 무시 */ }
                }
            });
            if (!ok) supported = false;
        } catch (Exception e) {
            supported = false;
        }
        listener.onChange();
    }

    // ───────────────── 화면 쪽 조회 ─────────────────
    String stateJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", supported);
            o.put("reg", registered);
            BluetoothDevice h = host;
            o.put("conn", h != null);
            o.put("host", h == null ? "" : safeName(h));
            JSONArray arr = new JSONArray();
            if (adapter != null) {
                Set<BluetoothDevice> bonded = null;
                try { bonded = adapter.getBondedDevices(); } catch (SecurityException e) { /* 권한 없음 */ }
                if (bonded != null) for (BluetoothDevice d : bonded) {
                    JSONObject j = new JSONObject();
                    j.put("n", safeName(d)); j.put("a", d.getAddress());
                    j.put("c", h != null && h.getAddress().equals(d.getAddress()));
                    arr.put(j);
                }
            }
            o.put("hosts", arr);
            o.put("scan", scanning);
            boolean disc = false; String nm = "";
            try {
                if (adapter != null) {
                    disc = adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
                    nm = adapter.getName();
                }
            } catch (SecurityException e) { nm = "(권한 없음)"; }
            o.put("disc", disc);
            o.put("name", nm == null ? "" : nm);
            o.put("on", adapter != null && adapter.isEnabled());
            o.put("pConn", perm("android.permission.BLUETOOTH_CONNECT"));
            o.put("pAdv", perm("android.permission.BLUETOOTH_ADVERTISE"));
            o.put("pScan", perm("android.permission.BLUETOOTH_SCAN"));
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("pairing", pairing != null);
            JSONArray fa = new JSONArray();
            synchronized (found) {
                for (java.util.Map.Entry<String, String> e : found.entrySet()) {
                    JSONObject f = new JSONObject();
                    f.put("n", e.getValue()); f.put("a", e.getKey());
                    fa.put(f);
                }
            }
            o.put("found", fa);
        } catch (Exception e) { /* 무시 */ }
        return o.toString();
    }

    private boolean perm(String p) {
        if (Build.VERSION.SDK_INT < 31) return true;      // 안드로이드 11 이하는 이 권한이 따로 없음
        return ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? d.getAddress() : n; } catch (SecurityException e) { return d.getAddress(); }
    }

    void connect(String addr) {
        try {
            if (hid == null || adapter == null) return;
            hid.connect(adapter.getRemoteDevice(addr));
        } catch (Exception e) { /* 무시 */ }
    }

    void disconnect(String addr) {
        try {
            if (hid == null || adapter == null) return;
            hid.disconnect(adapter.getRemoteDevice(addr));
        } catch (Exception e) { /* 무시 */ }
    }

    // ───────────────── pad.html 신호 → HID ─────────────────
    void handle(final String ev, final String json) {
        out.execute(() -> {
            try { run(ev, new JSONObject(json)); } catch (Exception e) { /* 잘못된 신호는 무시 */ }
        });
    }

    private void run(String ev, JSONObject p) throws Exception {
        if (host == null || hid == null) return;
        switch (ev) {
            case "f": {                                   // 이동·스크롤·확대 묶음
                int x = p.optInt("x"), y = p.optInt("y");
                while (x != 0 || y != 0) {
                    int cx = Math.max(-127, Math.min(127, x)), cy = Math.max(-127, Math.min(127, y));
                    mouse(cx, cy, 0, 0); x -= cx; y -= cy;
                }
                wAcc += p.optInt("sy") / 120.0; pAcc += p.optInt("sx") / 120.0;
                int w = (int) wAcc, pn = (int) pAcc;
                if (w != 0 || pn != 0) { wAcc -= w; pAcc -= pn; mouse(0, 0, clamp(w), clamp(pn)); }
                int z = p.optInt("z");
                if (z != 0) {                             // 확대·축소 = Ctrl + 휠
                    int n = z / 120; if (n == 0) n = z > 0 ? 1 : -1;
                    int old = mods; mods |= 0x01; sendKeys(); mouse(0, 0, clamp(n), 0); mods = old; sendKeys();
                }
                break;
            }
            case "b": {                                   // 마우스 버튼
                int bit = "l".equals(p.optString("b")) ? 1 : "r".equals(p.optString("b")) ? 2 : "m".equals(p.optString("b")) ? 4 : 0;
                if (bit == 0) break;
                String a = p.optString("a");
                if ("d".equals(a)) { buttons |= bit; mouse(0, 0, 0, 0); }
                else if ("u".equals(a)) { buttons &= ~bit; mouse(0, 0, 0, 0); }
                else if ("c".equals(a)) { buttons |= bit; mouse(0, 0, 0, 0); Thread.sleep(12); buttons &= ~bit; mouse(0, 0, 0, 0); }
                break;
            }
            case "k": {                                   // 키 하나
                String k = p.optString("k"), a = p.optString("a", "p");
                if ("d".equals(a)) press(k); else if ("u".equals(a)) release(k); else { press(k); Thread.sleep(10); release(k); }
                break;
            }
            case "c": {                                   // 조합키
                JSONArray arr = p.optJSONArray("k");
                if (arr == null) break;
                int n = Math.min(arr.length(), 5);
                for (int i = 0; i < n; i++) press(arr.getString(i));
                Thread.sleep(15);
                for (int i = n - 1; i >= 0; i--) release(arr.getString(i));
                break;
            }
            case "x": {                                   // 글자 입력 (앞에서 백스페이스 n번)
                int bs = Math.max(0, Math.min(500, p.optInt("bs")));
                for (int i = 0; i < bs; i++) tap(0, 0x2A);
                String s = p.optString("s", "");
                if (s.length() > 800) s = s.substring(0, 800);
                typeText(s);
                break;
            }
            default: break;
        }
    }

    private static int clamp(int v) { return Math.max(-127, Math.min(127, v)); }

    // ───────────────── 리포트 전송 ─────────────────
    private void mouse(int dx, int dy, int wheel, int pan) {
        send(2, new byte[]{(byte) buttons, (byte) dx, (byte) dy, (byte) wheel, (byte) pan});
    }

    private void sendKeys() {
        byte[] r = new byte[8];
        r[0] = (byte) mods;
        for (int i = 0; i < 6; i++) r[2 + i] = (byte) keys[i];
        send(1, r);
    }

    private void consumer(int usage) {
        send(3, new byte[]{(byte) (usage & 0xFF), (byte) (usage >> 8)});
        send(3, new byte[]{0, 0});
    }

    private void send(int id, byte[] data) {
        BluetoothDevice h = host; BluetoothHidDevice d = hid;
        if (h == null || d == null) return;
        try { d.sendReport(h, id, data); } catch (Exception e) { /* 무시 */ }
    }

    private void releaseAll() {
        mods = 0; buttons = 0; java.util.Arrays.fill(keys, 0);
        wAcc = 0; pAcc = 0;
    }

    // 키 이름 → HID
    private static final Map<String, Integer> MOD = new HashMap<>();
    private static final Map<String, Integer> KEY = new HashMap<>();
    private static final Map<String, Integer> CONS = new HashMap<>();
    static {
        MOD.put("ctrl", 0x01); MOD.put("shift", 0x02); MOD.put("alt", 0x04); MOD.put("win", 0x08);
        for (int i = 0; i < 26; i++) KEY.put(String.valueOf((char) ('a' + i)), 0x04 + i);
        for (int i = 1; i <= 9; i++) KEY.put(String.valueOf(i), 0x1D + i);
        KEY.put("0", 0x27);
        for (int i = 1; i <= 12; i++) KEY.put("f" + i, 0x39 + i);
        KEY.put("enter", 0x28); KEY.put("esc", 0x29); KEY.put("backspace", 0x2A); KEY.put("tab", 0x2B); KEY.put("space", 0x2C);
        KEY.put("minus", 0x2D); KEY.put("plus", 0x2E); KEY.put("comma", 0x36); KEY.put("period", 0x37); KEY.put("slash", 0x38); KEY.put("backtick", 0x35);
        KEY.put("prtsc", 0x46); KEY.put("ins", 0x49); KEY.put("home", 0x4A); KEY.put("pgup", 0x4B); KEY.put("del", 0x4C);
        KEY.put("end", 0x4D); KEY.put("pgdn", 0x4E); KEY.put("right", 0x4F); KEY.put("left", 0x50); KEY.put("down", 0x51); KEY.put("up", 0x52);
        KEY.put("hangul", 0x90);
        CONS.put("volUp", 0xE9); CONS.put("volDown", 0xEA); CONS.put("mute", 0xE2); CONS.put("play", 0xCD);
        CONS.put("next", 0xB5); CONS.put("prev", 0xB6); CONS.put("stop", 0xB7);
        CONS.put("acHome", 0x223); CONS.put("acBack", 0x224);
        // 셋톱박스 리모컨용 (HID Consumer 사용 코드)
        CONS.put("power", 0x30); CONS.put("menu", 0x40); CONS.put("guide", 0x8D);
        CONS.put("chUp", 0x9C); CONS.put("chDown", 0x9D);
        CONS.put("ff", 0xB3); CONS.put("rew", 0xB4); CONS.put("info", 0x1BD); CONS.put("search", 0x221);
        CONS.put("red", 0x69); CONS.put("green", 0x6A); CONS.put("blue", 0x6B); CONS.put("yellow", 0x6C);
    }

    private void press(String k) {
        Integer m = MOD.get(k);
        if (m != null) { mods |= m; sendKeys(); return; }
        Integer c = CONS.get(k);
        if (c != null) { consumer(c); return; }
        Integer u = KEY.get(k);
        if (u == null) return;
        for (int i = 0; i < 6; i++) if (keys[i] == u) { sendKeys(); return; }
        for (int i = 0; i < 6; i++) if (keys[i] == 0) { keys[i] = u; break; }
        sendKeys();
    }

    private void release(String k) {
        Integer m = MOD.get(k);
        if (m != null) { mods &= ~m; sendKeys(); return; }
        Integer u = KEY.get(k);
        if (u == null) return;
        for (int i = 0; i < 6; i++) if (keys[i] == u) keys[i] = 0;
        sendKeys();
    }

    /** 수정키 + 키 하나를 눌렀다 뗀다 */
    private void tap(int modifier, int usage) {
        int old = mods;
        mods = modifier; keys[0] = usage; sendKeys();
        try { Thread.sleep(8); } catch (InterruptedException e) { /* 무시 */ }
        keys[0] = 0; mods = old; sendKeys();
        try { Thread.sleep(6); } catch (InterruptedException e) { /* 무시 */ }
    }

    // ───────────────── 글자 → 키 입력 ─────────────────
    private static final String SHIFT_NUM = ")!@#$%^&*(";
    private static final String P1 = "-=[]\\;',./`";          // Shift 없이
    private static final String P2 = "_+{}|:\"<>?~";          // Shift 와 함께 (P1 과 같은 순서)
    private static final int[] PU = {0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x36, 0x37, 0x38, 0x35};

    /** 영문·숫자·기호는 그대로, 한글은 두벌식 자판 키로 풀어서 입력 (차량 쪽이 한글 입력 상태여야 글자로 조합됨) */
    private void typeText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') { tap(0, 0x28); continue; }
            if (ch >= 0xAC00 && ch <= 0xD7A3) {
                int v = ch - 0xAC00;
                typeKeys(CHO[v / 588]); typeKeys(JUNG[(v % 588) / 28]); typeKeys(JONG[v % 28]);
            } else if (ch >= 0x3131 && ch <= 0x3163) {
                typeKeys(ch <= 0x314E ? COMPAT_C[ch - 0x3131] : JUNG[ch - 0x314F]);
            } else typeKeys(String.valueOf(ch));
        }
    }

    private void typeKeys(String ks) {
        for (int i = 0; i < ks.length(); i++) {
            char c = ks.charAt(i);
            if (c >= 'a' && c <= 'z') tap(0, 0x04 + (c - 'a'));
            else if (c >= 'A' && c <= 'Z') tap(0x02, 0x04 + (c - 'A'));
            else if (c >= '1' && c <= '9') tap(0, 0x1D + (c - '0'));
            else if (c == '0') tap(0, 0x27);
            else if (c == ' ') tap(0, 0x2C);
            else {
                int n = SHIFT_NUM.indexOf(c);
                if (n >= 0) { tap(0x02, n == 0 ? 0x27 : 0x1D + n); continue; }
                n = P1.indexOf(c);
                if (n >= 0) { tap(0, PU[n]); continue; }
                n = P2.indexOf(c);
                if (n >= 0) tap(0x02, PU[n]);
            }
        }
    }

    private static final String[] CHO = {"r", "R", "s", "e", "E", "f", "a", "q", "Q", "t", "T", "d", "w", "W", "c", "z", "x", "v", "g"};
    private static final String[] JUNG = {"k", "o", "i", "O", "j", "p", "u", "P", "h", "hk", "ho", "hl", "y", "n", "nj", "np", "nl", "b", "m", "ml", "l"};
    private static final String[] JONG = {"", "r", "R", "rt", "s", "sw", "sg", "e", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg", "a", "q", "qt", "t", "T", "d", "w", "c", "z", "x", "v", "g"};
    private static final String[] COMPAT_C = {"r", "R", "rt", "s", "sw", "sg", "e", "E", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg", "a", "q", "Q", "qt", "t", "T", "d", "w", "W", "c", "z", "x", "v", "g"};
}
