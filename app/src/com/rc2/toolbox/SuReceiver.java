package com.rc2.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * The other half of the {@code su} bridge.
 *
 * <p>A shell script cannot speak the ADB protocol, and DJI replaced
 * {@code /system/bin/content} with a stub that prints nothing (its
 * {@code content.jar} is absent), so the obvious {@code content call} route is
 * dead on this firmware. A broadcast is the one channel that works from an
 * arbitrary uid *and* wakes this process when it is not running.
 *
 * <p>The caller writes its command into a spool file, sends us the two paths,
 * and waits for the answer to appear. Nothing about the command comes from the
 * Intent itself, so a malformed or hostile broadcast can at worst ask us to run
 * something that is already in a world-writable directory — which any local app
 * could do directly anyway. The privilege this grants is the same unauthenticated
 * root adbd the firmware already exposes.
 */
public class SuReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.rc2.toolbox.action.SU";

    /** 与 KsuActivity.ALLOW 必须一致：每行一个包名，{@code *} 表示全部放行。 */
    private static final String ALLOW = "/data/adb/ksu/.nexus_allow";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        Bundle e = intent.getExtras();
        if (e == null) {
            return;
        }
        final String req = e.getString("req");
        final String out = e.getString("out");
        if (req == null || out == null) {
            return;
        }
        // onReceive must return promptly; the bridge does real I/O.
        final PendingResult pr = goAsync();
        // 调用者身份只能从 spool 文件的属主 uid 取：Intent 里的 extra 谁都能伪造，
        // 但 req 文件是调用者自己创建的，属主就是它的 uid。
        final String reqPath = req;
        new Thread(new Runnable() {
            public void run() {
                try {
                    int callerUid = ownerUid(reqPath);
                    String who = nameOf(callerUid);
                    if (!allowed(callerUid, who)) {
                        publish(out, "nexus-su: 拒绝 " + who + " (uid " + callerUid
                                + ")：未在 RC2NEXUS「超级用户」页授权\n", 1);
                        return;
                    }
                    String cmd = "";
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(
                                        new java.io.FileInputStream(req), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        r.close();
                        cmd = sb.toString().trim();
                    } catch (Throwable t) {
                        cmd = "";
                    }

                    AdbShell.Result res = cmd.length() == 0
                            ? AdbShell.run("id", 30000)
                            : AdbShell.run(cmd, 120000);

                    String text = res.text();
                    if (cmd.length() == 0) {
                        text = "nexus-su: empty command\n" + text;
                    }
                    publish(out, text, res.code);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    /** 取 spool 文件属主 uid；读不到就返回 -1（永不匹配白名单）。 */
    private static int ownerUid(String path) {
        AdbShell.Result res = AdbShell.run("stat -c %u '" + path + "' 2>/dev/null", 15000);
        String t = res.text().trim();
        int nl = t.indexOf('\n');
        if (nl > 0) { t = t.substring(0, nl).trim(); }
        try {
            return Integer.parseInt(t);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** uid -> 可读名字，用于拒绝提示与白名单匹配。 */
    private static String nameOf(int uid) {
        if (uid == 0) { return "root"; }
        if (uid == 1000) { return "system"; }
        if (uid == 2000) { return "shell"; }
        if (uid < 0) { return "unknown"; }
        AdbShell.Result res = AdbShell.run(
                "pm list packages -U 2>/dev/null | grep -E 'uid:" + uid + "( |$)' | head -1",
                15000);
        String t = res.text().trim();
        int i = t.indexOf("package:");
        if (i >= 0) {
            int j = t.indexOf(' ', i);
            return j > 0 ? t.substring(i + 8, j) : t.substring(i + 8);
        }
        return "uid " + uid;
    }

    /**
     * 白名单判定。文件 {@code /data/adb/ksu/.nexus_allow} 每行一个包名，
     * 支持 {@code *} 表示全部放行；root/system/shell 永远放行。
     */
    private static boolean allowed(int uid, String who) {
        if (uid == 0 || uid == 1000 || uid == 2000) { return true; }
        String list;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(ALLOW), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) { sb.append(line).append('\n'); }
            r.close();
            list = sb.toString();
        } catch (Throwable t) {
            list = "";
        }
        if (list.trim().length() == 0) {
            // 没写过白名单：沿用原行为（放行），避免升级后把用户锁在外面。
            AdbShell.Result res = AdbShell.run(
                    "if [ -f " + ALLOW + " ]; then cat " + ALLOW + "; fi", 15000);
            list = res.text();
            if (list.trim().length() == 0) { return true; }
        }
        for (String raw : list.split("\n")) {
            String tok = raw.trim();
            if (tok.length() == 0) { continue; }
            if ("*".equals(tok)) { return true; }
            if (tok.equals(who)) { return true; }
        }
        return false;
    }

    /**
     * Writes the answer where the caller (any uid) can read it.
     *
     * <p>顺序很关键：{@code .rc} 先落地、结果文件最后用 {@code mv} 原子出现。
     * 调用方一看到结果文件就立刻读退出码，谁先谁后写反了就会偶发读到默认值。
     * 直接写结果文件的 Java 分支只作兜底（root 通道不可用时才走到）。
     */
    private static void publish(String path, String text, int code) {
        String b64 = android.util.Base64.encodeToString(
                text.getBytes(), android.util.Base64.NO_WRAP);
        AdbShell.Result r = AdbShell.run(
                "echo " + code + " > '" + path + ".rc' && chmod 0666 '" + path + ".rc' && "
              + "echo " + b64 + " | base64 -d > '" + path + ".tmp' && "
              + "chmod 0666 '" + path + ".tmp' && mv '" + path + ".tmp' '" + path + "'", 30000);
        if (r.code == 0) {
            return;
        }
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(path + ".rc");
            f.write(String.valueOf(code).getBytes("UTF-8"));
            f.close();
            f = new java.io.FileOutputStream(path);
            f.write(text.getBytes("UTF-8"));
            f.close();
        } catch (Throwable ignored) {
            // nothing else to try
        }
    }
}
