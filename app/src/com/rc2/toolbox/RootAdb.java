package com.rc2.toolbox;

import android.os.SystemClock;

import java.io.File;

/**
 * 持久 root ADB 与 Fastboot 入口。
 *
 * <p>这一层的方案来自社区包 dev.rc331.rootadb（RC331 Root ADB），本类只把它的
 * 两个能力搬进 RC2NEXUS，让用户不需要再装第二个 App：
 *
 * <p><b>1. 持久 root ADB 的状态与拉起</b><br>
 * 真正的 root 通道是 {@code /data/adb-persist/adbd-root} 在 5555 端口上提供的
 * adbd（{@code u:r:su:s0}、完整 capability）。设备重启后它不会自己回来，需要
 * 重新执行一次 setuid 启动器把注入链跑一遍 —— 那件事由 {@link TempRoot} 负责。
 * 本类负责的是「状态」：现在适配器在不在、pid 是多少、ready 标记有没有、日志
 * 最后说了什么。{@link #state()} 把这几个事实一次性收集成一张卡片。
 *
 * <p><b>2. Fastboot 入口（三级回退）</b><br>
 * 这是 Bundle 里唯一一个和 root ADB 无关、但很有用的功能：重启进 bootloader。
 * 社区实现用的回退顺序是
 * <pre>
 *   svc power reboot bootloader
 *   setprop sys.powerctl reboot,bootloader
 *   /system/bin/reboot bootloader
 * </pre>
 * 三条路谁先成功就停。这里保留同样的顺序，并且**每条都要先确认工具真的存在**，
 * 因为这台机器上 {@code /system/bin} 有可能被挂载点盖住（见 {@link Rescue}）。
 *
 * <p>注意：锁着 BL 的 RC 2 进 fastboot 之后只能靠 {@code fastboot continue} /
 * {@code fastboot reboot} 回来，刷写操作会把机器变砖。所以 {@link #NEED_ROOT}
 * 那套提示必须原样展示给用户，不要在 UI 上弱化。
 */
public final class RootAdb {

    /** 载荷持久化目录（与 {@link TempRoot} 共用）。 */
    public static final String PERSIST = TempRoot.PERSIST;
    /** adbd-root 监听的端口。 */
    public static final int TCP_PORT = 5555;
    /** 日志文件。 */
    public static final String LOG_FILE = PERSIST + "/root-adbd.log";
    /** 维护标记：存在时注入式启动会被跳过。 */
    public static final String DISABLE_FLAG = PERSIST + "/disable-custom-adbd";

    /** Fastboot 页面必须原样转达的救援说明。 */
    public static final String[] NEED_ROOT = {
        "机器已经进入 bootloader（FASTBOOT 界面）。",
        "",
        "回来只需要两种命令，二选一：",
        "  1. fastboot devices        —— 先确认 PC 认到机器",
        "  2. fastboot getvar product —— 确认型号（应为 rc331）",
        "  3. fastboot continue       —— 继续正常启动（推荐）",
        "     fastboot reboot         —— 直接重启",
        "",
        "不要在这台机器上执行 fastboot flash / erase / flashing unlock：",
        "RC 2 的 bootloader 锁定后没有可刷回的分区镜像，"
            + "一旦刷写或解锁就无法恢复。",
    };

    private RootAdb() { }

    /** 一次完整的 root ADB 状态快照。 */
    public static final class State {
        /** 载荷是否装在机器上（决定功能可用性）。 */
        public boolean installed;
        /** 是否存在维护标记（脚本会主动跳过启动）。 */
        public boolean disabled;
        /** ready 标记是否存在且非空。 */
        public boolean ready;
        /** adbd-root 的 pid 文件内容（空串表示没有）。 */
        public String pid = "";
        /** 5555 端口是否真的有人在听。 */
        public boolean listening;
        /** 当前 {@link AdbShell} 拿到的身份串（uid=0 才算 root）。 */
        public String identity = "";
        /** 一句话结论，直接给 UI 用。 */
        public String summary = "";

        /** 是否处于「能直接 root」的可用状态。 */
        public boolean usable() {
            return installed && listening && identity != null && identity.indexOf("uid=0") >= 0;
        }
    }

    /** 收集一次状态。全部通过文件与 socket 探测，不依赖 shell。 */
    public static State state() {
        State s = new State();
        s.installed = TempRoot.installed();
        s.disabled = TempRoot.disabled();

        // /data/adb-persist 是 0700 root:root，App（uid 10030）既读不了目录
        // 也 stat 不到里面的文件 —— File.exists() 会静默返回 false。
        // 所以状态文件必须走 root 通道读；一次 shell 把两个都取回来。
        String probe = AdbShell.line(
                "if [ -e " + TempRoot.READY_FLAG + " ]; then echo READY=1; else echo READY=0; fi; "
              + "echo PID=$(cat " + TempRoot.PID_FILE + " 2>/dev/null)");
        if (probe != null) {
            for (String line : probe.split("\n")) {
                line = line.trim();
                if (line.startsWith("READY=")) {
                    // 脚本用 touch 创建这个文件，它是空文件 —— 存在即就绪。
                    s.ready = "READY=1".equals(line);
                } else if (line.startsWith("PID=")) {
                    s.pid = line.substring(4).trim();
                }
            }
        }

        int port = AdbShell.findPort();
        s.listening = port > 0;
        if (s.listening) {
            String id = AdbShell.identity();
            s.identity = id == null ? "" : id;
        }

        if (!s.installed) {
            s.summary = "本机没有装那套载荷，root ADB 用不了";
        } else if (s.disabled) {
            s.summary = "存在维护标记，注入式启动被跳过";
        } else if (s.usable()) {
            s.summary = "root ADB 在线（TCP " + TCP_PORT + "）"
                    + (s.pid.length() > 0 ? " pid=" + s.pid : "");
        } else if (s.listening) {
            s.summary = "端口 " + port + " 有服务，但当前不是 uid=0";
        } else {
            s.summary = "root ADB 不在线，需要「转一次钥匙」重新拉起";
        }
        return s;
    }

    /** 日志最后 n 行；读不到就返回空串。 */
    public static String tailLog(int n) {
        try {
            // 日志可能很大（实测过 3.7MB），而且 /data/adb-persist 是 0700，
            // 所以只能交给 root 通道去 tail，不能自己 File 读。
            String out = AdbShell.line("tail -n " + n + " " + LOG_FILE + " 2>/dev/null");
            return out == null ? "" : out;
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- fastboot

    /** 一次 fastboot 尝试的结果。 */
    public static final class Fastboot {
        /** 实际生效的那条命令；全失败时为空串。 */
        public String usedCommand = "";
        /** 逐条尝试的记录，直接显示。 */
        public String log = "";
        /** 有没有成功踢出去。 */
        public boolean ok;

        /**
         * 命令返回了但设备未必真的重启（比如 svc 存在但被 SELinux 挡住），
         * 所以这里只报告「命令被接受」，真正的确认留给用户看屏幕。
         */
        public String verdict() {
            if (ok) {
                return "已发出进入 bootloader 的指令（" + usedCommand + "）。\n"
                        + "屏幕应该很快切到 FASTBOOT 界面。";
            }
            return "三条路都没能发出重启指令，详见下面的记录。";
        }
    }

    /**
     * 重启进 bootloader，三级回退。
     *
     * <p>顺序照搬社区实现。每条命令执行前都先确认程序存在，避免在
     * {@code /system/bin} 被盖住的机器上拿到一堆 "No such file or directory"。
     */
    public static Fastboot rebootBootloader() {
        Fastboot r = new Fastboot();
        StringBuilder log = new StringBuilder();
        long t0 = SystemClock.elapsedRealtime();

        // 1) svc power reboot bootloader —— 最正规的一条。
        if (attempt(r, log, "svc power reboot bootloader",
                cmdExists("svc"),
                "/system/bin/svc 不存在")) {
            r.usedCommand = "svc power reboot bootloader";
            r.ok = true;
        }

        // 2) setprop sys.powerctl —— init 直接接管，不经过 svc。
        if (!r.ok) {
            if (attempt(r, log, "setprop sys.powerctl reboot,bootloader",
                    cmdExists("setprop"),
                    "/system/bin/setprop 不存在")) {
                r.usedCommand = "setprop sys.powerctl reboot,bootloader";
                r.ok = true;
            }
        }

        // 3) /system/bin/reboot bootloader —— 最后的兜底。
        if (!r.ok) {
            if (attempt(r, log, "/system/bin/reboot bootloader",
                    new File("/system/bin/reboot").exists(),
                    "/system/bin/reboot 不存在")) {
                r.usedCommand = "/system/bin/reboot bootloader";
                r.ok = true;
            }
        }

        log.append("elapsed_ms=").append(SystemClock.elapsedRealtime() - t0).append('\n');
        log.append("result=").append(r.ok ? "sent" : "all-failed").append('\n');
        r.log = log.toString();
        return r;
    }

    /** 单条尝试；条件不满足或执行抛异常都算失败。 */
    private static boolean attempt(Fastboot r, StringBuilder log,
                                   String command, boolean condition, String why) {
        if (!condition) {
            log.append("skip : ").append(command).append('\n');
            log.append("       ").append(why).append('\n');
            return false;
        }
        try {
            AdbShell.Result res = AdbShell.run(command, 8000);
            String text = res == null ? "" : res.text().trim();
            log.append("try  : ").append(command).append('\n');
            log.append("       exit=").append(res == null ? -1 : res.code)
               .append(text.length() == 0 ? " (无输出)" : "").append('\n');
            if (text.length() > 0) {
                log.append("       ").append(text.replace('\n', ' ')).append('\n');
            }
            return res != null && res.code == 0;
        } catch (Throwable t) {
            log.append("try  : ").append(command).append('\n');
            log.append("       throw=").append(t).append('\n');
            return false;
        }
    }

    /** 命令是否既在 /system/bin 里、也在 PATH 上能找到。 */
    private static boolean cmdExists(String name) {
        if (new File("/system/bin/" + name).exists()) return true;
        try {
            String p = AdbShell.line("command -v " + name + " 2>/dev/null");
            return p != null && p.trim().length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
