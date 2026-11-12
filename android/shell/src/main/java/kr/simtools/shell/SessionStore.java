package kr.simtools.shell;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * 로그인 세션을 런처 앱 한 곳에 두고 모든 SIM 앱이 같이 쓴다.
 * (앱마다 WebView 저장소가 따로라서, 이렇게 하지 않으면 도구 앱은 로그인 상태를 알 수 없다)
 *  값: "" = 아직 없음, "OUT" = 런처에서 로그아웃함, 그 외 = {"access_token","refresh_token"} JSON, "NA" = 런처가 없어 조회 불가
 */
final class SessionStore {
    private SessionStore() {}

    static final String PREFS = "sim_session";
    static final String KEY = "s";
    static final Uri URI = Uri.parse("content://kr.simtools.launcher.session");

    static String get(Context c) {
        if (BuildConfig.IS_LAUNCHER) return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        try {
            Bundle b = c.getContentResolver().call(URI, "get", null, null);
            return b == null ? "NA" : b.getString(KEY, "");
        } catch (Exception e) {
            return "NA";
        }
    }

    static void put(Context c, String value) {
        if (value == null) return;
        if (BuildConfig.IS_LAUNCHER) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply();
            return;
        }
        try { c.getContentResolver().call(URI, "put", value, null); } catch (Exception e) { /* 런처가 없으면 무시 */ }
    }
}
