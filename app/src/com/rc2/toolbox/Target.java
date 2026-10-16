package com.rc2.toolbox;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

/** One launchable target: either a component, an action, or an action+URI. */
public final class Target {

    public final String title;
    public final String desc;
    /** Explicit component, or null when we rely on an action/URI. */
    public final String pkg;
    public final String cls;
    /** Intent action, or null. */
    public final String action;
    /** data URI, or null. */
    public final String data;
    /** "component", "action" or "uri". */
    public final String kind;

    private Target(String title, String desc, String pkg, String cls,
                   String action, String data, String kind) {
        this.title = title; this.desc = desc;
        this.pkg = pkg; this.cls = cls;
        this.action = action; this.data = data;
        this.kind = kind;
    }

    public static Target component(String title, String desc, String pkg, String cls) {
        return new Target(title, desc, pkg, cls, null, null, "component");
    }

    public static Target action(String title, String desc, String action) {
        return new Target(title, desc, null, null, action, null, "action");
    }

    public static Target uri(String title, String desc, String action, String data) {
        return new Target(title, desc, null, null, action, data, "uri");
    }

    /** True when this target names an explicit component. */
    public boolean isComponent() {
        return pkg != null && cls != null;
    }

    public Intent build(Context c) {
        Intent i;
        if (isComponent()) {
            i = new Intent();
            i.setComponent(new ComponentName(pkg, cls));
        } else if (action != null && data != null) {
            i = new Intent(action, Uri.parse(data));
        } else if (action != null) {
            i = new Intent(action);
        } else {
            return null;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * Tries a normal start first. Most of DJI's interesting screens are not
     * exported, so on refusal we replay the launch as uid 0 through
     * {@link AdbShell} — a root {@code am start} ignores the exported flag.
     *
     * <p>Only safe to call from a worker thread: the root replay opens a socket
     * and the platform throws {@link android.os.NetworkOnMainThreadException}
     * on the UI thread. UI code should call {@link #launchAsync} instead.
     */
    public String launch(Context c) {
        Intent i = build(c);
        if (i == null) {
            return "无法构造 Intent";
        }
        try {
            c.startActivity(i);
            return null;
        } catch (Throwable direct) {
            if (!isComponent()) {
                return describe(direct);
            }
            return rootStart(c, describe(direct));
        }
    }

    /** Result callback for {@link #launchAsync}; {@code err} is null on success. */
    public interface Then {
        void done(String err);
    }

    /**
     * Launch without blocking the UI thread.
     *
     * <p>The plain {@code startActivity} runs on the calling thread (cheap, and
     * it is where the exported check happens). Only the root replay, which needs
     * a socket, is moved to a worker. {@code then} is invoked on the main thread.
     */
    public void launchAsync(final Context c, final Then then) {
        final Intent i = build(c);
        if (i == null) {
            then.done("无法构造 Intent");
            return;
        }
        try {
            c.startActivity(i);
            then.done(null);
            return;
        } catch (Throwable direct) {
            if (!isComponent()) {
                then.done(describe(direct));
                return;
            }
        }
        final String why = "页面没有导出";
        final Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final String err = rootStart(app, why);
                post(app, new Runnable() {
                    public void run() {
                        then.done(err);
                    }
                });
            }
        }).start();
    }

    /** Replays the launch as uid 0. Returns null on success, else a reason. */
    private String rootStart(Context c, String why) {
        if (!AdbShell.available()) {
            String le = AdbShell.lastError;
            return why + "；root 通道也不可用"
                    + (le != null && le.length() > 0 ? "（" + le + "）" : "");
        }
        AdbShell.Result r = data != null
                ? AdbShell.startActivityData(pkg, cls, data)
                : AdbShell.startActivity(pkg, cls);
        String out = r.text();
        if (looksStarted(out)) {
            return null;
        }
        return why + "；root 启动也被拒: " + firstLine(out);
    }

    private static void post(Context c, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    /** {@code am start} prints "Starting: Intent {...}" when it accepted the request. */
    private static boolean looksStarted(String out) {
        if (out == null) {
            return false;
        }
        String s = out.toLowerCase();
        if (s.indexOf("exception") >= 0 || s.indexOf("error") >= 0) {
            return false;
        }
        return s.indexOf("starting: intent") >= 0;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "无输出";
        }
        String t = s.trim();
        int nl = t.indexOf('\n');
        if (nl > 0) {
            t = t.substring(0, nl);
        }
        return t.length() == 0 ? "无输出" : t;
    }

    private static String describe(Throwable t) {
        String msg = t.getClass().getSimpleName();
        if (t.getMessage() != null) {
            msg += ": " + t.getMessage();
        }
        return msg;
    }

    public void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    public static String settingsActionName(String a) {
        if (Settings.ACTION_SETTINGS.equals(a)) return "settings";
        return a;
    }
}
