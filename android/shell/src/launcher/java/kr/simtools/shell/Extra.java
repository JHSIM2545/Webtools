package kr.simtools.shell;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.database.Cursor;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.HashMap;
import java.util.Map;

/** 런처 앱: 설치된 SIM 앱 확인·실행, 다른 SIM 앱 APK 내려받아 설치·제거 */
final class Extra {
    private Extra() {}

    static final String BASE = "https://github.com/JHSIM2545/Webtools/releases/download/apps-latest/";
    private static final Map<String, String> PKG = new HashMap<>();
    static {
        for (String id : new String[]{"simtv", "note", "phone", "aitel", "assistant", "calendar", "folder", "message", "kbo", "orchestra", "radio", "calc", "map", "teams", "scan", "recipe", "brief", "price"})
            PKG.put(id, "kr.simtools." + id);
        PKG.put("pad", "kr.simtools.simpad");
        PKG.put("launcher", "kr.simtools.launcher");   // 런처 자신도 업데이트 대상
    }
    private static String file(String id) { return "simtools-" + id + ".apk"; }

    private static BroadcastReceiver receiver;
    private static final Map<Long, String> pending = new HashMap<>();
    /* 일괄 업데이트: 한꺼번에 내려받고, 설치 화면은 한 번에 하나씩(앞 설치가 끝나 앱으로 돌아오면 다음) 연다. 런처 자신은 맨 마지막. */
    private static final java.util.LinkedHashMap<String, Uri> ready = new java.util.LinkedHashMap<>();   // 받기 끝, 설치 차례 대기
    private static final java.util.Set<String> batch = new java.util.LinkedHashSet<>();                  // 일괄 대상 중 아직 설치 화면을 안 연 앱
    private static boolean installing;

    static void attach(final Activity a, final WebView w) {
        w.addJavascriptInterface(new Bridge(a, w), "SimLauncher");
        act = a; web = w;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { poll(); }   // 완료 알림이 와도, 못 받아도 아래 폴링이 같은 일을 한다
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else a.registerReceiver(receiver, f);
    }

    private static Activity act; private static WebView web;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean polling;
    private static String waited;
    private static final Runnable TICK = new Runnable() { @Override public void run() { polling = false; poll(); } };

    /** 받는 중인 파일 상태를 직접 확인한다 — 진행률 표시, 실패 원인 안내, 완료 알림을 놓쳐도 설치 진행 */
    static void poll() {
        if (act == null || web == null) return;
        DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
        for (Long id : new java.util.ArrayList<>(pending.keySet())) {
            String tool = pending.get(id);
            Cursor c = null;
            try {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c == null || !c.moveToFirst()) { pending.remove(id); batch.remove(tool); tell(web, tool, "fail"); continue; }
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    pending.remove(id);
                    Uri u = dm.getUriForDownloadedFile(id);
                    if (u == null) { batch.remove(tool); tell(web, tool, "fail"); } else ready.put(tool, u);
                } else if (st == DownloadManager.STATUS_FAILED) {
                    pending.remove(id); batch.remove(tool);
                    progress(tool, -1, "받기 실패(코드 " + reason + ")"); tell(web, tool, "fail");
                } else {
                    String note = "";
                    if (st == DownloadManager.STATUS_PAUSED) note = reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ? "Wi-Fi 연결을 기다리는 중" : reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ? "네트워크를 기다리는 중" : "잠시 멈춤";
                    else if (st == DownloadManager.STATUS_PENDING) note = "대기 중";
                    progress(tool, tot > 0 ? (int) (got * 100 / tot) : 0, note);
                }
            } catch (Exception e) { /* 다음 주기에 다시 */ }
            finally { if (c != null) c.close(); }
        }
        pump(act, web);
        if ((!pending.isEmpty() || !ready.isEmpty()) && !polling) { polling = true; H.postDelayed(TICK, 1000); }
    }

    private static void progress(String tool, int pct, String note) {
        final String js = "window.__simProgress&&window.__simProgress('" + tool + "'," + pct + ",'" + note + "')";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    /** 다음 설치 화면을 연다 (한 번에 하나, 런처는 마지막). 앱이 화면 앞에 있을 때만 — 뒤에서는 안드로이드가 막아서 아무 반응이 없어 보인다 */
    private static void pump(Context c, WebView w) {
        if (installing || ready.isEmpty()) return;
        String next = null;
        for (String id : ready.keySet()) if (!"launcher".equals(id)) { next = id; break; }
        if (next == null) {
            for (String id : batch) if (!"launcher".equals(id)) return;   // 다른 앱이 아직 받는 중 → 런처는 그 뒤에
            next = "launcher";
        }
        if (act == null || !act.hasWindowFocus()) { if (!next.equals(waited)) { waited = next; tell(w, next, "wait"); } return; }
        waited = null;
        Uri u = ready.remove(next); batch.remove(next);
        Intent inst = new Intent(Intent.ACTION_VIEW);
        inst.setDataAndType(u, "application/vnd.android.package-archive");
        inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(inst); installing = true; tell(w, next, "done");
            final int left = ready.size() + batch.size();
            if (left > 0) w.post(() -> w.evaluateJavascript("window.__simBatchLeft&&window.__simBatchLeft(" + left + ")", null));
        } catch (Exception e) { tell(w, next, "fail"); pump(c, w); }
    }

    static void onResume(final Activity a, final WebView w) {
        act = a; web = w;
        installing = false;      // 설치 화면에서 돌아옴(설치했든 취소했든) → 다음 앱
        H.postDelayed(new Runnable() { @Override public void run() { poll(); } }, 500);   // 창 포커스가 돌아온 뒤에 확인
        w.post(() -> w.evaluateJavascript("window.__simRefresh&&window.__simRefresh()", null));
    }

    static void destroy(Activity a) {
        try { if (receiver != null) a.unregisterReceiver(receiver); } catch (Exception e) { /* 무시 */ }
        receiver = null;
    }

    private static void tell(final WebView w, final String tool, final String st) {
        w.post(() -> w.evaluateJavascript("window.__simInstall&&window.__simInstall('" + tool + "','" + st + "')", null));
    }

    static class Bridge {
        private final Activity a; private final WebView w;
        Bridge(Activity a, WebView w) { this.a = a; this.w = w; }

        private boolean has(String pkg) {
            try { a.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
        }

        /** 설치된 도구 id 목록 (JSON 배열) */
        @JavascriptInterface public String installed() {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : PKG.entrySet()) if (has(e.getValue())) arr.put(e.getKey());
            return arr.toString();
        }

        private long ver(String pkg) {
            try { return a.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode(); } catch (Exception e) { return -1; }
        }

        /** 서버(릴리스)의 최신 버전 번호와 설치된 앱의 버전을 비교해, 업데이트가 필요한 앱 id 목록을 JS 로 알려 준다 */
        @JavascriptInterface public void checkUpdates() {
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(BASE + "versions.json").openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(true);
                    InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
                    JSONObject apps = new JSONObject(bo.toString("UTF-8")).getJSONObject("apps");   // 앱마다 따로 버전 비교
                    JSONArray need = new JSONArray(); JSONObject latestMap = new JSONObject();
                    for (Map.Entry<String, String> e : PKG.entrySet()) {
                        long v = ver(e.getValue()), latest = apps.has(e.getKey()) ? apps.getJSONObject(e.getKey()).optLong("v", -1) : -1;
                        if (v >= 0 && latest > v) { need.put(e.getKey()); latestMap.put(e.getKey(), latest); }
                    }
                    final String js = "window.__simUpdates&&window.__simUpdates(" + need + "," + latestMap + ")";
                    w.post(() -> w.evaluateJavascript(js, null));
                } catch (Exception e) { /* 인터넷이 없으면 조용히 넘어감 */ }
            }).start();
        }

        @JavascriptInterface public void open(String id) {
            String p = PKG.get(id); if (p == null) return;
            Intent i = a.getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) a.startActivity(i);
        }

        private long enqueue(String id) {
            try { new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), file(id)).delete(); } catch (Exception e) { /* 무시 */ }   // 이전에 받은 같은 파일이 남아 있으면 지운다
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(BASE + file(id)));
            r.setTitle("SIM 앱 내려받는 중");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file(id));
            long did = dm.enqueue(r);
            pending.put(did, id);
            return did;
        }

        private boolean installAllowed(String id) {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                tell(w, id, "perm");
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                return false;
            }
            return true;
        }

        @JavascriptInterface public void install(final String id) {
            final String p = PKG.get(id); if (p == null) return;
            a.runOnUiThread(() -> {
                try {
                    if (!installAllowed(id)) return;
                    if (batch.contains(id) || pending.containsValue(id)) { progress(id, 0, "이미 받는 중"); return; }
                    batch.add(id); enqueue(id);
                    tell(w, id, "start"); poll();
                } catch (Exception e) {
                    tell(w, id, "fail");
                }
            });
        }

        /** 업데이트가 필요한 앱 여러 개를 한꺼번에 받아 차례로 설치한다 (ids: JSON 배열) */
        @JavascriptInterface public void installAll(final String ids) {
            a.runOnUiThread(() -> {
                try {
                    if (!installAllowed("batch")) return;
                    JSONArray arr = new JSONArray(ids); int n = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        String id = arr.getString(i);
                        if (PKG.get(id) == null || batch.contains(id) || ready.containsKey(id)) continue;
                        batch.add(id); enqueue(id); n++;
                    }
                    final int cnt = n; poll();
                    w.post(() -> w.evaluateJavascript("window.__simBatch&&window.__simBatch(" + cnt + ")", null));
                } catch (Exception e) {
                    tell(w, "batch", "fail");
                }
            });
        }

        @JavascriptInterface public void uninstall(String id) {
            final String p = PKG.get(id); if (p == null) return;
            a.runOnUiThread(() -> {
                try { a.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + p))); } catch (Exception e) { /* 무시 */ }
            });
        }
    }
}
