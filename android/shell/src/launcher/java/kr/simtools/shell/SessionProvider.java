package kr.simtools.shell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** 런처 앱 전용: 같은 서명키로 만든 다른 SIM 앱에게만 로그인 세션을 읽고 쓰게 해 준다. */
public class SessionProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private boolean allowed() {
        Context c = getContext();
        String cp = getCallingPackage();
        if (c == null || cp == null) return false;
        try { return c.getPackageManager().checkSignatures(cp, c.getPackageName()) == PackageManager.SIGNATURE_MATCH; }
        catch (Exception e) { return false; }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!allowed()) return null;
        SharedPreferences sp = getContext().getSharedPreferences(SessionStore.PREFS, Context.MODE_PRIVATE);
        if ("put".equals(method) && arg != null && !arg.isEmpty()) sp.edit().putString(SessionStore.KEY, arg).apply();
        Bundle b = new Bundle();
        b.putString(SessionStore.KEY, sp.getString(SessionStore.KEY, ""));
        return b;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
