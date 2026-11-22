package kr.simtools.homebot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 연락처에서 이름으로 번호를 찾아 전화를 거는 기능 (번호는 AI 쪽으로 보내지 않고 앱 안에서만 사용) */
final class PhoneBridge {
    static final int REQ_PHONE = 92;
    private final Activity a;

    PhoneBridge(Activity a) { this.a = a; }

    private boolean has(String p) { return a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @JavascriptInterface
    public String perms() {
        return "{\"contacts\":" + has(Manifest.permission.READ_CONTACTS) + ",\"call\":" + has(Manifest.permission.CALL_PHONE) + "}";
    }

    @JavascriptInterface
    public void request() {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE}, REQ_PHONE);
            }
        });
    }

    private static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) if (Character.isLetterOrDigit(ch)) b.append(ch);
        String r = b.toString();
        if (r.length() > 2 && (r.endsWith("님") || r.endsWith("씨"))) r = r.substring(0, r.length() - 1);
        return r;
    }

    private static int lev(String x, String y) {
        int[] p = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) p[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            int prev = p[0];
            p[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int t = p[j];
                p[j] = Math.min(Math.min(p[j] + 1, p[j - 1] + 1), prev + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1));
                prev = t;
            }
        }
        return p[y.length()];
    }

    /** 이름이 같거나 비슷한 연락처를 찾는다. 결과: {"perm":bool,"items":[{"name","number"}]} */
    @JavascriptInterface
    public String find(String query) {
        try {
            if (!has(Manifest.permission.READ_CONTACTS)) return "{\"perm\":false,\"items\":[]}";
            String q = norm(query == null ? "" : query);
            if (q.length() < 1) return "{\"perm\":true,\"items\":[]}";
            int best = 9;
            Map<String, String> num = new LinkedHashMap<>();
            Map<String, Integer> typ = new LinkedHashMap<>();
            Map<String, Integer> sc = new LinkedHashMap<>();
            Cursor c = a.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE},
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (c != null) {
                try {
                    int n = 0;
                    while (c.moveToNext() && n++ < 8000) {
                        String name = c.getString(0), number = c.getString(1);
                        int type = c.getInt(2);
                        if (name == null || number == null) continue;
                        String nn = norm(name);
                        int s;
                        if (nn.equals(q)) s = 0;
                        else if (q.length() >= 2 && (nn.contains(q) || (nn.length() >= 2 && q.contains(nn)))) s = 1;
                        else if (q.length() >= 3 && nn.length() >= 3 && lev(nn, q) <= 1) s = 2;
                        else continue;
                        if (s > best) continue;
                        if (s < best) { best = s; num.clear(); typ.clear(); sc.clear(); }
                        Integer old = typ.get(name);
                        if (old == null || (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE && old != ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)) {
                            num.put(name, number);
                            typ.put(name, type);
                            sc.put(name, s);
                        }
                    }
                } finally { c.close(); }
            }
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : num.entrySet()) {
                if (arr.length() >= 8) break;
                JSONObject o = new JSONObject();
                o.put("name", e.getKey());
                o.put("number", e.getValue());
                arr.put(o);
            }
            JSONObject r = new JSONObject();
            r.put("perm", true);
            r.put("items", arr);
            return r.toString();
        } catch (Exception e) {
            return "{\"perm\":true,\"items\":[],\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** 전화 걸기. CALL_PHONE 이 허용돼 있으면 바로 연결, 아니면 다이얼 화면을 열어 둔다 */
    @JavascriptInterface
    public String call(final String number) {
        if (number == null) return "bad";
        final String n = number.replaceAll("[^0-9+*#]", "");
        if (n.length() < 3) return "bad";
        final boolean direct = has(Manifest.permission.CALL_PHONE);
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.fromParts("tel", n, null));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(i);
                } catch (Exception ignore) { }
            }
        });
        return direct ? "calling" : "dial";
    }
}
