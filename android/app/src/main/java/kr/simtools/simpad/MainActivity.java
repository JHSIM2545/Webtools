package kr.simtools.simpad;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** SIM PAD 화면(pad.html)을 WebView 로 띄우고, 입력을 블루투스 HID(마우스·키보드)로 내보낸다. */
public class MainActivity extends Activity {
    static final String URL = "https://jhsim2545.github.io/Webtools/pad.html?app=bt";
    static final String FALLBACK = "file:///android_asset/pad.html?app=bt";

    private WebView web;
    private HidController hid;
    private boolean fellBack = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#171a23"));
        getWindow().setNavigationBarColor(Color.parseColor("#0f1117"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1117"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(false);

        hid = new HidController(this, this::pushState);
        web.addJavascriptInterface(new Bridge(), "SimPadBT");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                return !(u.startsWith("https://jhsim2545.github.io/") || u.startsWith("file:///android_asset/"));
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) fallback(v);
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame() && e.getStatusCode() >= 400) fallback(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pushState();
            }
        });

        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
            } else hid.start();
        } else hid.start();

        web.loadUrl(URL);
    }

    private void fallback(WebView v) {
        if (fellBack) return;
        fellBack = true;
        v.post(() -> v.loadUrl(FALLBACK));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        hid.start();
        pushState();
    }

    @Override
    public void onBackPressed() {
        // 뒤로가기로 앱이 닫히지 않게 (차량 연결 유지)
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        hid.stop();
        web.destroy();
        super.onDestroy();
    }

    /** 상태가 바뀌면 화면(JS)에 알린다 */
    void pushState() {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript("window.__btUpdate&&window.__btUpdate()", null));
    }

    /** JS → 네이티브 (pad.html 의 window.SimPadBT) */
    class Bridge {
        @JavascriptInterface public String state() { return hid.stateJson(); }
        @JavascriptInterface public void send(String ev, String json) { hid.handle(ev, json); }
        @JavascriptInterface public void connect(String addr) { hid.connect(addr); }
        @JavascriptInterface public void disconnect(String addr) { hid.disconnect(addr); }
        @JavascriptInterface public void discoverable() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                    i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                    startActivity(i);
                } catch (Exception e) { /* 무시 */ }
            });
        }
        @JavascriptInterface public void btSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception e) { /* 무시 */ }
            });
        }
    }
}
