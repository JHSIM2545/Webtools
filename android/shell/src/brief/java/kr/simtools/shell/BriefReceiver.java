package kr.simtools.shell;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * SIM Brief 앱 전용: 정해진 시각마다 브리핑 알림을 띄운다.
 * 설정(시각·소리·분야)은 화면(웹)이 SimBrief.schedule() 로 넘겨 SharedPreferences 에 저장하고, 여기서 알람을 맞춘다.
 * 알림을 누르면 앱이 열려 브리핑 화면(카드 뉴스)으로 들어간다.
 */
public final class BriefReceiver extends BroadcastReceiver {
    static final String ACTION = "kr.simtools.brief.ALARM";
    static final String PREFS = "simbrief";
    private static final int RC_BASE = 7000, RC_MAX = 24;
    private static final String CH_SND = "brief_snd_v1", CH_QUIET = "brief_quiet_v1";
    static final String[][] CATS = {{"politics", "정치"}, {"economy", "경제"}, {"society", "사회"}, {"culture", "문화"}, {"sports", "스포츠"}};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static JSONObject cfg(Context c) {
        try { return new JSONObject(prefs(c).getString("cfg", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    @Override
    public void onReceive(final Context ctx, final Intent in) {
        final String act = in == null ? null : in.getAction();
        if (act == null) return;
        final PendingResult pr = goAsync();
        final String label = in.getStringExtra("label");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    scheduleAll(ctx);                         // 다음 알람을 먼저 다시 맞춘다 (재부팅·앱 업데이트 후에도 이 경로)
                    if (ACTION.equals(act)) post(ctx, label == null ? "" : label);
                } catch (Throwable ignore) {
                } finally { pr.finish(); }
            }
        }).start();
    }

    /** 켜져 있는 모든 시각의 다음 알람을 맞춘다 (이전 알람은 모두 취소) */
    static void scheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (int i = 0; i < RC_MAX; i++) am.cancel(pi(c, i, ""));
        JSONArray times = cfg(c).optJSONArray("times");
        if (times == null) return;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        for (int i = 0; i < times.length() && i < RC_MAX; i++) {
            JSONObject t = times.optJSONObject(i);
            if (t == null || !t.optBoolean("on", true)) continue;
            String[] hm = t.optString("t", "").split(":");
            if (hm.length != 2) continue;
            int h, m;
            try { h = Integer.parseInt(hm[0].trim()); m = Integer.parseInt(hm[1].trim()); } catch (Exception e) { continue; }
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1);
            PendingIntent p = pi(c, i, String.format(Locale.KOREA, "%02d:%02d", h, m));
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), p);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), p);
            } catch (SecurityException e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), p);
            }
        }
    }

    private static PendingIntent pi(Context c, int idx, String label) {
        Intent i = new Intent(c, BriefReceiver.class).setAction(ACTION).putExtra("label", label);
        return PendingIntent.getBroadcast(c, RC_BASE + idx, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ── 기기 달력(오늘 이후 from~to, 밀리초) ── */
    static boolean hasCalendar(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    static JSONArray calendar(Context c, long from, long to) {
        JSONArray out = new JSONArray();
        if (!hasCalendar(c)) return out;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        Cursor cu = null;
        try {
            cu = c.getContentResolver().query(b.build(),
                    new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION},
                    null, null, CalendarContract.Instances.BEGIN + " ASC");
            while (cu != null && cu.moveToNext() && out.length() < 60) {
                JSONObject o = new JSONObject();
                o.put("title", cu.getString(0) == null ? "" : cu.getString(0));
                o.put("start", cu.getLong(1));
                o.put("end", cu.getLong(2));
                o.put("allDay", cu.getInt(3) == 1);
                o.put("loc", cu.getString(4) == null ? "" : cu.getString(4));
                out.put(o);
            }
        } catch (Exception ignore) {
        } finally { if (cu != null) cu.close(); }
        return out;
    }

    /* ── 뉴스 가져오기 (실패하면 화면이 마지막으로 저장해 둔 헤드라인) ── */
    private static JSONObject fetchNews(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return null;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(endpoint + (endpoint.contains("?") ? "&" : "?") + "cat=all&n=2").openConnection();
            h.setConnectTimeout(4000); h.setReadTimeout(6000);
            if (h.getResponseCode() != 200) return null;
            InputStream is = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject j = new JSONObject(bo.toString("UTF-8"));
            return j.optJSONObject("news");
        } catch (Exception e) { return null; }
        finally { if (h != null) h.disconnect(); }
    }

    private static String[] wxInfo(int c) {
        if (c == 0) return new String[]{"☀️", "맑음"};
        if (c == 1) return new String[]{"🌤️", "대체로 맑음"};
        if (c == 2) return new String[]{"⛅", "구름 조금"};
        if (c == 3) return new String[]{"☁️", "흐림"};
        if (c == 45 || c == 48) return new String[]{"🌫️", "안개"};
        if (c >= 51 && c <= 57) return new String[]{"🌦️", "이슬비"};
        if (c >= 61 && c <= 67) return new String[]{"🌧️", "비"};
        if (c >= 71 && c <= 77) return new String[]{"❄️", "눈"};
        if (c >= 80 && c <= 82) return new String[]{"🌧️", "소나기"};
        if (c == 85 || c == 86) return new String[]{"❄️", "눈 소나기"};
        if (c >= 95) return new String[]{"⛈️", "뇌우"};
        return new String[]{"🌡️", "날씨"};
    }

    /** 오늘 예보 한 줄 (Open-Meteo). 실패하면 null → 화면이 저장해 둔 문구를 쓴다 */
    private static String fetchWeather(JSONObject w) {
        HttpURLConnection h = null;
        try {
            String u = "https://api.open-meteo.com/v1/forecast?latitude=" + w.optDouble("lat") + "&longitude=" + w.optDouble("lon")
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Asia%2FSeoul&forecast_days=1";
            h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(3500); h.setReadTimeout(3500);
            if (h.getResponseCode() != 200) return null;
            InputStream is = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            JSONObject d = new JSONObject(bo.toString("UTF-8")).getJSONObject("daily");
            String[] i = wxInfo(d.getJSONArray("weather_code").getInt(0));
            String rain = d.getJSONArray("precipitation_probability_max").isNull(0) ? "-" : String.valueOf(d.getJSONArray("precipitation_probability_max").getInt(0));
            return i[0] + " " + w.optString("name", "") + " " + i[1] + " · " + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°/"
                    + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "° · 강수 " + rain + "%";
        } catch (Exception e) { return null; }
        finally { if (h != null) h.disconnect(); }
    }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date()); }

    private static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH_SND) == null) {
            NotificationChannel ch = new NotificationChannel(CH_SND, "SIM Brief (심툴즈 알림음)", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(Uri.parse("android.resource://" + c.getPackageName() + "/raw/brief_chime"),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_QUIET) == null) {
            NotificationChannel ch = new NotificationChannel(CH_QUIET, "SIM Brief", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    /** 브리핑 알림을 띄운다 (설정 화면의 '지금 테스트'도 이 함수를 쓴다) */
    static void post(Context c, String label) {
        JSONObject cfg = cfg(c);
        boolean sound = cfg.optBoolean("sound", true);
        JSONArray want = cfg.optJSONArray("cats");
        JSONObject cache = null;
        try { cache = new JSONObject(prefs(c).getString("cache", "{}")); } catch (Exception ignore) {}
        // 일기예보는 뉴스와 동시에 받아 알림이 늦어지지 않게 한다
        final String[] wx = {null};
        final JSONObject wc = cfg.optJSONObject("weather");
        Thread wt = null;
        if (wc != null) { wt = new Thread(new Runnable() { @Override public void run() { wx[0] = fetchWeather(wc); } }); wt.start(); }
        JSONObject news = fetchNews(cfg.optString("endpoint", ""));
        if (wt != null) { try { wt.join(4000); } catch (InterruptedException ignore) {} }
        if (news == null && cache != null) news = cache.optJSONObject("news");

        StringBuilder big = new StringBuilder();
        String first = "";
        String wline = wx[0];
        if ((wline == null || wline.isEmpty()) && wc != null && cache != null && today().equals(cache.optString("date"))) wline = cache.optString("weather", "");
        if (wline != null && !wline.isEmpty()) { big.append(wline).append('\n'); first = wline; }
        // 일정: 기기 달력(오늘) + 화면이 오늘 저장해 둔 심스케줄
        StringBuilder sc = new StringBuilder();
        int cnt = 0;
        if (cfg.optBoolean("calDevice", true) && hasCalendar(c)) {
            Calendar s = Calendar.getInstance(); s.set(Calendar.HOUR_OF_DAY, 0); s.set(Calendar.MINUTE, 0); s.set(Calendar.SECOND, 0); s.set(Calendar.MILLISECOND, 0);
            JSONArray ev = calendar(c, s.getTimeInMillis(), s.getTimeInMillis() + 86400000L);
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.KOREA);
            for (int i = 0; i < ev.length(); i++) {
                JSONObject o = ev.optJSONObject(i); if (o == null) continue;
                cnt++;
                if (cnt <= 3) sc.append("· ").append(o.optBoolean("allDay") ? "종일" : f.format(new Date(o.optLong("start")))).append(' ').append(o.optString("title")).append('\n');
            }
        }
        if (cfg.optBoolean("calSim", true) && cache != null && today().equals(cache.optString("date"))) {
            JSONArray a = cache.optJSONArray("sched");
            for (int i = 0; a != null && i < a.length(); i++) { cnt++; if (cnt <= 5) sc.append("· ").append(a.optString(i)).append('\n'); }
        }
        if (cnt > 0) {
            if (big.length() > 0) big.append('\n');
            big.append("📅 오늘 일정 ").append(cnt).append("건\n").append(sc);
            if (first.isEmpty()) first = "오늘 일정 " + cnt + "건";
        }
        for (String[] cat : CATS) {
            if (want != null) { boolean on = false; for (int i = 0; i < want.length(); i++) if (cat[0].equals(want.optString(i))) on = true; if (!on) continue; }
            JSONArray a = news == null ? null : news.optJSONArray(cat[0]);
            if (a == null || a.length() == 0) continue;
            JSONObject o = a.optJSONObject(0);
            if (o == null) continue;
            if (big.length() > 0) big.append('\n');
            big.append('[').append(cat[1]).append("] ").append(o.optString("title"));
            if (first.isEmpty()) first = "[" + cat[1] + "] " + o.optString("title");
        }
        if (big.length() == 0) { big.append("누르면 오늘의 브리핑을 볼 수 있어요."); first = "오늘의 브리핑이 준비됐어요"; }

        channels(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open != null) open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 1, open == null ? new Intent() : open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(c, CH_QUIET) : new android.app.Notification.Builder(c);
        nb.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("SIM Brief" + (label.isEmpty() ? "" : " · " + label + " 브리핑"))
                .setContentText(first)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(big.toString().trim()))
                .setContentIntent(tap).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) {
            nb.setPriority(android.app.Notification.PRIORITY_HIGH);
        }
        try { nm.notify(7001, nb.build()); } catch (Exception ignore) {}
        if (sound) play(c, cfg.optString("soundId", ""), false);
    }

    /** "심툴즈" 알림음: 폰의 한국어 음성(TTS)을 높은 음정으로 읽어 아이 목소리 느낌을 낸다. 음성을 못 쓰면 3음 차임으로 대신한다. */
    static java.io.File voiceFile(Context c) { return new java.io.File(c.getFilesDir(), "brief_voice.wav"); }

    /** 마지막 재생 결과 (화면의 알림음 점검 표시용): ok / 실패 사유 */
    static volatile String LAST = "";

    /** 설정에서 고른 알림음을 재생: voice(저장해 둔 아이 목소리) · tts(폰 음성) · 그 밖에는 내장 멜로디(brief_s_<id>).
     *  preview=true 는 설정 화면 미리듣기(미디어 볼륨), false 는 실제 알림(알람 볼륨 — 무음·진동 모드에서도 들리도록) */
    static void play(Context c, String id, boolean preview) {
        if (id == null || id.isEmpty()) id = voiceFile(c).exists() ? "voice" : "tts";
        int usage = preview ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ALARM;
        String err;
        if ("tts".equals(id)) { sayName(c, usage); return; }
        if ("voice".equals(id) && voiceFile(c).exists() && voiceFile(c).length() > 1000) err = playMp(c, voiceFile(c), 0, usage);
        else {
            int res = c.getResources().getIdentifier("brief_s_" + id, "raw", c.getPackageName());
            err = res == 0 ? "소리 파일 없음(" + id + ")" : playMp(c, null, res, usage);
        }
        LAST = err == null ? "ok" : "실패: " + err;
        if (err != null) sayName(c, usage);
    }

    /** 성공하면 null, 실패하면 사유 */
    private static String playMp(Context c, java.io.File f, int resId, int usage) {
        MediaPlayer mp = null;
        final String[] err = {null};
        try {
            final CountDownLatch end = new CountDownLatch(1);
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            if (f != null) mp.setDataSource(f.getAbsolutePath());
            else {
                android.content.res.AssetFileDescriptor afd = c.getResources().openRawResourceFd(resId);
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) { err[0] = "재생 오류 " + what + "/" + extra; end.countDown(); return true; }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { @Override public void onCompletion(MediaPlayer m) { end.countDown(); } });
            mp.prepare();
            mp.setVolume(1f, 1f);
            mp.start();
            end.await(6, TimeUnit.SECONDS);
            return err[0];
        } catch (Exception e) { return String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
        finally { try { if (mp != null) mp.release(); } catch (Exception ignore) {} }
    }

    private static void sayName(final Context c, final int usage) {
        final CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
        final boolean[] ok = {false};
        final TextToSpeech[] tts = new TextToSpeech[1];
        try {
            tts[0] = new TextToSpeech(c.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int st) { ok[0] = st == TextToSpeech.SUCCESS; ready.countDown(); }
            });
            if (!ready.await(4, TimeUnit.SECONDS) || !ok[0]) throw new Exception("tts");
            TextToSpeech t = tts[0];
            int lang = t.setLanguage(Locale.KOREAN);
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) throw new Exception("lang");
            t.setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            t.setPitch(2.0f);            // 높은 음정 = 아이 목소리 느낌
            t.setSpeechRate(1.0f);
            t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { done.countDown(); }
                @Override public void onError(String id) { done.countDown(); }
            });
            if (t.speak("심툴즈", TextToSpeech.QUEUE_FLUSH, null, "simbrief") != TextToSpeech.SUCCESS) throw new Exception("speak");
            done.await(4, TimeUnit.SECONDS);
            LAST = (LAST == null || LAST.isEmpty() || LAST.startsWith("ok") ? "" : LAST + " → ") + "ok(폰 음성)";
        } catch (Exception e) {
            LAST = LAST + " / 폰 음성도 실패: " + e.getMessage();
            chime(c, usage);
        } finally {
            try { if (tts[0] != null) tts[0].shutdown(); } catch (Exception ignore) {}
        }
    }

    private static void chime(Context c, int usage) {
        int res = c.getResources().getIdentifier("brief_chime", "raw", c.getPackageName());
        if (res != 0) playMp(c, null, res, usage);
    }
}
