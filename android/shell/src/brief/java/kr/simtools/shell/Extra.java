package kr.simtools.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

/** SIM Brief 앱 전용(다른 앱에는 들어가지 않음): 화면(웹)이 브리핑 시각·알림을 맞추고 기기 달력을 읽는 창구 "SimBrief". */
final class Extra {
    private Extra() {}

    static void attach(Activity a, WebView w) { w.addJavascriptInterface(new Bridge(a), "SimBrief"); }
    static void onResume(Activity a, WebView w) {}
    static void destroy(Activity a) {}

    private static final class Bridge {
        private final Activity a;
        Bridge(Activity a) { this.a = a; }

        /** 브리핑 설정 저장 + 알람 맞추기. json: {times:[{t:"07:30",on:true}], sound, cats:[], endpoint, calDevice, calSim} */
        @JavascriptInterface public boolean schedule(String json) {
            try {
                new JSONObject(json);
                BriefReceiver.prefs(a).edit().putString("cfg", json).apply();
                BriefReceiver.scheduleAll(a);
                return true;
            } catch (Exception e) { return false; }
        }

        /** 화면이 마지막으로 받은 헤드라인·오늘 심스케줄을 저장 (알림에서 네트워크가 안 될 때 쓴다) */
        @JavascriptInterface public void cache(String json) {
            try { new JSONObject(json); BriefReceiver.prefs(a).edit().putString("cache", json).apply(); } catch (Exception ignore) {}
        }

        /** AI로 만든 알림음(WAV, base64)을 이 앱 안에 저장 — 알림 때 이 소리를 재생한다 */
        @JavascriptInterface public boolean saveSound(String b64) {
            try {
                byte[] d = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (d.length < 1000 || d.length > 2_000_000) return false;
                java.io.FileOutputStream o = new java.io.FileOutputStream(BriefReceiver.voiceFile(a));
                o.write(d); o.close();
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean hasSound() { return BriefReceiver.voiceFile(a).exists(); }
        @JavascriptInterface public void clearSound() { BriefReceiver.voiceFile(a).delete(); }

        @JavascriptInterface public void test() {
            new Thread(new Runnable() { @Override public void run() { BriefReceiver.post(a, "테스트"); } }).start();
        }

        /** 알림 권한(13+) */
        @JavascriptInterface public boolean hasNotif() {
            return Build.VERSION.SDK_INT < 33 || a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public void requestNotif() {
            if (Build.VERSION.SDK_INT >= 33) a.runOnUiThread(new Runnable() { @Override public void run() { a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 92); } });
        }

        /** 정확한 시각 알람 허용 여부 (12+ 에서 '알람 및 리마인더' 허용이 필요) */
        @JavascriptInterface public boolean canExact() {
            if (Build.VERSION.SDK_INT < 31) return true;
            AlarmManager am = (AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }
        @JavascriptInterface public void openExact() {
            try {
                Intent i = Build.VERSION.SDK_INT >= 31 ? new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.getPackageName()))
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()));
                a.startActivity(i);
            } catch (Exception ignore) {}
        }

        /** 기기 달력 */
        @JavascriptInterface public boolean hasCalendar() { return BriefReceiver.hasCalendar(a); }
        @JavascriptInterface public void requestCalendar() {
            a.runOnUiThread(new Runnable() { @Override public void run() { a.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 91); } });
        }
        /** [from, to) 밀리초 구간의 기기 달력 일정 (JSON 배열) */
        @JavascriptInterface public String calendar(double from, double to) { return BriefReceiver.calendar(a, (long) from, (long) to).toString(); }
    }
}
