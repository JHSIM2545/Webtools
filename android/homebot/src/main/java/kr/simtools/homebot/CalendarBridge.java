package kr.simtools.homebot;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/** 휴대폰 일정표(캘린더) 읽기 전용. 반복 일정도 펼쳐서 기간 내 일정만 돌려준다. */
final class CalendarBridge {
    static final int REQ_CAL = 93;
    private final Activity a;

    CalendarBridge(Activity a) { this.a = a; }

    private boolean has() { return a.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED; }

    @JavascriptInterface
    public boolean perm() { return has(); }

    @JavascriptInterface
    public void request() {
        a.runOnUiThread(new Runnable() {
            @Override public void run() { a.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_CAL); }
        });
    }

    /** 결과: {"perm":bool,"items":[{"title","start","end","allDay","loc"}]}  (start/end 는 epoch ms, 종일 일정은 UTC 자정 기준) */
    @JavascriptInterface
    public String events(double fromMs, double toMs) {
        try {
            if (!has()) return "{\"perm\":false,\"items\":[]}";
            long from = (long) fromMs, to = (long) toMs;
            if (to <= from || to - from > 100L * 86400000L) to = from + 7L * 86400000L;
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(b, from - 86400000L);   // 종일 일정(UTC) 경계 보정
            android.content.ContentUris.appendId(b, to + 86400000L);
            Cursor c = a.getContentResolver().query(b.build(),
                    new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.STATUS},
                    CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC");
            JSONArray arr = new JSONArray();
            if (c != null) {
                try {
                    while (c.moveToNext() && arr.length() < 60) {
                        int status = c.getInt(5);
                        if (status == CalendarContract.Events.STATUS_CANCELED) continue;
                        long bg = c.getLong(1), en = c.getLong(2);
                        boolean all = c.getInt(3) == 1;
                        if (en <= from || bg >= to) {
                            if (!all) continue;
                        }
                        JSONObject o = new JSONObject();
                        String t = c.getString(0);
                        o.put("title", t == null || t.trim().isEmpty() ? "(제목 없음)" : t);
                        o.put("start", bg);
                        o.put("end", en);
                        o.put("allDay", all);
                        String loc = c.getString(4);
                        if (loc != null && !loc.trim().isEmpty()) o.put("loc", loc);
                        arr.put(o);
                    }
                } finally { c.close(); }
            }
            JSONObject r = new JSONObject();
            r.put("perm", true);
            r.put("items", arr);
            return r.toString();
        } catch (Exception e) {
            return "{\"perm\":true,\"items\":[],\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }
}
