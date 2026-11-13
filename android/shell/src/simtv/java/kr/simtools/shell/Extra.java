package kr.simtools.shell;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Rational;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * SIM TV 앱 전용(다른 앱에는 들어가지 않음): 안드로이드 PIP(화면 속 화면).
 * 앱 안 WebView 는 웹의 PIP(video.requestPictureInPicture)를 지원하지 않으므로, 화면이 SimPip.enter() 를 부르면
 * 앱 창 자체를 PIP 로 줄인다. 화면(tv.html)은 이때 영상만 꽉 채워 보여 주고, 창이 다시 커지면 원래 모양으로 돌아온다.
 */
final class Extra {
    private Extra() {}

    static void attach(Activity a, WebView w) { w.addJavascriptInterface(new Pip(a), "SimPip"); }
    static void onResume(Activity a, WebView w) {}
    static void destroy(Activity a) {}

    private static final class Pip {
        private final Activity a;
        Pip(Activity a) { this.a = a; }

        /** 이 폰·안드로이드 버전이 PIP 를 지원하는지 */
        @JavascriptInterface public boolean available() {
            return Build.VERSION.SDK_INT >= 26 && a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        }

        /** 지금 PIP(작은 창) 상태인지 */
        @JavascriptInterface public boolean isPip() {
            return Build.VERSION.SDK_INT >= 24 && a.isInPictureInPictureMode();
        }

        /** 앱을 PIP 로 줄인다. 폰 설정에서 이 앱의 'PIP 허용'이 꺼져 있으면 아무 일도 일어나지 않는다(화면이 isPip() 로 확인). */
        @JavascriptInterface public void enter() {
            if (!available()) return;
            a.runOnUiThread(() -> {
                try {
                    a.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
                } catch (Exception e) { /* 설정에서 꺼져 있음 등 */ }
            });
        }
    }
}
