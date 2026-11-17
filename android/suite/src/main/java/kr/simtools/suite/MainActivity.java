package kr.simtools.suite;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.database.Cursor;
import android.os.Environment;
import android.provider.OpenableColumns;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SIM Tools 의 화면(웹)을 앱으로 띄우는 공용 껍데기. 런처 앱과 도구별 앱이 같이 쓴다. */
public class MainActivity extends Activity {
    private static final String BASE = "https://jhsim2545.github.io/";
    private static final String SHARE_HOST = "https://simshare.invalid/__share/";   // 공유받은 파일을 화면(웹)이 가져가는 가짜 주소 (서비스워커를 거치지 않게 다른 출처로 둠)
    private static final int REQ_FILE = 77, REQ_MEDIA = 78, REQ_GEO = 79, REQ_CONTACTS = 80;

    String key;                 // null 이면 런처, 아니면 열린 도구의 id (simtools://tool/<id>)
    boolean isLauncher = true;
    private String startUrl = BASE + "Webtools/index.html";
    private WebView web;
    private AppBridge appBridge;   // ⋯ 메뉴: 버전 정보·업데이트·권한
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingMedia;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private boolean errorShown = false;
    private final List<JSONObject> pendingFiles = new ArrayList<>();   // SIM Note 로 공유받은 PDF·그림·문서 파일(앱 캐시에 복사해 둔 것의 목록. 화면이 가져가기 전까지 보관)
    private final List<String> pendingShare = new ArrayList<>();   // 연락처 앱에서 공유받은 vCard 텍스트(화면이 가져가기 전까지 보관)

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        resolveTarget(getIntent());
        getWindow().setStatusBarColor(Color.parseColor("#171a23"));
        getWindow().setNavigationBarColor(Color.parseColor("#0f1117"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1117"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);   // 웹 느낌의 당겨 늘어나는 효과 제거
        web.setVerticalScrollBarEnabled(false);
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
                    if (!isLauncher && isLauncherPage(u)) { openLauncher(); return true; }
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

            /** 인터넷이 없을 때는 앱 안에 함께 들어 있는 화면 파일로 열어, 네이티브 앱처럼 오프라인에서도 켜지게 한다 */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                try {
                    String u = r.getUrl().toString();
                    if ("GET".equals(r.getMethod()) && (u.startsWith(SHARE_HOST) || u.startsWith(BASE + "Webtools/__share/"))) return sharedFileResponse(r.getUrl());   // SIM Note 로 공유받은 파일
                    if (!"GET".equals(r.getMethod()) || !u.startsWith(BASE + "Webtools") || online()) return null;
                    String p = r.getUrl().getPath() == null ? "" : r.getUrl().getPath().replaceFirst("^/Webtools/?", "");
                    if (p.isEmpty()) p = "index.html";
                    InputStream in = getAssets().open("web/" + p);
                    String ext = p.contains(".") ? p.substring(p.lastIndexOf('.') + 1).toLowerCase() : "";
                    String mime = "html".equals(ext) ? "text/html" : "js".equals(ext) ? "application/javascript" : "css".equals(ext) ? "text/css" : "png".equals(ext) ? "image/png"
                            : "svg".equals(ext) ? "image/svg+xml" : "json".equals(ext) || "webmanifest".equals(ext) ? "application/json" : "wav".equals(ext) ? "audio/wav" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) { return null; }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) showOffline(v);
            }

            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                runOnUiThread(() -> { try { v.loadUrl(startUrl); } catch (Exception e) { recreate(); } });
                return true;
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
        ttsBridge = new TtsBridge(this, web);
        web.addJavascriptInterface(ttsBridge, "SimTts");
        web.addJavascriptInterface(new ContactsBridge(), "SimContacts");
        appBridge = new AppBridge(this, web);
        web.addJavascriptInterface(appBridge, "SimApp");
        SuiteExtra.attach(this, web, isLauncher);
        ScanExtra.attach(this, web);
        PipExtra.attach(this, web);
        BriefExtra.attach(this, web);
        handleAuth(getIntent());
        handleShare(getIntent());
        web.post(() -> AppBridge.askAllOnce(this));   // 처음 설치 후 첫 실행: 필요한 권한을 한 번에 묻는다
        web.loadUrl(startUrl);
    }

    /** 로그인 세션 공유(런처가 보관, 도구 앱은 런처에서 받아 씀) + 구글 로그인용 브라우저 열기 */
    private class SessionBridge {
        @JavascriptInterface public String get() { return SessionStore.get(MainActivity.this); }

        @JavascriptInterface public void put(String json) {
            if (json != null && json.startsWith("{")) SessionStore.put(MainActivity.this, json);
        }

        /** 런처에서 로그아웃하면 "OUT" 으로 표시해 도구 앱들도 로그아웃되게 한다 */
        @JavascriptInterface public void clear() {
            SessionStore.put(MainActivity.this, "OUT");
        }

        /** 구글은 앱 안 WebView 로그인을 막으므로 기본 브라우저에서 진행 (우리 사이트 주소만 허용) */
        @JavascriptInterface public void openBrowser(String url0) {
            if (url0 == null || !url0.startsWith(BASE)) return;
            final String url = url0 + (url0.contains("?") ? "&" : "?") + "pkg=" + getPackageName();   // 로그인 뒤 이 앱으로 돌아오게 알려 준다
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
        if (i == null) return;
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
    private TtsBridge ttsBridge;

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
        /** SIM Note: 공유받은 파일 목록(JSON 배열 [{id,name,type,size}])을 돌려주고 비운다. 파일 내용은 화면이 같은 주소의 /Webtools/__share/<id> 로 가져간다 */
        @JavascriptInterface public String takeFiles() {
            synchronized (pendingFiles) {
                if (pendingFiles.isEmpty()) return "";
                JSONArray a = new JSONArray();
                for (JSONObject o : pendingFiles) a.put(o);
                pendingFiles.clear();
                return a.toString();
            }
        }
        @JavascriptInterface public void release(String id) {
            try {
                if (id == null || id.contains("/") || id.contains("..")) return;
                new File(new File(getCacheDir(), "share"), id).delete();
            } catch (Exception e) { /* 못 지워도 하루 뒤 자동 정리 */ }
        }
    }

    /** SIM Note 로 공유받은 파일(앱 캐시에 복사해 둔 것)을 화면이 fetch 로 가져가게 내준다 */
    private WebResourceResponse sharedFileResponse(Uri uri) {
        try {
            String id = uri.getLastPathSegment();
            if (id == null || id.contains("/") || id.contains("..")) return null;
            File f = new File(new File(getCacheDir(), "share"), id);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            h.put("Access-Control-Allow-Origin", "*");
            if (!f.isFile()) return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
            return new WebResourceResponse("application/octet-stream", null, 200, "OK", h, new FileInputStream(f));
        } catch (Exception e) { return null; }
    }

    /** 갤러리·파일·메신저 앱 등에서 'SIM Note'로 공유한 PDF·그림·문서를 앱 캐시에 복사하고 화면(note.html)에 알린다 */
    @SuppressWarnings("deprecation")
    private void handleShareFiles(Intent i, boolean one) {
        final List<Uri> uris = new ArrayList<>();
        if (one) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
        else { ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        final String fallbackType = i.getType();
        i.setAction(Intent.ACTION_MAIN);   // 화면 회전·재생성 때 같은 공유를 다시 처리하지 않도록
        if (uris.isEmpty()) { Toast.makeText(this, "공유받은 파일을 찾지 못했습니다", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "공유받은 파일을 여는 중…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<JSONObject> got = new ArrayList<>();
            File dir = new File(getCacheDir(), "share");
            dir.mkdirs();
            long now = System.currentTimeMillis();
            File[] old = dir.listFiles();
            if (old != null) for (File o : old) if (now - o.lastModified() > 24L * 3600 * 1000) o.delete();   // 하루 지난 임시 파일 정리
            int idx = 0;
            for (Uri u : uris) {
                try {
                    String name = "file", type = getContentResolver().getType(u);
                    try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                    } catch (Exception e) { String p = u.getLastPathSegment(); if (p != null) name = p; }
                    if (type == null || type.isEmpty()) type = fallbackType == null ? "" : fallbackType;
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9]", "") : "";
                    String id = now + "_" + (idx++) + (ext.isEmpty() ? "" : "." + ext);
                    File out = new File(dir, id);
                    long tot = 0;
                    boolean ok = false;
                    try (InputStream in = getContentResolver().openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                        if (in != null) {
                            byte[] buf = new byte[65536]; int n;
                            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); tot += n; if (tot > 80L * 1024 * 1024) throw new java.io.IOException("too big"); }
                            ok = true;
                        }
                    } catch (Exception e) { ok = false; }
                    if (!ok) { out.delete(); continue; }
                    JSONObject o = new JSONObject();
                    o.put("id", id); o.put("name", name); o.put("type", type); o.put("size", tot);
                    got.add(o);
                } catch (Exception e) { /* 읽지 못한 파일은 건너뜀 */ }
            }
            runOnUiThread(() -> {
                if (got.isEmpty()) { Toast.makeText(this, "공유받은 파일을 읽지 못했습니다 (80MB 이하 파일만 가능)", Toast.LENGTH_LONG).show(); return; }
                synchronized (pendingFiles) { pendingFiles.addAll(got); }
                if (web != null) web.evaluateJavascript("window.__simShareFiles&&window.__simShareFiles()", null);   // 화면이 이미 열려 있으면 바로 가져가게
            });
        }).start();
    }

    /** 연락처 앱 등에서 '공유'로 들어온 vCard(.vcf 파일 또는 텍스트)를 읽어 보관하고 화면에 알린다 */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent i) {
        if (i == null) return;
        String act = i.getAction();
        final boolean one = Intent.ACTION_SEND.equals(act), many = Intent.ACTION_SEND_MULTIPLE.equals(act);
        if (!one && !many) return;
        String cn = i.getComponent() == null ? "" : i.getComponent().getClassName();
        if (cn.endsWith("ShareNoteAlias")) { handleShareFiles(i, one); return; }   // PDF·그림·문서 → SIM Note (글자가 아닌 파일이라 따로 처리)
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
            if (text != null && (text.toUpperCase().contains("BEGIN:VCARD") || text.toUpperCase().contains("BEGIN:VCALENDAR") || "recipe".equals(key))) got.add(text);   // SIM Recipe 는 공유받은 링크(글)를 그대로 받는다
            runOnUiThread(() -> {
                if (got.isEmpty()) { Toast.makeText(this, "공유받은 내용을 읽지 못했습니다", Toast.LENGTH_SHORT).show(); return; }
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

    /** 어떤 화면을 열지 정한다: simtools://tool/<id> 이면 그 도구, 공유 받은 것이면 해당 도구, 그 밖엔 런처 */
    private void resolveTarget(Intent i) {
        String k = null;
        Uri d = i == null ? null : i.getData();
        if (d != null && "simtools".equals(d.getScheme()) && "tool".equals(d.getHost())) k = d.getLastPathSegment();
        else if (i != null && i.getComponent() != null) {
            String c = i.getComponent().getClassName();
            if (c.endsWith("ShareContactAlias")) k = "phone";
            else if (c.endsWith("ShareRecipeAlias")) k = "recipe";
            else if (c.endsWith("ShareCalendarAlias")) k = "calendar";
            else if (c.endsWith("ShareNoteAlias")) k = "note";
        }
        if (k != null && !SuiteExtra.valid(k)) k = null;
        key = k; isLauncher = k == null;
        startUrl = BASE + "Webtools/" + (k == null ? "index.html" : SuiteExtra.page(k));
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return true; }
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private static boolean isLauncherPage(Uri u) {
        String p = u.getPath() == null ? "" : u.getPath();
        return p.equals("/Webtools/") || p.equals("/Webtools") || p.equals("/Webtools/index.html");
    }

    /** 도구에서 나갈 때: 런처가 아래에 있으면 이 화면만 닫아 런처로 돌아가고, 도구가 맨 처음 화면이었다면(공유로 바로 열린 경우 등) 런처를 새로 연다 */
    private void openLauncher() {
        if (!isTaskRoot()) { finish(); return; }
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i != null) startActivity(i);
        finish();
    }

    private void showOffline(WebView v) {
        if (errorShown) return;
        errorShown = true;
        v.loadDataWithBaseURL(null, "<html><body style='background:#0f1117;color:#e8eaf0;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='font-size:42px'>📡</div><p style='margin:12px 0 18px'>인터넷에 연결되어 있지 않습니다</p>"
                + "<button onclick='location.href=\"" + startUrl + "\"' style='font-size:16px;padding:10px 22px;border-radius:10px;border:1px solid #2a2f3f;background:#1f2330;color:#e8eaf0'>다시 시도</button></body></html>",
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
        } else if (code == AppBridge.REQ_FIRST) {
            AppBridge.askExact(this);
        } else if (code == AppBridge.REQ_APP && appBridge != null) {
            appBridge.onPermResult(perms, res);
        } else if (code == REQ_GEO && geoCb != null) {
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        SuiteExtra.onResume(this, web);
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
        if (isLauncher) moveTaskToBack(true);
        else openLauncher();
    }

    @Override
    protected void onDestroy() {
        SuiteExtra.destroy(this);
        ScanExtra.destroy(this);
        if (appBridge != null) appBridge.destroy();
        if (ttsBridge != null) ttsBridge.destroy();
        web.destroy();
        super.onDestroy();
    }
}
