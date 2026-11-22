package kr.simtools.homebot;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 장기 기억 파일: 앱 내부 + 다운로드/HomeBot 폴더에 저장(앱을 지워도 남음), 파일 선택기로 불러오기 */
final class FileBridge {
    static final int REQ_PICK = 94;
    private final Activity a;
    private final WebView web;

    FileBridge(Activity a, WebView web) { this.a = a; this.web = web; }

    private static void write(OutputStream o, String text) throws Exception {
        try { o.write(text.getBytes(StandardCharsets.UTF_8)); o.flush(); } finally { o.close(); }
    }

    /** 결과: "ok:위치" 또는 "err:사유" */
    @JavascriptInterface
    public String save(String name, String text) {
        try {
            if (name == null || !name.matches("[A-Za-z0-9._-]{1,60}")) return "err:bad name";
            if (text == null) text = "";
            write(new FileOutputStream(new File(a.getFilesDir(), name)), text);     // 1) 앱 내부 사본
            if (Build.VERSION.SDK_INT >= 29) {                                       // 2) 다운로드/HomeBot (재설치 후에도 남는 사본)
                ContentResolver cr = a.getContentResolver();
                Uri coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String rel = Environment.DIRECTORY_DOWNLOADS + "/HomeBot";
                Uri uri = null;
                Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{name, rel + "%"}, null);
                if (c != null) {
                    try { if (c.moveToFirst()) uri = ContentUris.withAppendedId(coll, c.getLong(0)); } finally { c.close(); }
                }
                if (uri == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                    uri = cr.insert(coll, v);
                }
                if (uri != null) { write(cr.openOutputStream(uri, "wt"), text); return "ok:다운로드/HomeBot/" + name; }
            }
            return "ok:앱 내부";
        } catch (Exception e) {
            return "err:" + e.getMessage();
        }
    }

    /** 파일 선택기를 열어 장기 기억 파일을 고른다. 선택하면 window.__ltmFile(text) 로 전달 */
    @JavascriptInterface
    public void pick() {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    a.startActivityForResult(i, REQ_PICK);
                } catch (Exception e) { send(null); }
            }
        });
    }

    void onResult(int res, Intent data) {
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) { send(null); return; }
        try {
            InputStream in = a.getContentResolver().openInputStream(data.getData());
            if (in == null) { send(null); return; }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; long tot = 0;
            while ((n = in.read(buf)) > 0) { tot += n; if (tot > 4L * 1024 * 1024) { in.close(); send(null); return; } b.write(buf, 0, n); }
            in.close();
            send(b.toString("UTF-8"));
        } catch (Exception e) { send(null); }
    }

    private void send(final String text) {
        final String js = "window.__ltmFile&&window.__ltmFile(" + (text == null ? "null" : JSONObject.quote(text)) + ")";
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(js, null); } });
    }
}
