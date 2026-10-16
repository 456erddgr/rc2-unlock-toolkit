package com.rc2.toolbox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The NEXUS privileged bridge.
 *
 * <p>This device ships an unauthenticated root adbd listening on 127.0.0.1:5555,
 * which means any process that can speak the ADB wire protocol can obtain a
 * uid=0 shell. {@link AdbShell} does exactly that. This provider turns that
 * capability into something other apps and shell scripts can use: a single
 * {@code call()} that runs one command as root and returns its output.
 *
 * <p>It is exposed so that a {@code su} shim installed at
 * {@code /data/adb/ksu/bin/su} can route through it. Access is gated by
 * {@code /data/adb/ksu/.nexus_allow} — one uid or package per line, or {@code *}
 * to allow everything. The gate is the only real control here, because the
 * underlying channel is itself unauthenticated; the app says so plainly on
 * screen rather than pretending otherwise.
 */
public class SuProvider extends ContentProvider {

    public static final String AUTHORITY = "com.rc2.toolbox.su";
    public static final String URI = "content://" + AUTHORITY;
    public static final String METHOD_RUN = "run";
    public static final String METHOD_INFO = "info";
    public static final String KEY_OUT = "out";
    public static final String KEY_SPOOL = "spool";
    public static final String ALLOW_FILE = "/data/adb/ksu/.nexus_allow";
    public static final String SPOOL_DIR = "/data/local/tmp/nexus-su";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle b = new Bundle();
        if (METHOD_INFO.equals(method)) {
            int port = AdbShell.findPort();
            b.putString(KEY_OUT, port > 0
                    ? "NEXUS su ready\nroot=uid0\nport=" + port
                      + "\nidentity=" + AdbShell.identity()
                      + "\nallow=" + allowPolicy()
                    : "NEXUS su unavailable\n" + AdbShell.lastError);
            return b;
        }
        if (!METHOD_RUN.equals(method)) {
            b.putString(KEY_OUT, "unknown method: " + method);
            return b;
        }

        int uid = callerUid();
        if (!allowed(uid)) {
            b.putString(KEY_OUT, "denied: uid " + uid + " is not in " + ALLOW_FILE);
            return b;
        }
        if (arg == null || arg.trim().length() == 0) {
            b.putString(KEY_OUT, "usage: su [-c] <command>");
            return b;
        }

        AdbShell.Result r = AdbShell.run(arg, 120000);
        String out = r.text();
        if (!r.ok()) {
            out = out + "\n[exit " + r.code + "]";
        }

        // A shell caller cannot easily read a Bundle back out of `content call`,
        // so when it names a spool file we drop the raw output there and hand it
        // 0666. World-readable is deliberate: the caller may be any app uid.
        String spool = extras == null ? null : extras.getString(KEY_SPOOL);
        if (spool != null && spool.startsWith("/data/local/tmp/")) {
            AdbShell.run("mkdir -p " + SPOOL_DIR + " && chmod 0777 " + SPOOL_DIR, 15000);
            writeFile(spool, out);
            b.putString(KEY_OUT, "spooled:" + spool);
            return b;
        }

        b.putString(KEY_OUT, out);
        return b;
    }

    /** Writes text as root and opens it up, so the calling uid can cat it. */
    private static void writeFile(String path, String text) {
        String b64 = android.util.Base64.encodeToString(
                text.getBytes(), android.util.Base64.NO_WRAP);
        // base64 -d keeps the content byte-exact and sidesteps quoting entirely.
        AdbShell.run("echo " + b64 + " | base64 -d > " + shellQuote(path)
                + " && chmod 0666 " + shellQuote(path), 30000);
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ gate

    private int callerUid() {
        try {
            return android.os.Binder.getCallingUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean allowed(int uid) {
        if (uid == 0 || uid == 1000 || uid == 2000) {
            return true;   // root, system, shell
        }
        String policy = allowPolicy();
        if ("*".equals(policy)) {
            return true;
        }
        String[] want = policy.split("\n");
        for (int i = 0; i < want.length; i++) {
            String w = want[i].trim();
            if (w.length() == 0) {
                continue;
            }
            if (w.startsWith("uid=")) {
                w = w.substring(4);
            }
            try {
                if (Integer.parseInt(w) == uid) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // a package name: resolve to uid and compare
                try {
                    if (android.os.Process.getUidForName(w) == uid) {
                        return true;
                    }
                } catch (Throwable ignored2) {
                }
            }
        }
        return false;
    }

    /** Raw contents of the allowlist, or "" when absent. */
    public static String allowPolicy() {
        AdbShell.Result r = AdbShell.run(
                "cat " + ALLOW_FILE + " 2>/dev/null || echo __NONE__", 10000);
        String t = r.text().trim();
        if (t.length() == 0 || t.indexOf("__NONE__") >= 0) {
            return "";
        }
        return t;
    }

    // -------------------------------------------------- ContentProvider boilerplate

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }
}
