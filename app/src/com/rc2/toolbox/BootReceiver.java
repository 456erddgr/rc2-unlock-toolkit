package com.rc2.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后的自愈，三件事：
 *   1. Rescue —— /system/bin 被挂载点盖住时把 shell 拉回来；
 *   2. MemActivity.reapply —— 重新挂上内存扩展的换页；
 *   3. restoreDji —— 大疆的两个工程包每次重启都会被系统禁掉，
 *      这里重新 pm enable，桌面图标才会回来。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            return;
        }

        // goAsync() keeps the process alive while the work runs.
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 安全网：如果 /system/bin 被挂载点盖住了，adb 的 shell 服务
                    // 就彻底失效（它写死了 exec /system/bin/sh）。外面再也没有
                    // 办法操作这台设备，只有 App 自己能把它拉回来。
                    try { Rescue.run(ctx); } catch (Throwable ignored) { }

                    // 内存扩展：先把换页重新挂上。
                    try { MemActivity.reapply(ctx); } catch (Throwable ignored) { }
                    // 大疆工厂包：系统每次重启都会把它们重新禁掉，桌面图标跟着消失。
                    try { restoreDji(); } catch (Throwable ignored) { }

                    // root ADB 重启后一定掉（系统自带 adbd 会回来占住 5555）。
                    // 社区包的做法就是开机自动把注入链再跑一遍，用户在桌面上
                    // 什么都不用点。这里照做，但只在「载荷装好了、没被维护标记
                    // 禁用、当前确实不是 root」这三个条件同时成立时才动手 ——
                    // 避免每次开机都无谓地拉起一遍注入。
                    try { restoreRootAdb(); } catch (Throwable ignored) { }

                    // su 的 PATH 入口是 tmpfs 遮蔽，重启后必然消失；用户装过
                    // 就要在 root 通道回来之后补挂，否则重启一次 su 就没了。
                    // 顺序不能反：补挂要 root（走 adbd-root），所以放在后面。
                    try { restorePathSu(); } catch (Throwable ignored) { }
                } catch (Throwable ignored) {
                } finally {
                    try { pending.finish(); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    /**
     * 开机后把 root ADB 拉回来。
     *
     * <p>重启之后系统自带 adbd 会重新占住 5555，root 通道就没了。社区包
     * （dev.rc331.rootadb）的 BootReceiver 在开机广播里直接跑一遍启动器，
     * 这里沿用同样的思路，但加了三道闸：
     *
     * <ol>
     *   <li>载荷必须已经装在机器上（{@link TempRoot#installed()}）；
     *   <li>不能存在维护标记（{@link TempRoot#DISABLE_FLAG}）—— 用户想手动
     *       接管的时候，开机自启必须让路；
     *   <li>当前必须真的还不是 root，否则每次开机都白跑一遍注入。
     * </ol>
     *
     * <p>广播的 onReceive 只有 10 秒预算，所以这里不等待结果：启动器自己
     * 会在后台把注入跑完，状态由「root ADB 状态」那张卡随时查看。
     */
    private static void restoreRootAdb() {
        if (!TempRoot.installed()) return;
        if (TempRoot.disabled()) return;
        if (TempRoot.currentIdentity() != null) return;

        // 给系统一点时间把服务都起来，再跑注入；否则 zygote_secondary
        // 的 socket 可能还没准备好，am attach-agent 会失败。
        try {
            Thread.sleep(8000L);
        } catch (InterruptedException e) {
            return;
        }

        // 再确认一次：等这 8 秒的工夫里，用户可能自己点了「一键找回」。
        if (TempRoot.currentIdentity() != null) return;

        // 不调 TempRoot.start()（那会阻塞等 90 秒），只把启动器踢出去。
        try {
            ProcessBuilder pb = new ProcessBuilder(TempRoot.LAUNCHER);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 输出必须排空，否则管道写满会把启动器卡死。
            drain(p);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 重启后把 su 的 PATH 入口补回来。
     *
     * <p>{@code /product/bin} 用的是 tmpfs 遮蔽（只读分区没法直接写文件），
     * 而 tmpfs 是内存态，每次重启都会消失。这里等 root 通道回来后再挂一遍。
     *
     * <p>必须甩到独立线程里 fire-and-forget：等待 adb 端口最长要一分钟，
     * 而广播接收器（哪怕已经 goAsync）的预算只有几十秒，占着不放会被系统
     * 直接杀掉，等于自愈从来没跑过。这里踢完线程立刻返回，让
     * {@code pending.finish()} 尽快执行。
     */
    private static void restorePathSu() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 12; i++) {
                    try {
                        if (AdbShell.findPort() > 0) {
                            String r = KsuActivity.reapplyPathSu();
                            android.util.Log.i("RC2BOOT", "reapplyPathSu: " + r);
                            // logcat 关机就没了，而「重启后 su 还在不在」正是
                            // 用户唯一能感知的点，所以落一份到 /data 上。
                            AdbShell.run("echo \"$(date) reapply: " + r
                                    + "\" >> " + KsuActivity.BOOT_LOG, 20000);
                            return;
                        }
                    } catch (Throwable ignored) { }
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                android.util.Log.w("RC2BOOT", "reapplyPathSu: root 通道一直没起来，放弃");
                try {
                    AdbShell.run("echo \"$(date) reapply: root 通道未就绪，放弃\" >> "
                            + KsuActivity.BOOT_LOG, 20000);
                } catch (Throwable ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 把子进程的 stdout/stderr 读干，避免它因为管道满而阻塞。 */
    private static void drain(final Process p) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    while (r.readLine() != null) { /* 丢弃 */ }
                    r.close();
                } catch (Throwable ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把大疆的两个工程包放回桌面。
     *
     * 出厂固件把 com.dpad.fuli（开发助手）和 com.dpad.devicetest（产测 2.0）都设成
     * disabled，所以桌面上根本没有图标；而且系统每次重启都会把它们再禁一遍。
     * 这里在开机后重新 pm enable --user 0，图标就回来了。
     *
     * 故意不碰 com.dpad.setup：它是首次开机向导，一启用设备就会自己重启一次。
     */
    private static void restoreDji() {
        String[] pkgs = { "com.dpad.fuli", "com.dpad.devicetest" };
        for (int i = 0; i < pkgs.length; i++) {
            String gone = AdbShell.run(
                    "pm list packages -d | grep -c " + pkgs[i], 20000).text().trim();
            if ("0".equals(gone)) continue;
            AdbShell.run("pm enable --user 0 " + pkgs[i], 30000);
        }
    }
}
