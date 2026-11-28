package kr.simtools.suite;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * SIM Assistant 카톡 도우미의 보관함. 모두 이 폰 안에만 둔다(계정 동기화 대상이 아님).
 * 대화 내용은 사용자가 켠 방만 저장하고(방마다 200개, 3일), 켜지 않은 방은 이름만 목록에 남긴다.
 * 방은 알림의 방 번호(tag)로 구분해, 이름이 같은 방에 잘못 보내지 않게 한다.
 */
final class KakaoStore {
    private KakaoStore() {}

    private static final int MAX_MSG = 200, MAX_ROOM = 100;
    private static final long KEEP_MS = 3L * 24 * 3600 * 1000;

    /** 방별 답장 창구(알림의 '답장' 버튼). 앱 프로세스가 살아 있는 동안만 유효 */
    private static final Map<String, Reply> REPLIES = new HashMap<>();

    static final class Reply {
        final PendingIntent intent; final RemoteInput[] inputs;
        Reply(PendingIntent i, RemoteInput[] r) { intent = i; inputs = r; }
    }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("simkakao", Context.MODE_PRIVATE); }

    private static JSONObject rooms(Context c) {
        try { return new JSONObject(sp(c).getString("rooms", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static JSONArray msgs(Context c, String id) {
        try { return new JSONArray(sp(c).getString("m:" + id, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static JSONArray fresh(JSONArray a) {
        JSONArray out = new JSONArray();
        long min = System.currentTimeMillis() - KEEP_MS;
        for (int i = Math.max(0, a.length() - MAX_MSG); i < a.length(); i++) {
            JSONObject m = a.optJSONObject(i);
            if (m != null && m.optLong("at") >= min) out.put(m);
        }
        return out;
    }

    static synchronized void reply(String id, Reply r) { REPLIES.put(id, r); }

    static synchronized void add(Context c, String id, String name, boolean named, boolean group, String sender, String text, long at, String dbg) {
        try {
            JSONObject all = rooms(c), r = all.optJSONObject(id);
            if (r == null) r = new JSONObject().put("on", false).put("read", 0);
            // 방 이름이 담긴 알림으로 한 번 알아낸 이름은, 이름 칸이 없는 알림(보낸 사람 이름만 있는 것)으로 덮어쓰지 않는다
            if (named || !r.optBoolean("named")) r.put("name", name).put("group", group).put("named", named);
            r.put("dbg", dbg).put("last", Math.max(at, r.optLong("last")));
            all.put(id, r);
            if (all.length() > MAX_ROOM) {   // 오래된 '꺼진 방'부터 정리
                String old = null; long t = Long.MAX_VALUE;
                for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String k = it.next(); JSONObject o = all.getJSONObject(k);
                    if (!o.optBoolean("on") && o.optLong("last") < t) { t = o.optLong("last"); old = k; }
                }
                if (old != null) all.remove(old);
            }
            SharedPreferences.Editor ed = sp(c).edit().putString("rooms", all.toString());
            if (r.optBoolean("on")) {
                JSONArray a = fresh(msgs(c, id));
                for (int i = a.length() - 1; i >= 0 && i >= a.length() - 20; i--) {   // 같은 알림이 다시 올라온 것은 건너뜀
                    JSONObject m = a.getJSONObject(i);
                    if (m.optLong("at") == at && m.optString("s").equals(sender) && m.optString("t").equals(text)) { ed.apply(); return; }
                }
                a.put(new JSONObject().put("at", at).put("s", sender).put("t", text.length() > 1000 ? text.substring(0, 1000) : text));
                ed.putString("m:" + id, fresh(a).toString());
            }
            ed.apply();
        } catch (Exception e) { /* 한 건 저장 실패는 건너뜀 */ }
    }

    /** [{id,name,group,on,last,count,unread,canReply}] */
    static synchronized String list(Context c) {
        JSONArray out = new JSONArray();
        try {
            JSONObject all = rooms(c);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next(); JSONObject r = all.getJSONObject(id);
                JSONArray a = r.optBoolean("on") ? fresh(msgs(c, id)) : new JSONArray();
                int unread = 0;
                for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).optLong("at") > r.optLong("read")) unread++;
                out.put(new JSONObject(r.toString()).put("id", id).put("count", a.length()).put("unread", unread).put("canReply", REPLIES.containsKey(id)));
            }
        } catch (Exception e) { /* 읽은 만큼만 */ }
        return out.toString();
    }

    static synchronized void setOn(Context c, String id, boolean on) {
        try {
            JSONObject all = rooms(c), r = all.optJSONObject(id);
            if (r == null) return;
            r.put("on", on);
            SharedPreferences.Editor ed = sp(c).edit().putString("rooms", all.toString());
            if (!on) ed.remove("m:" + id);   // 끄면 저장해 둔 대화도 지운다
            ed.apply();
        } catch (Exception e) { /* 그대로 둠 */ }
    }

    static synchronized String messages(Context c, String id) {
        JSONObject r = rooms(c).optJSONObject(id);
        if (r == null || !r.optBoolean("on")) return "[]";
        JSONArray a = fresh(msgs(c, id));
        try { for (int i = 0; i < a.length(); i++) a.getJSONObject(i).put("new", a.getJSONObject(i).optLong("at") > r.optLong("read")); } catch (Exception e) { /* 표시만 생략 */ }
        return a.toString();
    }

    static synchronized void markRead(Context c, String id, long upTo) {
        try {
            JSONObject all = rooms(c), r = all.optJSONObject(id);
            if (r == null) return;
            r.put("read", Math.max(upTo, r.optLong("read")));
            sp(c).edit().putString("rooms", all.toString()).apply();
        } catch (Exception e) { /* 그대로 둠 */ }
    }

    static synchronized void clear(Context c) { sp(c).edit().clear().apply(); REPLIES.clear(); }

    /** "ok" | "off"(켜지 않은 방) | "empty" | "nosession"(답장할 알림이 없음) | "fail" */
    static synchronized String send(Context c, String id, String text) {
        JSONObject r = rooms(c).optJSONObject(id);
        if (r == null || !r.optBoolean("on")) return "off";
        if (text == null || text.trim().isEmpty()) return "empty";
        Reply p = REPLIES.get(id);
        if (p == null) return "nosession";
        try {
            Intent i = new Intent();
            Bundle b = new Bundle();
            for (RemoteInput in : p.inputs) b.putCharSequence(in.getResultKey(), text.trim());
            RemoteInput.addResultsToIntent(p.inputs, i, b);
            p.intent.send(c, 0, i);
            return "ok";
        } catch (PendingIntent.CanceledException e) { REPLIES.remove(id); return "nosession"; }
        catch (Exception e) { return "fail"; }
    }
}
