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
import android.database.Cursor;
import android.os.Environment;
import android.provider.ContactsContract;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SIM Tools 의 화면(웹)을 앱으로 띄우는 공용 껍데기. 런처 앱과 도구별 앱이 같이 쓴다. */
public class MainActivity extends Activity {
    private static final String BASE = "https://jhsim2545.github.io/";
    private static final int REQ_FILE = 77, REQ_MEDIA = 78, REQ_GEO = 79, REQ_CONTACTS = 80;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingMedia;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private boolean errorShown = false;
    private final List<String> pendingShare = new ArrayList<>();   // 연락처 앱에서 공유받은 vCard 텍스트(화면이 가져가기 전까지 보관)

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

        web.addJavascriptInterface(new SessionBridge(), "SimSession");
        web.addJavascriptInterface(new ShareBridge(), "SimShare");
        if (BuildConfig.CONTACTS) web.addJavascriptInterface(new ContactsBridge(), "SimContacts");
        Extra.attach(this, web);
        handleAuth(getIntent());
        handleShare(getIntent());
        web.loadUrl(BuildConfig.START_URL);
    }

    /** 로그인 세션 공유(런처가 보관, 도구 앱은 런처에서 받아 씀) + 구글 로그인용 브라우저 열기 */
    private class SessionBridge {
        @JavascriptInterface public String get() { return SessionStore.get(MainActivity.this); }

        @JavascriptInterface public void put(String json) {
            if (json != null && json.startsWith("{")) SessionStore.put(MainActivity.this, json);
        }

        /** 런처에서 로그아웃하면 "OUT" 으로 표시해 도구 앱들도 로그아웃되게 한다 */
        @JavascriptInterface public void clear() {
            if (BuildConfig.IS_LAUNCHER) SessionStore.put(MainActivity.this, "OUT");
        }

        /** 구글은 앱 안 WebView 로그인을 막으므로 기본 브라우저에서 진행 (우리 사이트 주소만 허용) */
        @JavascriptInterface public void openBrowser(final String url) {
            if (url == null || !url.startsWith(BASE)) return;
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /** 브라우저에서 구글 로그인을 마치고 kr.simtools.launcher://auth?... 로 돌아온 경우 세션을 저장하고 화면에 알린다 */
    private void handleAuth(Intent i) {
        if (!BuildConfig.IS_LAUNCHER || i == null) return;
        Uri d = i.getData();
        if (d == null || !"kr.simtools.launcher".equals(d.getScheme()) || !"auth".equals(d.getHost())) return;
        String at = d.getQueryParameter("access_token"), rt = d.getQueryParameter("refresh_token");
        i.setData(null);
        if (at == null || rt == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("access_token", at);
            o.put("refresh_token", rt);
            SessionStore.put(this, o.toString());
        } catch (Exception e) { return; }
        if (web != null) web.post(() -> web.evaluateJavascript("window.__simAuthReturn&&window.__simAuthReturn()", null));
    }

    /** 폰 연락처 읽기(SIM Message 앱 전용): 권한 확인·요청, 전체 연락처를 JSON 으로 넘긴다. 화면(웹)이 검색·선택을 처리한다. */
    private class ContactsBridge {
        @JavascriptInterface public boolean has() { return granted(Manifest.permission.READ_CONTACTS); }

        @JavascriptInterface public void request() {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS));
        }

        /** [{n:이름, c:회사, t:직함, p:[번호,...]}] — 번호가 있는 연락처만, 최대 8000명 */
        @JavascriptInterface public String all() {
            JSONArray out = new JSONArray();
            if (!granted(Manifest.permission.READ_CONTACTS)) return out.toString();
            try {
                Map<Long, JSONObject> by = new LinkedHashMap<>();
                try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
                    if (c != null) while (c.moveToNext() && by.size() < 8000) {
                        long id = c.getLong(0); String num = c.getString(2);
                        if (num == null || num.trim().isEmpty()) continue;
                        JSONObject o = by.get(id);
                        if (o == null) { o = new JSONObject(); o.put("n", c.getString(1) == null ? "" : c.getString(1)); o.put("c", ""); o.put("t", ""); o.put("p", new JSONArray()); by.put(id, o); }
                        JSONArray p = o.getJSONArray("p"); if (p.length() < 5) p.put(num.trim());
                    }
                }
                try (Cursor c = getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                        new String[]{ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Organization.COMPANY, ContactsContract.CommonDataKinds.Organization.TITLE},
                        ContactsContract.Data.MIMETYPE + "=?", new String[]{ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE}, null)) {
                    if (c != null) while (c.moveToNext()) {
                        JSONObject o = by.get(c.getLong(0)); if (o == null) continue;
                        String co = c.getString(1), ti = c.getString(2);
                        if (co != null && o.optString("c").isEmpty()) o.put("c", co);
                        if (ti != null && o.optString("t").isEmpty()) o.put("t", ti);
                    }
                }
                for (JSONObject o : by.values()) out.put(o);
            } catch (Exception e) { /* 읽다가 실패하면 지금까지 모은 것만 */ }
            return out.toString();
        }
    }

    /** 화면(웹)이 공유받은 연락처를 가져가는 창구: JSON 문자열 배열을 돌려주고 비운다 */
    private class ShareBridge {
        @JavascriptInterface public String take() {
            synchronized (pendingShare) {
                if (pendingShare.isEmpty()) return "";
                JSONArray a = new JSONArray();
                for (String t : pendingShare) a.put(t);
                pendingShare.clear();
                return a.toString();
            }
        }
    }

    /** 연락처 앱 등에서 '공유'로 들어온 vCard(.vcf 파일 또는 텍스트)를 읽어 보관하고 화면에 알린다 */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent i) {
        if (i == null) return;
        String act = i.getAction();
        final boolean one = Intent.ACTION_SEND.equals(act), many = Intent.ACTION_SEND_MULTIPLE.equals(act);
        if (!one && !many) return;
        final List<Uri> uris = new ArrayList<>();
        if (one) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
        else { ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        final String text = i.getStringExtra(Intent.EXTRA_TEXT);
        i.setAction(Intent.ACTION_MAIN);   // 화면 회전·재생성 때 같은 공유를 다시 처리하지 않도록
        new Thread(() -> {
            List<String> got = new ArrayList<>();
            for (Uri u : uris) {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) continue;
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n, tot = 0;
                    while ((n = in.read(buf)) > 0 && tot < 4_000_000) { bo.write(buf, 0, n); tot += n; }
                    String t = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                    if (t.length() > 0 && t.charAt(0) == '\uFEFF') t = t.substring(1);
                    got.add(t);
                } catch (Exception e) { /* 읽지 못한 파일은 건너뜀 */ }
            }
            if (text != null && text.toUpperCase().contains("BEGIN:VCARD")) got.add(text);
            runOnUiThread(() -> {
                if (got.isEmpty()) { Toast.makeText(this, "공유받은 연락처를 읽지 못했습니다", Toast.LENGTH_SHORT).show(); return; }
                synchronized (pendingShare) { pendingShare.addAll(got); }
                if (web != null) web.evaluateJavascript("window.__simShare&&window.__simShare()", null);   // 화면이 이미 열려 있으면 바로 가져가게
            });
        }).start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleAuth(i);
        handleShare(i);
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
        } else if (code == REQ_CONTACTS) {
            final boolean g = ok;
            if (web != null) web.post(() -> web.evaluateJavascript("window.__simContactsPerm&&window.__simContactsPerm(" + g + ")", null));
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
