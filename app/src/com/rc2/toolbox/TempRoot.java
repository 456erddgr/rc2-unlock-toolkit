package com.rc2.toolbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 临时 root。
 *
 * <p>做法照搬社区方案（dev.rc331.rootadb）：设备上先被安装好一套一次性载荷，
 * 里面最关键的是 {@code /dji_apk/.adb-dev/adb-root-launcher} —— 一个
 * setuid(4755) 的小程序。任何应用执行它，进程的 euid 就变成 0，它会以 root
 * 身份 execve {@code /data/adb-persist/start-root-adbd.sh}；那个脚本再用
 * {@code am attach-agent system …} 把一段「全 capability」的脚本注入
 * system_server，由它停掉系统自带 adbd，并在 5555 端口拉起源码里自带的
 * {@code adbd-root --root_seclabel=u:r:su:s0}。
 *
 * <p>所以真正的 root 是那个 adbd-root 给的（{@link AdbShell} 连的就是它），
 * 启动器只是每次重启之后把 root 找回来的那把钥匙。本类只负责转这把钥匙，
 * 不自己实现注入。
 */
public final class TempRoot {

    /** setuid 启动器。 */
    public static final String LAUNCHER = "/dji_apk/.adb-dev/adb-root-launcher";
    /** 载荷的持久化目录。 */
    public static final String PERSIST = "/data/adb-persist";
    /** 出现这个文件时，注入式启动会被脚本主动跳过。 */
    public static final String DISABLE_FLAG = PERSIST + "/disable-custom-adbd";
    /** 注入启动器验证过 CapEff 之后才会创建它。 */
    public static final String READY_FLAG = PERSIST + "/root-adbd-ready";
    /** adbd-root 的 pid。 */
    public static final String PID_FILE = PERSIST + "/root-adbd.pid";
    /** 启动与注入日志。 */
    public static final String LOG_FILE = PERSIST + "/root-adbd.log";

    private TempRoot() { }

    /** 载荷是否已装到本机（决定这个功能能不能用）。 */
    public static boolean installed() {
        try {
            return new File(LAUNCHER).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否被维护标记禁用。 */
    public static boolean disabled() {
        try {
            return new File(DISABLE_FLAG).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上一次注入是否留下 ready 标记。 */
    public static boolean readyFlag() {
        try {
            return new File(READY_FLAG).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 现在能不能拿到 uid=0；拿得到就返回那一行身份串。 */
    public static String currentIdentity() {
        int port = AdbShell.findPort();
        if (port <= 0) {
            return null;
        }
        String id = AdbShell.identity();
        if (id == null || id.indexOf("uid=0") < 0) {
            return null;
        }
        return id;
    }

    /**
     * 转一次钥匙：执行 setuid 启动器，然后等 root 通道真的变成 uid=0。
     *
     * @param maxMs 最多等多久（脚本自己要等注入完成，给够时间）
     * @return 多行报告，直接落盘/显示都行
     */
    public static String start(long maxMs) {
        StringBuilder sb = new StringBuilder();
        long t0 = System.currentTimeMillis();
        sb.append("launcher=").append(LAUNCHER).append('\n');
        if (!installed()) {
            sb.append("ok=false\n");
            sb.append("reason=启动器不存在，本机没有装那套载荷\n");
            return sb.toString();
        }
        if (disabled()) {
            sb.append("warn=存在维护标记 ").append(DISABLE_FLAG).append("，脚本会跳过启动\n");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(LAUNCHER);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final StringBuilder out = new StringBuilder();
            Thread drain = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (out.length() < 4000) {
                                out.append(line).append('|');
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            });
            drain.setDaemon(true);
            drain.start();

            String id = waitForRoot(maxMs);
            sb.append("elapsed_ms=").append(System.currentTimeMillis() - t0).append('\n');
            sb.append("identity=").append(id == null ? "" : id).append('\n');
            sb.append("exit=").append(exitOf(p)).append('\n');
            sb.append("launcher.out=").append(out).append('\n');
            sb.append("ok=").append(id != null).append('\n');
        } catch (Throwable t) {
            sb.append("elapsed_ms=").append(System.currentTimeMillis() - t0).append('\n');
            sb.append("ok=false\n");
            sb.append("throw=").append(t).append('\n');
        }
        return sb.toString();
    }

    /** 轮询到 root 通道给出 uid=0，或超时返回 null。 */
    private static String waitForRoot(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            String id = currentIdentity();
            if (id != null) {
                return id;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    private static String exitOf(Process p) {
        try {
            return String.valueOf(p.exitValue());
        } catch (Throwable stillRunning) {
            return "running";
        }
    }
}
