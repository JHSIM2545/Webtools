package kr.simtools.suite;

import android.app.Notification;
import android.app.RemoteInput;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 카카오톡 알림만 읽는다(다른 앱 알림은 바로 버린다). 사용자가 '알림 접근'을 허용해야 동작한다. */
public class KakaoListener extends NotificationListenerService {
    private static final String KAKAO = "com.kakao.talk";
    private static final String[] ROOM_KEYS = {"android.conversationTitle", "android.subText", "android.summaryText", "android.hiddenConversationTitle"};

    @Override
    public void onListenerConnected() {
        try { for (StatusBarNotification n : getActiveNotifications()) handle(n); } catch (Exception e) { /* 새 알림부터 받는다 */ }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification n) { handle(n); }

    private void handle(StatusBarNotification sbn) {
        try {
            if (sbn == null || !KAKAO.equals(sbn.getPackageName())) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            KakaoStore.Reply reply = findReply(n);
            if (reply == null) return;   // 답장 버튼이 없는 알림은 대화가 아니다(광고·안내 등)
            Bundle x = n.extras;
            String title = str(x, Notification.EXTRA_TITLE), text = str(x, Notification.EXTRA_TEXT);
            if (title.isEmpty() || text.isEmpty()) return;
            // 단체방 이름이 담기는 칸은 카톡·안드로이드 버전마다 달라 차례로 찾는다. 모두 없으면 1:1 대화(상대 이름 = 방 이름)
            String room = "";
            StringBuilder dbg = new StringBuilder("제목=").append(title);
            for (String k : ROOM_KEYS) {
                String v = str(x, k);
                if (v.isEmpty()) continue;
                dbg.append(" / ").append(k.replace("android.", "")).append('=').append(v);
                if (room.isEmpty() && !v.matches(".*(\\d+\\s*개|새\\s*메시지|안\\s*읽은).*")) room = v;
            }
            boolean groupFlag = x.getBoolean("android.isGroupConversation", false);
            if (groupFlag) dbg.append(" / 단체방 표시");
            boolean named = !room.isEmpty();
            boolean group = groupFlag || (named && !room.equals(title));
            if (!named) room = title;
            String id = sbn.getTag() != null ? sbn.getTag() : "n:" + room;
            if (sbn.getUser() != null && sbn.getUser().hashCode() != 0) id = id + "@" + sbn.getUser().hashCode();   // 듀얼 메신저 구분
            KakaoStore.reply(id, reply);
            KakaoStore.add(this, id, room, named, group, title, text, n.when > 0 ? n.when : sbn.getPostTime(), dbg.toString());
        } catch (Exception e) { /* 알 수 없는 형식의 알림은 건너뜀 */ }
    }

    private static String str(Bundle x, String k) {
        CharSequence s = x == null ? null : x.getCharSequence(k);
        return s == null ? "" : s.toString().trim();
    }

    private static KakaoStore.Reply findReply(Notification n) {
        if (n.actions != null) for (Notification.Action a : n.actions) { KakaoStore.Reply r = toReply(a); if (r != null) return r; }
        for (Notification.Action a : new Notification.WearableExtender(n).getActions()) { KakaoStore.Reply r = toReply(a); if (r != null) return r; }
        return null;
    }

    private static KakaoStore.Reply toReply(Notification.Action a) {
        if (a == null || a.actionIntent == null) return null;
        RemoteInput[] in = a.getRemoteInputs();
        return in == null || in.length == 0 ? null : new KakaoStore.Reply(a.actionIntent, in);
    }
}
