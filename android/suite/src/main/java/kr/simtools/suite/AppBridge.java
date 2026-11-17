package kr.simtools.suite;

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

    /** {화면에서 쓰는 id, 안드로이드 권한 이름, 이름, 설명} */
    private static final String[][] CAT = {
            {"camera", Manifest.permission.CAMERA, "카메라", "명함·문서 스캔, 사진 촬영"},
            {"mic", Manifest.permission.RECORD_AUDIO, "마이크", "음성 입력·받아쓰기·실시간 대화"},
            {"location", Manifest.permission.ACCESS_FINE_LOCATION, "위치", "현재 위치 등록·길안내"},
            {"contacts", Manifest.permission.READ_CONTACTS, "연락처", "폰 연락처 검색·전화 걸기"},
            {"calendar", Manifest.permission.READ_CALENDAR, "캘린더", "SIM Brief 일정 읽기"},
            {"bt", "android.permission.BLUETOOTH_CONNECT,android.permission.BLUETOOTH_ADVERTISE,android.permission.BLUETOOTH_SCAN", "블루투스(근처 기기)", "SIM Control 마우스·키보드 연결"},
            {"notif", "android.permission.POST_NOTIFICATIONS", "알림", "재생·출발·브리핑 알림"},
    };
    static final int REQ_FIRST = 82;

    /** 이 폰(안드로이드 버전)에서 실제로 물어야 하는 권한만 */
    private static boolean applies(String id) {
        if ("notif".equals(id)) return Build.VERSION.SDK_INT >= 33;
        if ("bt".equals(id)) return Build.VERSION.SDK_INT >= 31;
        return true;
    }

    private static boolean granted(Activity a, String csv) {
        for (String p : csv.split(",")) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** 처음 설치했을 때: 필요한 권한을 한 번에 묻는다(이미 허용된 것은 제외) */
    static void askAllOnce(Activity a) {
        android.content.SharedPreferences sp = a.getSharedPreferences("simsuite", Context.MODE_PRIVATE);
        if (sp.getBoolean("permAsked", false)) return;
        sp.edit().putBoolean("permAsked", true).apply();
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        for (String[] c : CAT) {
            if (!applies(c[0])) continue;
            for (String p : c[1].split(",")) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) a.requestPermissions(need.toArray(new String[0]), REQ_FIRST);
        else askExact(a);
    }

    /** '알람 및 리마인더'는 일반 권한창이 없어 설정 화면으로 보낸다 (SIM Brief 정시 알림용) */
    static void askExact(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am = (android.app.AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms())
                    a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.getPackageName())));
            }
        } catch (Exception e) { /* 무시 */ }
    }

    private final Activity a;
    private final WebView w;

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
            o.put("key", "suite"); o.put("suite", true);
            o.put("versionName", pi == null || pi.versionName == null ? "" : pi.versionName);
            o.put("build", pi == null ? 0 : pi.getLongVersionCode());
            o.put("buildTime", BuildConfig.BUILD_TIME);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("launcher", a instanceof MainActivity && ((MainActivity) a).isLauncher);
        } catch (Exception e) { /* 무시 */ }
        return o.toString();
    }

    private boolean declared(PackageInfo pi, String perm) {
        if (pi == null || pi.requestedPermissions == null) return false;
        for (String p : pi.requestedPermissions) if (perm.equals(p)) return true;
        return false;
    }

    private boolean can(String perm) { return a.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    /** 이 앱이 쓰는 권한 목록 [{id,label,desc,granted,special}] */
    @JavascriptInterface public String perms() {
        JSONArray arr = new JSONArray();
        try {
            PackageInfo pi = pkgInfo(PackageManager.GET_PERMISSIONS);
            for (String[] c : CAT) {
                if (!applies(c[0])) continue;   // 이 안드로이드 버전에 없는 권한은 숨김
                JSONObject o = new JSONObject();
                o.put("id", c[0]); o.put("label", c[2]); o.put("desc", c[3]);
                o.put("granted", granted(a, c[1])); o.put("special", false);
                arr.put(o);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                android.app.AlarmManager am = (android.app.AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
                JSONObject o = new JSONObject();
                o.put("id", "alarm"); o.put("label", "알람 및 리마인더"); o.put("desc", "SIM Brief 정시 알림");
                o.put("granted", am != null && am.canScheduleExactAlarms()); o.put("special", true);
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
                    if ("alarm".equals(id)) { askExact(a); return; }
                    String perm = null;
                    for (String[] c : CAT) if (c[0].equals(id)) perm = c[1];
                    if (perm == null) return;
                    if (granted(a, perm)) { openSettings(); return; }
                    java.util.ArrayList<String> need = new java.util.ArrayList<>();
                    for (String p : perm.split(",")) if (!can(p)) need.add(p);
                    a.requestPermissions(need.toArray(new String[0]), REQ_APP);
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
                    if (perm == null) { openSettings(); return; }
                    if (Build.VERSION.SDK_INT >= 33 && can(perm.split(",")[0])) {
                        for (String p : perm.split(",")) a.revokeSelfPermissionOnKill(p);
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

    /** SIM Radio 재생 알림 켜기/갱신: ok / nonotif(알림 권한 필요) / err:... */
    @JavascriptInterface public String radioStart(String title, String sub) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && !can("android.permission.POST_NOTIFICATIONS")) return "nonotif";
            RadioService.cb = new RadioService.Callback() {
                @Override public void onCmd(String cmd) { js("window.__simRadioCmd&&window.__simRadioCmd('" + cmd + "')"); }
            };
            if (RadioService.update(title, sub)) return "ok";
            Intent i = new Intent(a, RadioService.class).putExtra("title", title).putExtra("sub", sub);
            a.startForegroundService(i);
            return "ok";
        } catch (Exception e) { return "err:" + e.getMessage(); }
    }

    /** SIM Radio 재생 알림 끄기 */
    @JavascriptInterface public void radioStop() {
        try { a.stopService(new Intent(a, RadioService.class)); } catch (Exception e) { /* 무시 */ }
    }

    /** 요청창 결과 (MainActivity 가 넘겨 준다) */
    void onPermResult(String[] perms, int[] res) {
        String id = "";
        boolean ok = res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        boolean perma = false;
        if (perms != null && perms.length > 0) {
            for (String[] c : CAT) if (java.util.Arrays.asList(c[1].split(",")).contains(perms[0])) id = c[0];
            perma = !ok && !a.shouldShowRequestPermissionRationale(perms[0]);   // 다시 묻지 않음 상태 → 설정에서만 켤 수 있음
        }
        js("window.__simAppPerm&&window.__simAppPerm('" + id + "'," + ok + "," + perma + ")");
    }

    private static final String REL = "https://api.github.com/repos/JHSIM2545/Webtools/releases/tags/suite-latest";
    private String dlUrl = "";

    /** 앱 본체 업데이트 확인: 릴리스(suite-latest)의 빌드 번호와 지금 설치된 빌드 번호를 비교 */
    @JavascriptInterface public void checkUpdate() {
        final long cur = pkgInfo(0) == null ? 0 : pkgInfo(0).getLongVersionCode();
        new Thread(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(REL).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(8000);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                    JSONObject r = new JSONObject(bo.toString("UTF-8"));
                    String body = r.optString("body", "");
                    long latest = 0;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("build=(\\d+)").matcher(body);
                    if (m.find()) latest = Long.parseLong(m.group(1));
                    JSONArray as = r.optJSONArray("assets");
                    if (as != null && as.length() > 0) dlUrl = as.getJSONObject(0).optString("browser_download_url", "");
                    if (latest <= 0) throw new Exception("no build");
                    out = "{\"ok\":true,\"need\":" + (latest > cur) + ",\"latest\":" + latest + ",\"current\":" + cur + "}";
                } catch (Exception e) { out = "{\"ok\":false}"; }
                js("window.__simAppUpd&&window.__simAppUpd(" + out + ")");
            }
        }).start();
    }

    /** 새 앱 파일은 브라우저로 내려받아 직접 설치한다(앱이 스스로 설치하지 않는다) */
    @JavascriptInterface public void installUpdate() {
        try {
            String u = dlUrl.isEmpty() ? "https://github.com/JHSIM2545/Webtools/releases/tag/suite-latest" : dlUrl;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            js("window.__simAppUpdSt&&window.__simAppUpdSt('browser')");
        } catch (Exception e) { js("window.__simAppUpdSt&&window.__simAppUpdSt('fail')"); }
    }

    void destroy() { }
}
