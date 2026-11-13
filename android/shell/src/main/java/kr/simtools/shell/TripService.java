package kr.simtools.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/**
 * 도착 시간에 맞춘 출발 알림: 앱을 꺼도 계속 돈다(알림창에 '대기 중' 표시가 뜬다).
 * 주기적으로 현재 위치에서 목적지까지 걸리는 시간(assistant-trip 함수)을 확인해, 출발해야 할 시각 10분 전에 알림을 낸다.
 */
public class TripService extends Service {
    private static final String ETA_URL = "https://gpqlximhoqdybcewdfde.supabase.co/functions/v1/assistant-trip";
    private static final String CH_RUN = "trip_run", CH_ALERT = "trip_alert";
    private static final int ID_RUN = 1201, ID_ALERT = 1202;

    private volatile boolean running = false;
    private Thread worker;
    private LocationManager lm;
    private volatile Location last;

    private final LocationListener ll = new LocationListener() {
        @Override public void onLocationChanged(Location l) { last = l; }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    private SharedPreferences prefs() { return getSharedPreferences("simtrip", MODE_PRIVATE); }

    private JSONObject loadTrip() {
        try { String s = prefs().getString("trip", ""); return s.isEmpty() ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    private void clearTrip() { prefs().edit().remove("trip").apply(); }

    private void channels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel run = new NotificationChannel(CH_RUN, "출발 알림 대기", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("도착 시간에 맞춰 출발 시각을 확인하는 동안 표시돼요");
        NotificationChannel al = new NotificationChannel(CH_ALERT, "출발 알림", NotificationManager.IMPORTANCE_HIGH);
        al.enableVibration(true);
        nm.createNotificationChannel(run);
        nm.createNotificationChannel(al);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private String hm(long t) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(t);
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return (h < 12 ? "오전 " : "오후 ") + (h % 12 == 0 ? 12 : h % 12) + "시" + (m > 0 ? " " + m + "분" : "");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("trip")) prefs().edit().putString("trip", intent.getStringExtra("trip")).apply();
        JSONObject t = loadTrip();
        if (t == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            channels();
            Notification n = new Notification.Builder(this, CH_RUN)
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setContentTitle("출발 알림 대기 중")
                    .setContentText(t.optString("name") + " · " + hm(t.optLong("arrive")) + " 도착")
                    .setContentIntent(openApp())
                    .setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(ID_RUN, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        startLocation();
        if (!running) {
            running = true;
            worker = new Thread(new Runnable() { @Override public void run() { loop(); } });
            worker.start();
        }
        return START_STICKY;
    }

    private void startLocation() {
        try {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) continue;
                lm.requestLocationUpdates(p, 60000L, 100f, ll, Looper.getMainLooper());
                Location k = lm.getLastKnownLocation(p);
                if (k != null && (last == null || k.getTime() > last.getTime())) last = k;
            }
        } catch (Exception e) { /* 권한 없음 등: 위치를 못 받으면 계속 기다린다 */ }
    }

    private int eta(JSONObject t, Location l) {
        try {
            JSONObject b = new JSONObject();
            b.put("a", "eta"); b.put("phone", t.optString("phone"));
            b.put("o", new JSONObject().put("lat", l.getLatitude()).put("lng", l.getLongitude()));
            b.put("d", new JSONObject().put("lat", t.optDouble("dlat")).put("lng", t.optDouble("dlng")));
            byte[] body = b.toString().getBytes("UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(ETA_URL).openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream(); os.write(body); os.close();
            if (c.getResponseCode() != 200) return -1;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new JSONObject(bo.toString("UTF-8")).optInt("sec", -1);
        } catch (Exception e) { return -1; }
    }

    private void alert(JSONObject t, int sec, boolean late) {
        long arrive = t.optLong("arrive"), leave = arrive - sec * 1000L;
        int min = Math.max(1, Math.round(sec / 60f));
        String body = t.optString("name") + "까지 약 " + min + "분. " + hm(arrive) + " 도착하려면 "
                + (late ? "지금 출발하세요." : hm(leave) + "에 출발하세요 (약 " + Math.max(1, Math.round((leave - System.currentTimeMillis()) / 60000f)) + "분 뒤).");
        Notification n = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("🚗 곧 출발할 시간이에요")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp())
                .setAutoCancel(true).build();
        try { ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(ID_ALERT, n); } catch (Exception e) { /* 알림 권한 없음 */ }
    }

    private void nap(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < end) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private void loop() {
        try {
            while (running) {
                JSONObject t = loadTrip();
                if (t == null) break;
                long now = System.currentTimeMillis(), arrive = t.optLong("arrive");
                if (now > arrive + 30 * 60000L) { clearTrip(); break; }
                Location l = last;
                if (l == null) { nap(20000); continue; }
                int sec = eta(t, l);
                if (sec <= 0) { nap(30000); continue; }
                long leave = arrive - sec * 1000L, notifyAt = leave - t.optInt("lead", 10) * 60000L, rem = notifyAt - System.currentTimeMillis();
                if (rem <= 30000L) { alert(t, sec, System.currentTimeMillis() >= leave); clearTrip(); break; }
                nap(rem > 60 * 60000L ? 5 * 60000L : rem > 20 * 60000L ? 2 * 60000L : 60000L);
            }
        } finally {
            running = false;
            stopForeground(true);
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        running = false;
        try { if (lm != null) lm.removeUpdates(ll); } catch (Exception e) { /* 무시 */ }
        super.onDestroy();
    }
}
