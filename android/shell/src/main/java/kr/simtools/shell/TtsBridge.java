package kr.simtools.shell;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Locale;

/** 기기 자체(Android) 음성 합성 — 인터넷 없이도 한국어 음성이 설치돼 있으면 소리 낸다. 웹은 window.SimTts 로 부른다. */
public class TtsBridge {
    private final WebView web;
    private TextToSpeech tts;
    private volatile String state = "init";   // init | ready | nolang | none

    public TtsBridge(Context ctx, WebView web) {
        this.web = web;
        try {
            tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) { state = "none"; return; }
                try {
                    int r = tts.setLanguage(Locale.KOREAN);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { state = "nolang"; return; }
                    try {   // 인터넷이 필요 없는 한국어 음성을 우선 고른다
                        for (Voice v : tts.getVoices()) {
                            if (v.getLocale() != null && "ko".equals(v.getLocale().getLanguage()) && !v.isNetworkConnectionRequired()) { tts.setVoice(v); break; }
                        }
                    } catch (Exception ignore) { }
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { emit(id, "start"); }
                        @Override public void onDone(String id) { emit(id, "done"); }
                        @Override public void onError(String id) { emit(id, "error"); }
                        @Override public void onStop(String id, boolean interrupted) { emit(id, "stop"); }
                    });
                    state = "ready";
                } catch (Exception e) { state = "none"; }
            });
        } catch (Exception e) { state = "none"; }
    }

    private void emit(String id, String ev) {
        if (web == null) return;
        final String js = "window.__simTts&&window.__simTts(" + JSONObject.quote(id) + "," + JSONObject.quote(ev) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    @JavascriptInterface public String status() { return state; }

    @JavascriptInterface public boolean speak(String text, String id, float rate) {
        if (tts == null || !"ready".equals(state)) return false;
        try {
            tts.setSpeechRate(rate > 0 ? rate : 1f);
            Bundle b = new Bundle();
            return tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, id) == TextToSpeech.SUCCESS;
        } catch (Exception e) { return false; }
    }

    @JavascriptInterface public void stop() { try { if (tts != null) tts.stop(); } catch (Exception ignore) { } }

    public void destroy() { try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignore) { } tts = null; }
}
