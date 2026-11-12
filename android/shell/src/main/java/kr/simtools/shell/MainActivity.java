package kr.simtools.shell;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** SIM Tools 의 화면(웹)을 앱으로 띄우는 공용 껍데기. 런처 앱과 도구별 앱이 같이 쓴다. */
public class MainActivity extends Activity {
    private static final String BASE = "https://jhsim2545.github.io/";
    private static final int REQ_FILE = 77, REQ_MEDIA = 78, REQ_GEO = 79;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingMedia;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private boolean errorShown = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#171a23"));
        getWindow().setNavigationBarColor(Color.parseColor("#0f1117"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1117"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String s = u.toString();
                if (s.startsWith(BASE)) {
                    if (!BuildConfig.IS_LAUNCHER && isLauncherPage(u)) { openLauncher(); return true; }
                    return false;
                }
                try {      // tel:, sms:, 지도·내비 앱, 외부 링크는 알맞은 앱으로
                    Intent i = s.startsWith("intent:") ? Intent.parseUri(s, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "열 수 있는 앱이 없습니다", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) showOffline(v);
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame() && e.getStatusCode() >= 400) showOffline(v);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    List<String> need = new ArrayList<>();
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (need.isEmpty()) req.grant(req.getResources());
                    else { pendingMedia = req; requestPermissions(need.toArray(new String[0]), REQ_MEDIA); }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) cb.invoke(origin, true, false);
                else { geoOrigin = origin; geoCb = cb; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_GEO); }
            }

            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {      // 동영상 전체 화면
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb;
                ((FrameLayout) getWindow().getDecorView()).addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                ((FrameLayout) getWindow().getDecorView()).removeView(customView);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (!url.startsWith("http")) { Toast.makeText(this, "이 형식은 앱에서 바로 저장할 수 없습니다", Toast.LENGTH_SHORT).show(); return; }
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String name = URLUtil.guessFileName(url, cd, mime);
                r.setTitle(name); r.setMimeType(mime);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "내려받는 중: " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "내려받기에 실패했습니다", Toast.LENGTH_SHORT).show();
            }
        });

        Extra.attach(this, web);
        web.loadUrl(BuildConfig.START_URL);
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private static boolean isLauncherPage(Uri u) {
        String p = u.getPath() == null ? "" : u.getPath();
        return p.equals("/Webtools/") || p.equals("/Webtools") || p.equals("/Webtools/index.html");
    }

    private void openLauncher() {
        Intent i = getPackageManager().getLaunchIntentForPackage("kr.simtools.launcher");
        if (i != null) startActivity(i);
        finish();
    }

    private void showOffline(WebView v) {
        if (errorShown) return;
        errorShown = true;
        v.loadDataWithBaseURL(null, "<html><body style='background:#0f1117;color:#e8eaf0;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='font-size:42px'>📡</div><p style='margin:12px 0 18px'>인터넷에 연결되어 있지 않습니다</p>"
                + "<button onclick='location.href=\"" + BuildConfig.START_URL + "\"' style='font-size:16px;padding:10px 22px;border-radius:10px;border:1px solid #2a2f3f;background:#1f2330;color:#e8eaf0'>다시 시도</button></body></html>",
                "text/html", "utf-8", null);
        v.postDelayed(() -> errorShown = false, 1500);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (code == REQ_MEDIA && pendingMedia != null) {
            if (ok) pendingMedia.grant(pendingMedia.getResources()); else pendingMedia.deny();
            pendingMedia = null;
        } else if (code == REQ_GEO && geoCb != null) {
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Extra.onResume(this, web);
    }

    @Override
    protected void onPause() {
        // 라디오·TV 등 소리는 계속 나게 하려고 web.onPause()는 부르지 않는다
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { web.getWebChromeClient().onHideCustomView(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        if (BuildConfig.IS_LAUNCHER) moveTaskToBack(true);
        else openLauncher();
    }

    @Override
    protected void onDestroy() {
        Extra.destroy(this);
        web.destroy();
        super.onDestroy();
    }
}
