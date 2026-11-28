package kr.simtools.suite;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/** SIM Assistant 화면에만 붙는 카톡 창구 "SimKakao". 보내기는 화면에서 사용자가 확인한 뒤에만 부른다. */
final class KakaoExtra {
    private KakaoExtra() {}

    static void attach(Activity a, WebView w, String key) {
        if ("assistant".equals(key)) w.addJavascriptInterface(new Bridge(a), "SimKakao");
    }

    static class Bridge {
        private final Activity a;
        Bridge(Activity a) { this.a = a; }

        /** 알림 접근이 허용되어 있는지 */
        @JavascriptInterface public boolean allowed() {
            String s = Settings.Secure.getString(a.getContentResolver(), "enabled_notification_listeners");
            if (s == null) return false;
            ComponentName me = new ComponentName(a, KakaoListener.class);
            for (String p : s.split(":")) if (me.equals(ComponentName.unflattenFromString(p))) {
                try { NotificationListenerService.requestRebind(me); } catch (Exception e) { /* 이미 연결됨 */ }
                return true;
            }
            return false;
        }

        @JavascriptInterface public void openAccess() { open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }

        /** 직접 설치한 앱은 Android 13 이상에서 '제한된 설정 허용'이 먼저 필요하다 → 앱 정보 화면 */
        @JavascriptInterface public void openAppInfo() { open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))); }

        private void open(final Intent i) { a.runOnUiThread(() -> { try { a.startActivity(i); } catch (Exception e) { /* 설정 화면이 없는 기기 */ } }); }

        @JavascriptInterface public String rooms() { return KakaoStore.list(a); }
        @JavascriptInterface public void setRoom(String id, boolean on) { KakaoStore.setOn(a, id, on); }
        @JavascriptInterface public String messages(String id) { return KakaoStore.messages(a, id); }
        @JavascriptInterface public void markRead(String id, String upTo) { try { KakaoStore.markRead(a, id, Long.parseLong(upTo)); } catch (Exception e) { /* 무시 */ } }
        @JavascriptInterface public String send(String id, String text) { return KakaoStore.send(a, id, text); }
        @JavascriptInterface public void clear() { KakaoStore.clear(a); }
    }
}
