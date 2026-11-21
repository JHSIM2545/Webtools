package kr.simtools.homebot;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** 기기 자체(Android) 음성 합성. WebView 에는 웹 음성출력이 없어서 homebot.html 이 window.SimTts 로 부른다. */
final class TtsBridge {
    private final WebView web;
    private TextToSpeech tts;
    private volatile String state = "init";   // init | ready | nolang | none
    private final ArrayList<Object[]> waiting = new ArrayList<>();

    TtsBridge(Context ctx, WebView w) {
        web = w;
        try {
            tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) { onReady(status); }
            });
        } catch (Exception e) {
            state = "none";
        }
    }

    private void onReady(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) { state = "none"; drop(); return; }
        try {
            int r = tts.setLanguage(Locale.KOREAN);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { state = "nolang"; drop(); return; }
            try {   // 인터넷이 필요 없는 한국어 음성 중 품질이 가장 높은 것
                Voice best = null;
                for (Voice v : tts.getVoices()) {
                    if (v.getLocale() != null && "ko".equals(v.getLocale().getLanguage()) && !v.isNetworkConnectionRequired()) {
                        if (best == null || v.getQuality() > best.getQuality()) best = v;
                    }
                }
                if (best != null) tts.setVoice(best);
            } catch (Exception ignore) { }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { emit(id, "start"); }
                @Override public void onDone(String id) { emit(id, "done"); }
                @Override public void onError(String id) { emit(id, "error"); }
                @Override public void onStop(String id, boolean interrupted) { emit(id, "stop"); }
            });
            state = "ready";
            ArrayList<Object[]> q;
            synchronized (waiting) { q = new ArrayList<>(waiting); waiting.clear(); }
            for (Object[] a : q) doSpeak((String) a[0], (String) a[1], (Float) a[2], (Float) a[3], (Boolean) a[4]);
        } catch (Exception e) {
            state = "none";
            drop();
        }
    }

    /** 준비 전에 쌓인 말이 있었는데 쓸 수 없게 되면 JS 쪽에 실패를 알린다 */
    private void drop() {
        ArrayList<Object[]> q;
        synchronized (waiting) { q = new ArrayList<>(waiting); waiting.clear(); }
        for (Object[] a : q) emit((String) a[1], "error");
    }

    private void emit(String id, String ev) {
        final String js = "window.__simTts&&window.__simTts(" + JSONObject.quote(id) + "," + JSONObject.quote(ev) + ")";
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
    }

    private boolean doSpeak(String text, String id, float rate, float pitch, boolean add) {
        try {
            tts.setSpeechRate(rate > 0 ? rate : 1f);
            tts.setPitch(pitch > 0 ? pitch : 1f);
            return tts.speak(text, add ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, new Bundle(), id) == TextToSpeech.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String status() { return state; }

    @JavascriptInterface
    public boolean speak(String text, String id, float rate, float pitch, boolean add) {
        if (tts == null || "none".equals(state) || "nolang".equals(state)) return false;
        if ("init".equals(state)) {
            synchronized (waiting) { waiting.add(new Object[]{text, id, rate, pitch, add}); }
            return true;
        }
        return doSpeak(text, id, rate, pitch, add);
    }

    @JavascriptInterface
    public void stop() {
        synchronized (waiting) { waiting.clear(); }
        try { if (tts != null) tts.stop(); } catch (Exception ignore) { }
    }

    void destroy() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignore) { }
        tts = null;
    }
}
