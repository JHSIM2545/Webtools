package kr.simtools.homebot;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 앱 안에서 새 APK 를 내려받아 바로 설치 화면까지 띄운다(시스템 확인 1회는 필요). */
final class Installer {
    private static final String ACTION = "kr.simtools.homebot.INSTALL_RESULT";
    private final Activity a;
    private final WebView web;
    private volatile boolean busy = false;

    private final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(confirm);
                    js(100, "confirm", "설치 화면에서 '설치' 또는 '업데이트'를 눌러 주세요");
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                busy = false;
                js(100, "done", "설치 완료");
            } else {
                busy = false;
                js(0, "fail", "설치 실패(" + st + ") " + (msg == null ? "" : msg));
            }
        }
    };

    Installer(Activity a, WebView web) {
        this.a = a;
        this.web = web;
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(rcv, f);
    }

    void destroy() {
        try { a.unregisterReceiver(rcv); } catch (Exception ignore) { }
    }

    private void js(final int pct, final String state, final String msg) {
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.__upd&&window.__upd(" + pct + ",'" + state + "'," + JSONObject.quote(msg) + ")", null);
            }
        });
    }

    void start(final String url) {
        if (busy) { js(0, "busy", "이미 진행 중이에요"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            js(0, "perm", "설치 허용이 필요해요. 열린 설정에서 '이 출처 허용'을 켠 뒤 돌아와 다시 업그레이드를 눌러 주세요");
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File f = download(url);
                    install(f);
                } catch (Exception e) {
                    busy = false;
                    js(0, "fail", "내려받기/설치 실패: " + e.getMessage());
                }
            }
        }).start();
    }

    private File download(String url) throws Exception {
        File out = new File(a.getCacheDir(), "update.apk");
        if (out.exists()) out.delete();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        long got = 0;
        int last = -1, n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
            got += n;
            if (total > 0) {
                int p = (int) (got * 100 / total);
                if (p != last && p % 5 == 0) { last = p; js(p, "down", "내려받는 중 " + p + "%"); }
            }
        }
        os.close();
        in.close();
        c.disconnect();
        if (out.length() < 100000) throw new Exception("파일이 너무 작아요");
        return out;
    }

    private void install(File f) throws Exception {
        js(100, "installing", "설치 준비 중…");
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setSize(f.length());
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        OutputStream os = s.openWrite("homebot.apk", 0, f.length());
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        s.fsync(os);
        in.close();
        os.close();
        Intent it = new Intent(ACTION).setPackage(a.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pe = PendingIntent.getBroadcast(a, id, it, flags);
        s.commit(pe.getIntentSender());
        s.close();
    }
}
