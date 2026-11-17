package kr.simtools.suite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 통합 SIM Tools 의 런처 창구 "SimLauncher".
 * 예전에는 도구마다 별도 앱을 내려받아 설치했지만, 이제는 모든 도구가 이 앱 안에 들어 있고
 * "설치"는 런처 보드에 그 도구를 켜는 것, "제거"는 끄는 것이다(데이터는 그대로 남는다).
 */
final class SuiteExtra {
    private SuiteExtra() {}

    /** 이 앱에 들어 있는 도구 id (런처 index.html 의 TOOLS 와 같은 id) */
    private static final List<String> IDS = Arrays.asList("simtv", "note", "phone", "aitel", "assistant", "calendar", "folder", "message", "kbo", "orchestra", "radio", "calc", "map", "teams", "scan", "recipe", "brief", "price");

    static boolean valid(String id) { return id != null && IDS.contains(id); }

    static String page(String id) { return "simtv".equals(id) ? "tv.html" : id + ".html"; }

    static void attach(Activity a, WebView w, boolean launcher) {
        if (launcher) w.addJavascriptInterface(new Bridge(a, w), "SimLauncher");
    }

    static void onResume(Activity a, WebView w) {
        w.post(() -> w.evaluateJavascript("window.__simRefresh&&window.__simRefresh()", null));
    }

    static void destroy(Activity a) {}

    static class Bridge {
        private final Activity a; private final WebView w;
        Bridge(Activity a, WebView w) { this.a = a; this.w = w; }

        private SharedPreferences sp() { return a.getSharedPreferences("simsuite", Context.MODE_PRIVATE); }

        private Set<String> on() {
            Set<String> s = new LinkedHashSet<>();
            try {
                JSONArray arr = new JSONArray(sp().getString("on", "[]"));
                for (int i = 0; i < arr.length(); i++) if (valid(arr.getString(i))) s.add(arr.getString(i));
            } catch (Exception e) { /* 비어 있는 것으로 */ }
            return s;
        }

        private void save(Set<String> s) { sp().edit().putString("on", new JSONArray(new ArrayList<>(s)).toString()).apply(); }

        private void refresh() { w.post(() -> w.evaluateJavascript("window.__simRefresh&&window.__simRefresh()", null)); }

        @JavascriptInterface public boolean isSuite() { return true; }

        /** 켜 둔 도구 id 목록 (JSON 배열) */
        @JavascriptInterface public String installed() { return new JSONArray(new ArrayList<>(on())).toString(); }

        /** 앱 하나에 모두 들어 있어 따로 업데이트할 것이 없다 */
        @JavascriptInterface public void checkUpdates() {
            w.post(() -> w.evaluateJavascript("window.__simUpdates&&window.__simUpdates([],{})", null));
        }

        @JavascriptInterface public void open(String id) {
            if (!valid(id)) return;
            Intent i = new Intent(a, MainActivity.class).setData(Uri.parse("simtools://tool/" + id));
            a.startActivity(i);
        }

        /** "설치" = 런처 보드에 켜기 */
        @JavascriptInterface public void install(String id) {
            if (!valid(id)) return;
            Set<String> s = on(); s.add(id); save(s);
            w.post(() -> w.evaluateJavascript("window.__simInstall&&window.__simInstall('" + id + "','added')", null));
            refresh();
        }

        @JavascriptInterface public void installAll(String ids) { /* 업데이트할 앱이 없음 */ }

        /** "제거" = 런처 보드에서 끄기 (저장된 데이터는 지우지 않는다) */
        @JavascriptInterface public void uninstall(String id) {
            Set<String> s = on(); s.remove(id); save(s);
            refresh();
        }
    }
}
