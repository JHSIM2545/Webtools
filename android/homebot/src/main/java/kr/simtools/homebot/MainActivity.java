package kr.simtools.homebot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 홈봇 전용 앱: 전체 화면 WebView 로 homebot.html 을 열고, 카메라·마이크 권한, 화면 켜짐 유지, 기기 음성인식(SimStt)을 연결한다. */
public class MainActivity extends Activity {
    private static final String HOST = "https://jhsim2545.github.io/";
    private static final String URL = HOST + "Webtools/homebot.html?app=apk";
    private static final String LOCAL = "file:///android_asset/homebot.html?app=apk";   // 인터넷이 안 될 때 쓰는 앱 내장 사본
    private static final int REQ_PERM = 91;

    private WebView web;
    private SttBridge stt;
    private TtsBridge tts;
    private PermissionRequest pendingMedia;
    private boolean usedLocal = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);     // 로봇이라 화면이 꺼지지 않게
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setBuiltInZoomControls(false);

        stt = new SttBridge(this, web);
        web.addJavascriptInterface(stt, "SimStt");
        tts = new TtsBridge(this, web);
        web.addJavascriptInterface(tts, "SimTts");
        web.addJavascriptInterface(new PermBridge(), "SimPerm");
        web.addJavascriptInterface(new AppBridge(), "SimApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(HOST) || u.startsWith("file:///android_asset/")) return false;
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && !usedLocal) {      // 인터넷이 없으면 앱에 들어 있는 화면으로
                    usedLocal = true;
                    v.loadUrl(LOCAL);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                runOnUiThread(new Runnable() { @Override public void run() { recreate(); } });
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        List<String> need = missing();
                        if (need.isEmpty()) req.grant(req.getResources());
                        else { pendingMedia = req; requestPermissions(need.toArray(new String[0]), REQ_PERM); }
                    }
                });
            }
        });

        List<String> need = missing();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);   // 처음 실행: 카메라·마이크를 한 번에 요청
        web.loadUrl(online() ? URL : LOCAL);
        usedLocal = !online();
    }

    private List<String> missing() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        return need;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERM) return;
        SharedPreferences.Editor ed = getSharedPreferences("perm", MODE_PRIVATE).edit();
        for (String p : perms) ed.putBoolean(p, true);
        ed.apply();
        if (pendingMedia != null) {
            PermissionRequest r = pendingMedia;
            pendingMedia = null;
            if (missing().isEmpty()) r.grant(r.getResources()); else r.deny();
        }
        notifyPerm();
        if (!missing().isEmpty()) Toast.makeText(this, "카메라·마이크 권한이 있어야 홈봇이 보고 들을 수 있어요 (설정 > 앱 > 홈봇 > 권한)", Toast.LENGTH_LONG).show();
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private void notifyPerm() {
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript("window.__simPerm&&window.__simPerm()", null); } });
    }

    /** 더보기 메뉴: 설치된 앱 버전 조회 · 업데이트 APK 내려받기 · 앱 종료 */
    final class AppBridge {
        @JavascriptInterface
        public int versionCode() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) { Toast.makeText(MainActivity.this, "열 수 없습니다", Toast.LENGTH_LONG).show(); }
                }
            });
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try { stt.destroy(); } catch (Throwable t) { /* 없으면 무시 */ }
                    finishAndRemoveTask();
                }
            });
        }
    }

    /** 홈봇 화면이 권한 상태를 보고 다시 요청할 수 있게 하는 창구 */
    final class PermBridge {
        @JavascriptInterface
        public String perms() {
            boolean c = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean m = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            return "{\"camera\":" + c + ",\"mic\":" + m + "}";
        }

        @JavascriptInterface
        public void request() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    List<String> need = missing();
                    if (need.isEmpty()) { notifyPerm(); return; }
                    SharedPreferences sp = getSharedPreferences("perm", MODE_PRIVATE);
                    boolean blocked = false;      // 이미 한 번 거절했고 시스템이 더는 묻지 않는 상태
                    for (String p : need) if (sp.getBoolean(p, false) && !shouldShowRequestPermissionRationale(p)) blocked = true;
                    if (blocked) {
                        Toast.makeText(MainActivity.this, "설정 > 권한에서 카메라·마이크를 허용해 주세요", Toast.LENGTH_LONG).show();
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } else {
                        requestPermissions(need.toArray(new String[0]), REQ_PERM);
                    }
                }
            });
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        notifyPerm();      // 설정 화면에서 돌아오면 권한 상태를 다시 표시
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);      // 실수로 앱이 종료되지 않게
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (stt != null) stt.destroy();
        if (tts != null) tts.destroy();
        if (web != null) { web.removeJavascriptInterface("SimStt"); web.destroy(); }
        super.onDestroy();
    }
}
