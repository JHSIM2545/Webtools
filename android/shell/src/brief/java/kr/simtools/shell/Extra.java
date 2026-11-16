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

    static void attach(Activity a, WebView w) { w.addJavascriptInterface(new Bridge(a, w), "SimBrief"); }
    static void onResume(Activity a, WebView w) {}
    static void destroy(Activity a) {}

    private static final class Bridge {
        private final Activity a;
        private final WebView w;
        Bridge(Activity a, WebView w) { this.a = a; this.w = w; }

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

        /** 마지막 재생 결과와 현재 볼륨 상태 (JSON) — 소리가 안 들릴 때 원인 확인용 */
        @JavascriptInterface public String diag() {
            try {
                android.media.AudioManager am = (android.media.AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
                JSONObject o = new JSONObject();
                o.put("last", BriefReceiver.LAST);
                o.put("music", am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC));
                o.put("alarm", am.getStreamVolume(android.media.AudioManager.STREAM_ALARM));
                o.put("notif", am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION));
                o.put("voiceSize", BriefReceiver.voiceFile(a).exists() ? BriefReceiver.voiceFile(a).length() : 0);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        /** 현재 위치 찾기(웹뷰 위치 대신 기기에서 직접). 결과는 window.__briefLoc({lat,lon} 또는 {err:'perm'|'off'|'none'}) 로 돌려준다 */
        @JavascriptInterface public void locate() {
            final boolean fine = a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            final boolean coarse = a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) {
                a.runOnUiThread(new Runnable() { @Override public void run() { a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 93); } });
                reply("{\"err\":\"perm\"}"); return;
            }
            try {
                final android.location.LocationManager lm = (android.location.LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
                android.location.Location best = null;
                for (String p : lm.getProviders(true)) {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
                if (best != null && System.currentTimeMillis() - best.getTime() < 30L * 60000L) { reply(fix(best)); return; }
                final String prov = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ? android.location.LocationManager.NETWORK_PROVIDER
                        : lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ? android.location.LocationManager.GPS_PROVIDER : null;
                if (prov == null) { reply(best != null ? fix(best) : "{\"err\":\"off\"}"); return; }
                final android.location.Location fb = best;
                final boolean[] done = {false};
                final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                final android.location.LocationListener ll = new android.location.LocationListener() {
                    @Override public void onLocationChanged(android.location.Location l) { if (done[0]) return; done[0] = true; try { lm.removeUpdates(this); } catch (Exception ignore) {} reply(fix(l)); }
                    @Override public void onStatusChanged(String s, int i, android.os.Bundle b) {}
                    @Override public void onProviderEnabled(String s) {}
                    @Override public void onProviderDisabled(String s) {}
                };
                h.post(new Runnable() { @Override public void run() {
                    try { lm.requestSingleUpdate(prov, ll, android.os.Looper.getMainLooper()); } catch (Exception e) { if (!done[0]) { done[0] = true; reply(fb != null ? fix(fb) : "{\"err\":\"none\"}"); } }
                } });
                h.postDelayed(new Runnable() { @Override public void run() {
                    if (done[0]) return; done[0] = true; try { lm.removeUpdates(ll); } catch (Exception ignore) {}
                    reply(fb != null ? fix(fb) : "{\"err\":\"none\"}");
                } }, 12000);
            } catch (Exception e) { reply("{\"err\":\"none\"}"); }
        }
        private static String fix(android.location.Location l) { return "{\"lat\":" + l.getLatitude() + ",\"lon\":" + l.getLongitude() + "}"; }
        private void reply(final String json) {
            a.runOnUiThread(new Runnable() { @Override public void run() { try { w.evaluateJavascript("window.__briefLoc&&window.__briefLoc(" + json + ")", null); } catch (Exception ignore) {} } });
        }

        @JavascriptInterface public boolean hasSound() { return BriefReceiver.voiceFile(a).exists(); }
        /** 알림음 미리듣기 (알림 때와 같은 방식으로 재생) */
        @JavascriptInterface public void preview(final String id) { new Thread(new Runnable() { @Override public void run() { BriefReceiver.play(a, id, true); } }).start(); }

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
