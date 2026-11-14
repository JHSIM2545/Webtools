package kr.simtools.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * SIM Radio 재생 알림: 화면을 내리거나 다른 앱을 써도 알림창에 라디오 알림이 남는다.
 *  - 알림을 누르면 앱(라디오 화면)으로 돌아온다
 *  - 알림을 밀어서 지우거나 ■ 정지를 누르면 라디오를 끈다 (화면 쪽에 'stop' 신호를 보낸다)
 *  - ◀ ▶ 로 이전/다음 방송국
 * 소리는 화면(웹)에서 나므로, 이 서비스는 앱이 꺼지지 않게 붙잡고 알림만 담당한다.
 */
public class RadioService extends Service {
    /** 알림 버튼이 눌렸을 때 화면(웹)에 전달하는 창구 */
    interface Callback { void onCmd(String cmd); }

    static volatile Callback cb;
    private static volatile RadioService inst;

    private static final String CH = "radio_play";
    private static final int ID = 1301;
    static final String ACT_STOP = "kr.simtools.shell.RADIO_STOP";
    static final String ACT_PREV = "kr.simtools.shell.RADIO_PREV";
    static final String ACT_NEXT = "kr.simtools.shell.RADIO_NEXT";

    private MediaSession session;
    private String title = "SIM Radio", sub = "";

    /** 이미 켜져 있으면 알림 글자만 바꾼다 (백그라운드에서 서비스를 새로 시작하지 않도록). 켜져 있지 않으면 false */
    static boolean update(String t, String s) {
        RadioService r = inst;
        if (r == null) return false;
        r.title = t; r.sub = s;
        r.show();
        return true;
    }

    private void channel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(CH, "라디오 재생", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("라디오를 듣는 동안 표시돼요");
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String label, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }

    private Notification build() {
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        try { if (session != null) style.setMediaSession(session.getSessionToken()); } catch (Exception e) { /* 무시 */ }
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(openApp())
                .setDeleteIntent(act(ACT_STOP, 3))          // 밀어서 지우면 라디오 종료
                .addAction(action(android.R.drawable.ic_media_previous, "이전", act(ACT_PREV, 1)))
                .addAction(action(android.R.drawable.ic_media_pause, "정지", act(ACT_STOP, 3)))
                .addAction(action(android.R.drawable.ic_media_next, "다음", act(ACT_NEXT, 2)))
                .setStyle(style)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    private void show() {
        try {
            if (session != null) {
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                        .build());
                session.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_STOP | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build());
            }
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
        } catch (Exception e) { /* 알림을 못 띄워도 소리는 계속 난다 */ }
    }

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        channel();
        try {
            session = new MediaSession(this, "SIMRadio");
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPause() { send("stop"); }
                @Override public void onStop() { send("stop"); }
                @Override public void onSkipToNext() { send("next"); }
                @Override public void onSkipToPrevious() { send("prev"); }
            });
            session.setActive(true);
        } catch (Exception e) { session = null; }
    }

    private void send(String cmd) {
        Callback c = cb;
        if (c != null) { try { c.onCmd(cmd); } catch (Exception e) { /* 무시 */ } }
        if ("stop".equals(cmd)) { stopForeground(true); stopSelf(); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_STOP.equals(a)) { send("stop"); return START_NOT_STICKY; }
        if (ACT_PREV.equals(a)) { send("prev"); return START_NOT_STICKY; }
        if (ACT_NEXT.equals(a)) { send("next"); return START_NOT_STICKY; }
        if (intent != null) {
            if (intent.hasExtra("title")) title = intent.getStringExtra("title");
            if (intent.hasExtra("sub")) sub = intent.getStringExtra("sub");
        }
        show();
        return START_NOT_STICKY;
    }

    /** 앱을 최근 앱 목록에서 밀어 닫으면(소리도 같이 끊김) 알림도 거둔다 */
    @Override public void onTaskRemoved(Intent rootIntent) {
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        inst = null;
        try { if (session != null) { session.setActive(false); session.release(); } } catch (Exception e) { /* 무시 */ }
        session = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
