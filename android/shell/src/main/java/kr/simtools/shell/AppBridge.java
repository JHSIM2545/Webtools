package kr.simtools.shell;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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

/**
 * 모든 SIM 앱(런처·도구)에 들어가는 공통 창구 "SimApp".
 * 화면(웹)의 ⋯ 메뉴가 쓴다: 버전·빌드 정보, 업데이트 확인·설치, 권한 확인·요청.
 */
final class AppBridge {
    static final int REQ_APP = 81;
    private static final String BASE = "https://github.com/JHSIM2545/Webtools/releases/download/apps-latest/";

    /** {화면에서 쓰는 id, 안드로이드 권한 이름, 이름, 설명} */
    private static final String[][] CAT = {
            {"camera", Manifest.permission.CAMERA, "카메라", "명함·문서 스캔, 사진 촬영"},
            {"mic", Manifest.permission.RECORD_AUDIO, "마이크", "음성 입력·받아쓰기·실시간 대화"},
            {"location", Manifest.permission.ACCESS_FINE_LOCATION, "위치", "현재 위치 등록·길안내"},
            {"contacts", Manifest.permission.READ_CONTACTS, "연락처", "폰 연락처 검색·전화 걸기"},
            {"notif", "android.permission.POST_NOTIFICATIONS", "알림", "다운로드·업데이트 알림"},
    };

    private final Activity a;
    private final WebView w;
    private BroadcastReceiver rx;
    private long dlId = -1;

    AppBridge(Activity a, WebView w) {
        this.a = a;
        this.w = w;
    }

    private void js(final String code) {
        w.post(new Runnable() {
            @Override public void run() { w.evaluateJavascript(code, null); }
        });
    }

    private PackageInfo pkgInfo(int flags) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), flags); } catch (Exception e) { return null; }
    }

    /** 앱 이름·버전·빌드 번호·빌드 날짜 (JSON) */
    @JavascriptInterface public String info() {
        JSONObject o = new JSONObject();
        try {
            PackageInfo pi = pkgInfo(0);
            o.put("name", a.getApplicationInfo().loadLabel(a.getPackageManager()).toString());
            o.put("pkg", a.getPackageName());
            o.put("key", BuildConfig.APP_KEY);
            o.put("versionName", pi == null || pi.versionName == null ? "" : pi.versionName);
            o.put("build", pi == null ? 0 : pi.getLongVersionCode());
            o.put("buildTime", BuildConfig.BUILD_TIME);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("launcher", BuildConfig.IS_LAUNCHER);
        } catch (Exception e) { /* 무시 */ }
        return o.toString();
    }

    private boolean declared(PackageInfo pi, String perm) {
        if (pi == null || pi.requestedPermissions == null) return false;
        for (String p : pi.requestedPermissions) if (perm.equals(p)) return true;
        return false;
    }

    private boolean can(String perm) { return a.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    /** 이 앱이 쓰는 권한 목록 [{id,label,desc,granted,special}] */
    @JavascriptInterface public String perms() {
        JSONArray arr = new JSONArray();
        try {
            PackageInfo pi = pkgInfo(PackageManager.GET_PERMISSIONS);
            for (String[] c : CAT) {
                if (!declared(pi, c[1])) continue;
                if ("notif".equals(c[0]) && Build.VERSION.SDK_INT < 33) continue;   // 13 미만은 알림 권한이 따로 없음
                JSONObject o = new JSONObject();
                o.put("id", c[0]); o.put("label", c[2]); o.put("desc", c[3]);
                o.put("granted", can(c[1])); o.put("special", false);
                arr.put(o);
            }
            if (declared(pi, Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
                JSONObject o = new JSONObject();
                o.put("id", "install"); o.put("label", "앱 설치(업데이트)"); o.put("desc", "새 버전 APK 설치를 허용");
                o.put("granted", canInstall()); o.put("special", true);
                arr.put(o);
            }
        } catch (Exception e) { /* 무시 */ }
        return arr.toString();
    }

    /** 권한 켜기: 아직 허용 안 된 권한이면 요청창, 이미 허용된 것(끄기)·영구 거절·특수 권한은 폰 설정 화면으로 보낸다 */
    @JavascriptInterface public void requestPerm(final String id) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if ("install".equals(id)) { openInstallSettings(); return; }
                    String perm = null;
                    for (String[] c : CAT) if (c[0].equals(id)) perm = c[1];
                    if (perm == null) return;
                    if (can(perm)) { openSettings(); return; }
                    a.requestPermissions(new String[]{perm}, REQ_APP);
                } catch (Exception e) { openSettings(); }
            }
        });
    }

    /** 폰의 이 앱 설정 화면(권한 끄기 등) */
    @JavascriptInterface public void openSettings() {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.getPackageName(), null));
                    a.startActivity(i);
                } catch (Exception e) { /* 무시 */ }
            }
        });
    }

    private void openInstallSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Exception e) { openSettings(); }
    }

    /**
     * 권한 끄기. 안드로이드 13 이상은 앱이 스스로 철회를 요청할 수 있다(앱이 곧 종료되고, 다시 열면 꺼진 상태).
     * 그보다 낮은 버전은 폰 설정 화면으로 보낸다.
     */
    @JavascriptInterface public void revokePerm(final String id) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    String perm = null;
                    for (String[] c : CAT) if (c[0].equals(id)) perm = c[1];
                    if (perm == null || "install".equals(id)) { openSettings(); return; }
                    if (Build.VERSION.SDK_INT >= 33 && can(perm)) {
                        a.revokeSelfPermissionOnKill(perm);
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                a.finishAndRemoveTask();
                                android.os.Process.killProcess(android.os.Process.myPid());
                            }
                        }, 500);
                    } else openSettings();
                } catch (Exception e) { openSettings(); }
            }
        });
    }

    /** 출발 알림 시작(앱을 꺼도 계속 확인): ok / noloc(위치 권한 필요) / nonotif(알림 권한 필요) / err:... */
    @JavascriptInterface public String tripStart(String json) {
        try {
            if (!can(Manifest.permission.ACCESS_FINE_LOCATION)) return "noloc";
            if (Build.VERSION.SDK_INT >= 33 && !can("android.permission.POST_NOTIFICATIONS")) return "nonotif";
            Intent i = new Intent(a, TripService.class).putExtra("trip", json);
            a.startForegroundService(i);
            return "ok";
        } catch (Exception e) { return "err:" + e.getMessage(); }
    }

    @JavascriptInterface public void tripStop() {
        try { a.stopService(new Intent(a, TripService.class)); } catch (Exception e) { /* 무시 */ }
        a.getSharedPreferences("simtrip", Context.MODE_PRIVATE).edit().remove("trip").apply();
    }

    /** 대기 중인 출발 알림(JSON, 없으면 빈 문자열) */
    @JavascriptInterface public String tripActive() {
        return a.getSharedPreferences("simtrip", Context.MODE_PRIVATE).getString("trip", "");
    }

    /** 요청창 결과 (MainActivity 가 넘겨 준다) */
    void onPermResult(String[] perms, int[] res) {
        String id = "";
        boolean ok = res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        boolean perma = false;
        if (perms != null && perms.length > 0) {
            for (String[] c : CAT) if (c[1].equals(perms[0])) id = c[0];
            perma = !ok && !a.shouldShowRequestPermissionRationale(perms[0]);   // 다시 묻지 않음 상태 → 설정에서만 켤 수 있음
        }
        js("window.__simAppPerm&&window.__simAppPerm('" + id + "'," + ok + "," + perma + ")");
    }

    /** 서버의 최신 빌드 번호와 비교 → window.__simAppUpd({ok,current,latest,need}) */
    @JavascriptInterface public void checkUpdate() {
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject out = new JSONObject();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(BASE + "versions.json").openConnection();
                    c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(true);
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024]; int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    JSONObject apps = new JSONObject(bo.toString("UTF-8")).getJSONObject("apps");
                    long latest = apps.has(BuildConfig.APP_KEY) ? apps.getJSONObject(BuildConfig.APP_KEY).optLong("v", -1) : -1;
                    PackageInfo pi = pkgInfo(0);
                    long cur = pi == null ? 0 : pi.getLongVersionCode();
                    out.put("ok", latest >= 0); out.put("current", cur); out.put("latest", latest); out.put("need", latest > cur);
                } catch (Exception e) {
                    try { out.put("ok", false); out.put("err", String.valueOf(e.getMessage())); } catch (Exception e2) { /* 무시 */ }
                }
                js("window.__simAppUpd&&window.__simAppUpd(" + out + ")");
            }
        }).start();
    }

    private void tell(String st) { js("window.__simAppUpdSt&&window.__simAppUpdSt('" + st + "')"); }

    /** 이 앱의 새 APK 를 내려받아 설치 화면을 띄운다 → window.__simAppUpdSt('start'|'done'|'fail'|'perm') */
    @JavascriptInterface public void installUpdate() {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (!canInstall()) { tell("perm"); openInstallSettings(); return; }
                    final String file = "simtools-" + BuildConfig.APP_KEY + ".apk";
                    try { new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), file).delete(); } catch (Exception e) { /* 무시 */ }
                    if (rx == null) {
                        rx = new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent i) {
                                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                                if (id != dlId) return;
                                dlId = -1;
                                DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                                Uri u = dm.getUriForDownloadedFile(id);
                                if (u == null) { tell("fail"); return; }
                                tell("done");
                                Intent inst = new Intent(Intent.ACTION_VIEW);
                                inst.setDataAndType(u, "application/vnd.android.package-archive");
                                inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                                try { c.startActivity(inst); } catch (Exception e) { tell("fail"); }
                            }
                        };
                        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
                        else a.registerReceiver(rx, f);
                    }
                    DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(BASE + file));
                    r.setTitle("SIM 앱 업데이트 내려받는 중");
                    r.setMimeType("application/vnd.android.package-archive");
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file);
                    dlId = dm.enqueue(r);
                    tell("start");
                } catch (Exception e) {
                    tell("fail");
                }
            }
        });
    }

    void destroy() {
        try { if (rx != null) a.unregisterReceiver(rx); } catch (Exception e) { /* 무시 */ }
        rx = null;
    }
}
