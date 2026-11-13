package kr.simtools.shell;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * SIM Scan 앱 전용(다른 앱에는 들어가지 않음): 화면(웹)이 스캔 결과를 '내 파일(다운로드 폴더)'에 저장하고 프린터로 출력하는 창구 "SimFiles".
 * 웹의 Blob 은 앱 내려받기 기능이 처리하지 못하므로, 화면이 파일을 조각(base64)으로 넘기면 여기서 이어 붙여 저장한다.
 */
final class Extra {
    private Extra() {}

    private static Bridge bridge;

    static void attach(Activity a, WebView w) {
        bridge = new Bridge(a);
        w.addJavascriptInterface(bridge, "SimFiles");
    }

    static void onResume(Activity a, WebView w) {}

    static void destroy(Activity a) {
        if (bridge != null) bridge.closeAll();
    }

    private static final class Out {
        OutputStream os;
        Uri uri;          // 다운로드 폴더(MediaStore) 항목
        File file;        // 캐시·구형 기기 저장 파일
        String name;
        boolean pending;
    }

    private static final class Bridge {
        private final Activity act;
        private final Map<String, Out> outs = new HashMap<>();
        private int seq = 0;

        Bridge(Activity a) { act = a; }

        private static String clean(String n) {
            String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
            return s.isEmpty() ? "scan" : s;
        }

        /** 저장 시작. mode: "download"(다운로드/SIM Scan 폴더) 또는 "cache"(인쇄용 임시 파일). 반환: 작업 번호 (실패하면 "ERR...") */
        @JavascriptInterface
        public synchronized String begin(String name, String mime, String mode) {
            try {
                Out o = new Out();
                o.name = clean(name);
                if ("cache".equals(mode)) {
                    o.file = new File(act.getCacheDir(), "scan-" + System.nanoTime() + "-" + o.name);
                    o.os = new FileOutputStream(o.file);
                } else if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = act.getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, o.name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SIM Scan");
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    o.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (o.uri == null) return "ERR:insert";
                    o.pending = true;
                    o.os = cr.openOutputStream(o.uri);
                } else {
                    File dir = new File(act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SIM Scan");
                    dir.mkdirs();
                    o.file = new File(dir, o.name);
                    o.os = new FileOutputStream(o.file);
                }
                if (o.os == null) return "ERR:open";
                String id = "o" + (++seq);
                outs.put(id, o);
                return id;
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public synchronized boolean chunk(String id, String b64) {
            Out o = outs.get(id);
            if (o == null) return false;
            try {
                o.os.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 저장 끝. 반환: 저장 위치 설명 (인쇄용 임시 파일이면 파일 경로). 실패하면 "ERR..." */
        @JavascriptInterface
        public synchronized String end(String id) {
            Out o = outs.remove(id);
            if (o == null) return "ERR:none";
            try {
                o.os.flush();
                o.os.close();
                if (o.uri != null && o.pending) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    act.getContentResolver().update(o.uri, cv, null, null);
                    return "Download/SIM Scan/" + o.name;
                }
                return o.file != null ? o.file.getAbsolutePath() : o.name;
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** 임시 PDF 파일을 안드로이드 인쇄 창으로 출력 */
        @JavascriptInterface
        public void print(final String path, final String jobName) {
            final File f = new File(path == null ? "" : path);
            if (!f.isFile()) return;
            act.runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
                        final String title = (jobName == null || jobName.isEmpty()) ? "SIM Scan" : jobName;
                        pm.print(title, new PrintDocumentAdapter() {
                            @Override public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cs, LayoutResultCallback cb, Bundle extras) {
                                if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
                                cb.onLayoutFinished(new PrintDocumentInfo.Builder(title + ".pdf")
                                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true);
                            }
                            @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cs, WriteResultCallback cb) {
                                InputStream in = null;
                                OutputStream out = null;
                                try {
                                    in = new FileInputStream(f);
                                    out = new FileOutputStream(dest.getFileDescriptor());
                                    byte[] buf = new byte[16384];
                                    int n;
                                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                                } catch (Exception e) {
                                    cb.onWriteFailed(e.getMessage());
                                } finally {
                                    try { if (in != null) in.close(); } catch (Exception ignore) {}
                                    try { if (out != null) out.close(); } catch (Exception ignore) {}
                                }
                            }
                        }, new PrintAttributes.Builder().build());
                    } catch (Exception ignore) {}
                }
            });
        }

        synchronized void closeAll() {
            for (Out o : outs.values()) { try { o.os.close(); } catch (Exception ignore) {} }
            outs.clear();
        }
    }
}
