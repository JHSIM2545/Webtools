package kr.simtools.homebot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 안드로이드 WebView 에는 웹 음성인식(SpeechRecognition)이 없어서, 기기의 음성인식(SpeechRecognizer)을 웹 화면에 "SimStt" 로 내보낸다.
 * homebot.html 안의 어댑터가 이것을 웹 표준 SpeechRecognition 처럼 보이게 한다.
 * 이벤트: start / speechstart / speechend / result{text,final} / error{error} / end  → window.__simStt(이벤트, 값)
 */
final class SttBridge {
    private final Activity act;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager am;
    private SpeechRecognizer sr;
    private int gen = 0;
    private boolean muted = false;

    SttBridge(Activity a, WebView w) {
        act = a;
        web = w;
        am = (AudioManager) a.getSystemService(Activity.AUDIO_SERVICE);
    }

    @JavascriptInterface
    public boolean available() {
        return SpeechRecognizer.isRecognitionAvailable(act);
    }

    @JavascriptInterface
    public void start(final String lang, final boolean interim) {
        main.post(new Runnable() { @Override public void run() { begin(lang, interim); } });
    }

    @JavascriptInterface
    public void stop() {
        main.post(new Runnable() {
            @Override public void run() { try { if (sr != null) sr.stopListening(); } catch (Exception ignore) { } }
        });
    }

    @JavascriptInterface
    public void abort() {
        main.post(new Runnable() { @Override public void run() { kill(); } });
    }

    void destroy() {
        kill();
    }

    private void begin(String lang, boolean interim) {
        kill();
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            emit("error", err("not-allowed"));
            emit("end", "{}");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            emit("error", err("service-not-allowed"));
            emit("end", "{}");
            return;
        }
        final int my = ++gen;
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(act);
            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEvent(int t, Bundle p) { }

                @Override public void onBeginningOfSpeech() {
                    if (my == gen) emit("speechstart", "{}");
                }

                @Override public void onEndOfSpeech() {
                    if (my == gen) emit("speechend", "{}");
                }

                @Override public void onPartialResults(Bundle b) {
                    if (my != gen) return;
                    String t = first(b);
                    if (t != null) emit("result", res(t, false));
                }

                @Override public void onResults(Bundle b) {
                    if (my != gen) return;
                    String t = first(b);
                    if (t != null) emit("result", res(t, true));
                    finishSession();
                    emit("end", "{}");
                }

                @Override public void onError(int code) {
                    if (my != gen) return;
                    String e;
                    switch (code) {
                        case SpeechRecognizer.ERROR_NO_MATCH:
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: e = "no-speech"; break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: e = "not-allowed"; break;
                        case SpeechRecognizer.ERROR_NETWORK:
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        case SpeechRecognizer.ERROR_SERVER: e = "network"; break;
                        case SpeechRecognizer.ERROR_AUDIO: e = "audio-capture"; break;
                        default: e = "aborted";
                    }
                    finishSession();
                    emit("error", err(e));
                    emit("end", "{}");
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, (lang == null || lang.isEmpty()) ? "ko-KR" : lang);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.getPackageName());
            muteBeep(true);
            sr.startListening(i);
            emit("start", "{}");
        } catch (Exception e) {
            finishSession();
            emit("error", err("service-not-allowed"));
            emit("end", "{}");
        }
    }

    private static String first(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (l == null || l.isEmpty() || l.get(0) == null || l.get(0).isEmpty()) ? null : l.get(0);
    }

    private static String res(String t, boolean fin) {
        try { return new JSONObject().put("text", t).put("final", fin).toString(); } catch (Exception e) { return "{}"; }
    }

    private static String err(String e) {
        try { return new JSONObject().put("error", e).toString(); } catch (Exception x) { return "{}"; }
    }

    /** 현재 세션 정리(JS 로는 알리지 않음) */
    private void kill() {
        gen++;
        finishSession();
    }

    private void finishSession() {
        SpeechRecognizer s = sr;
        sr = null;
        if (s != null) {
            try { s.cancel(); } catch (Exception ignore) { }
            try { s.destroy(); } catch (Exception ignore) { }
        }
        muteBeep(false);
    }

    /** 음성인식을 시작할 때 나는 "띠링" 소리를 가능한 만큼 줄인다(기기에 따라 효과 없음). 로봇 음성(미디어)에는 영향 없음 */
    private void muteBeep(boolean on) {
        if (am == null || on == muted) return;
        muted = on;
        int dir = on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
        int[] streams = {AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM};
        for (int st : streams) {
            try { am.adjustStreamVolume(st, dir, 0); } catch (Exception ignore) { }
        }
    }

    private void emit(final String ev, final String json) {
        final String js = "window.__simStt&&window.__simStt('" + ev + "'," + json + ")";
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
    }
}
