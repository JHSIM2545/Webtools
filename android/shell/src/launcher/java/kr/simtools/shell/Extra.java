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
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;

import java.util.HashMap;
import java.util.Map;

/** 런처 앱: 설치된 SIM 앱 확인·실행, 다른 SIM 앱 APK 내려받아 설치·제거 */
final class Extra {
    private Extra() {}

    static final String BASE = "https://github.com/JHSIM2545/Webtools/releases/download/apps-latest/";
    private static final Map<String, String> PKG = new HashMap<>();
    static {
        for (String id : new String[]{"simtv", "note", "phone", "aitel", "assistant", "calendar", "folder", "message", "kbo", "orchestra", "radio", "calc", "map"})
            PKG.put(id, "kr.simtools." + id);
        PKG.put("pad", "kr.simtools.simpad");
    }
    private static String file(String id) { return "simtools-" + id + ".apk"; }

    private static BroadcastReceiver receiver;
    private static final Map<Long, String> pending = new HashMap<>();

    static void attach(final Activity a, final WebView w) {
        w.addJavascriptInterface(new Bridge(a, w), "SimLauncher");
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                String tool = pending.remove(id);
                if (tool == null) return;
                DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                Uri u = dm.getUriForDownloadedFile(id);
                if (u == null) { notify(w, tool, "fail"); return; }
                notify(w, tool, "done");
                Intent inst = new Intent(Intent.ACTION_VIEW);
                inst.setDataAndType(u, "application/vnd.android.package-archive");
                inst.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(inst); } catch (Exception e) { notify(w, tool, "fail"); }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else a.registerReceiver(receiver, f);
    }

    static void onResume(Activity a, WebView w) {
        w.post(() -> w.evaluateJavascript("window.__simRefresh&&window.__simRefresh()", null));
    }

    static void destroy(Activity a) {
        try { if (receiver != null) a.unregisterReceiver(receiver); } catch (Exception e) { /* 무시 */ }
        receiver = null;
    }

    private static void notify(final WebView w, final String tool, final String st) {
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

        @JavascriptInterface public void open(String id) {
            String p = PKG.get(id); if (p == null) return;
            Intent i = a.getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) a.startActivity(i);
        }

        @JavascriptInterface public void install(final String id) {
            final String p = PKG.get(id); if (p == null) return;
            a.runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                        notify(w, id, "perm");
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                        return;
                    }
                    DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(BASE + file(id)));
                    r.setTitle("SIM 앱 내려받는 중");
                    r.setMimeType("application/vnd.android.package-archive");
                    r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    r.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file(id));
                    long did = dm.enqueue(r);
                    pending.put(did, id);
                    notify(w, id, "start");
                } catch (Exception e) {
                    notify(w, id, "fail");
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
