package kr.simtools.shell;

import android.app.Activity;
import android.webkit.WebView;

/** 도구별 앱: 추가 기능 없음 (런처 전용 기능은 launcher 쪽 Extra 에 있음) */
final class Extra {
    private Extra() {}
    static void attach(Activity a, WebView w) {}
    static void onResume(Activity a, WebView w) {}
    static void destroy(Activity a) {}
}
