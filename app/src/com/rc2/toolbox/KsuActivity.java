package com.rc2.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The root manager: everything KernelSU's own app does, wired to the root path
 * this device actually has.
 *
 * <p>KernelSU's real engine lives in a patched kernel. This RC 2 runs a stock
 * DJI 5.4.210-qgki kernel with no KernelSU code in it, so the manager can be
 * installed but the kernel half cannot be conjured from userspace. What this
 * screen does instead is honest and, on this device, more useful: it exposes an
 * equivalent capability over the unauthenticated root adbd this firmware already
 * ships on 127.0.0.1:5555, and gives it KernelSU's shape — a superuser allowlist,
 * a module tree at /data/adb/modules, and a {@code su} entry point apps can call.
 */
public class KsuActivity extends Activity {

    private static final String MANAGER = "KernelSU.apk";
    private static final String KSU_DIR = "/data/adb/ksu";
    private static final String SOFT_REBOOT =
            "if getprop init.svc.zygote_secondary | grep -q running; then setprop ctl.restart zygote_secondary; "
            + "elif getprop init.svc.zygote | grep -q running; then setprop ctl.restart zygote; "
            + "else setprop ctl.restart zygote_secondary; fi";

    private static final String KSU_BIN = KSU_DIR + "/bin";
    private static final String MODULE_DIR = "/data/adb/modules";
    private static final String ALLOW = KSU_DIR + "/.nexus_allow";
    private static final String STAGE = "/data/local/tmp/rc2-" + MANAGER;
    /** 系统文件选择器的请求码：挑模块 zip。 */
    private static final int REQ_PICK_ZIP = 0x5A17;
    private static final String TEMP_LOG = "/data/adb-persist/nexus-temproot.log";

    /** 当前白名单原文，勾选界面用它回显。 */
    private String allowText = "";

    /**
     * KernelSU/Magisk CLI-compatible su, routed through the broadcast bridge.
     *
     * <p>实测通过的调用形式（全部在真实 app uid 10057 下跑过）：
     * {@code su -c "id"}、{@code echo id | su}、{@code su 0 id}、
     * {@code su -v} / {@code su -V} / {@code su --version}。管道、引号、
     * 分号都靠 {@code -c} 原样透传，所以 {@code su -c "ls | head -3"} 也对。
     *
     * <p>{@code --user 0} 不能省：不指定 user 时 am 会按调用者所属用户解析，
     * 非 app 的裸 uid 直接抛 ActivityManagerService 异常、一个字节都不回。
     */
    private static final String SU_SHIM =
            "#!/system/bin/sh\n"
          + "# NEXUS su - KernelSU/Magisk compatible su front-end.\n"
          + "# Routes commands through the RC2 NEXUS privileged bridge (root adbd).\n"
          + "V=$(cat " + KSU_DIR + "/.nexus_ver 2>/dev/null); [ -z \"$V\" ] && V=4.6\n"
          + "VER=\"$V:NEXUSSU:MAGISKSU\"\n"
          + "SHX=/system/bin/sh\n"
          + "CMD=\"\"; LOGIN=0; USER=root\n"
          + "while [ $# -gt 0 ]; do\n"
          + "  case \"$1\" in\n"
          + "    -c|--command) shift; CMD=\"$1\"; shift ;;\n"
          + "    -s|--shell) shift; SHX=\"$1\"; shift ;;\n"
          + "    -Z|--context) shift; shift ;;\n"
          + "    -t|--target) shift; shift ;;\n"
          + "    -g|--group|-G|--supp-group) shift; shift ;;\n"
          + "    -M|-mm|--mount-master) shift ;;\n"
          + "    -l|-|--login) LOGIN=1; shift ;;\n"
          + "    -m|-p|--preserve-environment) shift ;;\n"
          + "    -v|--version) echo \"$VER\"; exit 0 ;;\n"
          + "    -V) echo \"${VER%%:*}\"; exit 0 ;;\n"
          + "    -h|--help) echo \"usage: su [options] [-] [user [argument...]]\"; exit 0 ;;\n"
          + "    --) shift; break ;;\n"
          + "    -*) shift ;;\n"
          + "    *) USER=\"$1\"; shift; break ;;\n"
          + "  esac\n"
          + "done\n"
          + "if [ -z \"$CMD\" ] && [ $# -gt 0 ]; then\n"
          + "  for a in \"$@\"; do CMD=\"$CMD $a\"; done\n"
          + "fi\n"
          + "# 没有 -c：从 stdin 读脚本（这正是 echo id | su 的含义）。\n"
          + "if [ -z \"$CMD\" ]; then\n"
          + "  if [ -t 0 ]; then CMD=\"$SHX\"; else CMD=$(cat); fi\n"
          + "fi\n"
          + "[ -z \"$CMD\" ] && CMD=\"$SHX\"\n"
          + "BASE=/data/local/tmp/nexus-su\n"
          + "mkdir -p $BASE 2>/dev/null\n"
          + "grep -q \" $BASE \" /proc/mounts 2>/dev/null || mount -t tmpfs -o mode=0777 tmpfs $BASE 2>/dev/null\n"
          + "chmod 0777 $BASE 2>/dev/null\n"
          + "REQ=$BASE/req.$$; OUT=$BASE/out.$$\n"
          + "rm -f \"$OUT\" \"$OUT.rc\"\n"
          + "printf '%s' \"$CMD\" > \"$REQ\" 2>/dev/null || exit 1\n"
          + "chmod 0666 \"$REQ\" 2>/dev/null\n"
          + "# am 的输出要留着：未知 uid（没有对应包名）会被 AMS 直接拒绝，\n"
          + "# 那种情况下唯一的线索就是它报的 SecurityException。\n"
          + "AM=$(am broadcast --user 0 -a com.rc2.toolbox.action.SU \\\n"
          + "  -n com.rc2.toolbox/.SuReceiver --es req \"$REQ\" --es out \"$OUT\" 2>&1)\n"
          + "# 没有对应包名的 uid（比如 u0_a101）会被 AMS 直接拒绝：\n"
          + "#   java.lang.IllegalArgumentException: Unable to determine caller package name\n"
          + "# 这种调用根本不会送达 receiver，没人会来写结果，死等 30 秒纯属浪费。\n"
          + "# 只有真正送达（Broadcast completed）才继续等。\n"
          + "case \"$AM\" in\n"
          + "  *\"Broadcast completed\"*|*\"result=\"*) : ;;\n"
          + "  *\"Unable to determine caller package name\"*|*IllegalArgumentException*|*SecurityException*)\n"
          + "    echo \"nexus-su: 以 uid $(id -u) 调用失败：本机没有与该 uid 对应的已安装应用\" >&2\n"
          + "    printf '%s\\n' \"$AM\" >&2\n"
          + "    rm -f \"$REQ\"; exit 1 ;;\n"
          + "esac\n"
          + "i=0\n"
          + "while [ $i -lt 600 ]; do\n"
          + "  if [ -f \"$OUT\" ]; then\n"
          + "    cat \"$OUT\"\n"
          + "    RC=0\n"
          + "    [ -f \"$OUT.rc\" ] && RC=$(cat \"$OUT.rc\" 2>/dev/null)\n"
          + "    rm -f \"$OUT\" \"$OUT.rc\" \"$REQ\"\n"
          + "    # 退出码必须透传：脚本里 `su -c ... || 兜底` 就靠这个。\n"
          + "    case \"$RC\" in ''|*[!0-9]*) RC=0 ;; esac\n"
          + "    exit $RC\n"
          + "  fi\n"
          + "  sleep 0.05\n"
          + "  i=$((i+1))\n"
          + "done\n"
          + "echo \"nexus-su: bridge timeout\" >&2\n"
          + "[ -n \"$AM\" ] && printf '%s\\n' \"$AM\" >&2\n"
          + "rm -f \"$REQ\"\n"
          + "exit 1\n";

    /**
     * 把 su 放进**所有进程都能解析到的 PATH** 里。
     *
     * <p>这是整条链路唯一有技术含量的地方，实测依据：
     * <ul>
     *   <li>{@code /system}、{@code /product}、{@code /vendor} 都是 dm-verity
     *       只读分区，{@code mkdir /system/xbin} 必然失败（Read-only file system），
     *       rootfs 也 {@code remount,rw} 不动。</li>
     *   <li>但挂载命名空间在 root shell 与应用之间是**共享传播**的：在 root shell
     *       里 {@code mount -t tmpfs}，同一时刻 {@code /proc/<app pid>/mounts} 里
     *       就能看到这一条；zygote 也能看到，所以之后新起的进程同样看得到。</li>
     *   <li>应用 PATH 的头一项是 {@code /product/bin}，而它只装了一个文件
     *       （{@code init.qti.display.sh}）。</li>
     * </ul>
     * 所以做法是：先用 tmpfs 盖住 {@code /product/bin}，再把原文件从持久备份
     * 复制回去，最后放 su。代价是只遮蔽一个 1.4KB 的脚本，且原件仍可见。
     *
     * <p>重启后 tmpfs 消失、/product/bin 自动恢复原样，不留任何痕迹。
     */
    private static final String PATH_DIR = "/product/bin";
    /** 原件备份放这儿：/data 可写且重启后仍在。 */
    private static final String PATH_BAK = KSU_DIR + "/.orig-product-bin";
    /**
     * 「用户主动装过 su」的持久标记。
     *
     * <p>tmpfs 遮蔽是内存态的，重启必然消失，所以 {@link BootReceiver}
     * 只能靠这个标记判断「该不该开机自动补挂」——没有它就没法区分
     * 「用户装过、被重启抹掉了」和「用户从来没装过」。卸载时删掉。
     */
    static final String PATH_MARK = KSU_DIR + "/.nexus_pathsu";
    /** 开机自愈的持久日志；logcat 关机即失，这个留着可事后追查。 */
    static final String BOOT_LOG = KSU_DIR + "/nexus-boot.log";
    /** 应用进程的实际 PATH（取自 /proc/<pid>/environ），自检时照抄。 */
    private static final String APP_PATH = "/product/bin:/apex/com.android.runtime/bin:"
            + "/apex/com.android.art/bin:/system_ext/bin:/system/bin";

    static final String INSTALL_PATH =
            "set -e\n"
          + "D=" + PATH_DIR + "\n"
          + "B=" + PATH_BAK + "\n"
          + "SRC=/data/local/tmp/nexus-su.sh\n"
          + "mkdir -p \"$B\" " + KSU_BIN + "\n"
          + "# 每次都以「当前真实可见的内容」重建备份：若 tmpfs 还挂着就先卸掉，\n"
          + "# 否则会把上一次的 su 一起备份进去。\n"
          + "if grep -q \" $D \" /proc/mounts 2>/dev/null; then umount \"$D\" 2>/dev/null || true; fi\n"
          + "rm -rf \"$B\"; mkdir -p \"$B\"; cp -a \"$D\"/. \"$B\"/ 2>/dev/null || true\n"
          + "mount -t tmpfs -o mode=0755 tmpfs \"$D\"\n"
          + "cp -a \"$B\"/. \"$D\"/ 2>/dev/null || true\n"
          + "cp \"$SRC\" \"$D/su\"\n"
          + "chmod 0755 \"$D/su\"; chown 0:0 \"$D/su\" 2>/dev/null || true\n"
          + "# 持久标记：tmpfs 重启即失，开机自愈靠它判断用户是否装过。\n"
          + "echo \"$D\" > " + PATH_MARK + "; chmod 0644 " + PATH_MARK + "\n"
          + "ls -la \"$D\"\n";

    /** 卸载 tmpfs，/product/bin 原样回来，并清掉开机自愈标记。 */
    static final String UNINSTALL_PATH =
            "if grep -q \" " + PATH_DIR + " \" /proc/mounts 2>/dev/null; then "
          + "umount " + PATH_DIR + " && echo uninstalled; else echo \"no shadow\"; fi\n"
          + "rm -f " + PATH_MARK + "\n";

    /**
     * 开机自愈：tmpfs 遮蔽是内存态，重启后必须重新挂。
     *
     * <p>与 {@link #INSTALL_PATH} 的唯一区别是 shim 来源——重启后
     * {@code /data/local/tmp/nexus-su.sh} 可能被 init 清理，所以一律从
     * {@code /data/adb/ksu/bin/su}（应用自己写的持久副本）取。
     */
    static final String REAPPLY_PATH =
            "set -e\n"
          + "D=" + PATH_DIR + "\n"
          + "B=" + PATH_BAK + "\n"
          + "SRC=" + KSU_BIN + "/su\n"
          + "[ -x \"$SRC\" ] || { echo \"no persistent su\"; exit 3; }\n"
          + "grep -q \" $D \" /proc/mounts 2>/dev/null && { echo \"already shadowed\"; exit 0; }\n"
          + "mkdir -p \"$B\"\n"
          + "rm -rf \"$B\"; mkdir -p \"$B\"; cp -a \"$D\"/. \"$B\"/ 2>/dev/null || true\n"
          + "mount -t tmpfs -o mode=0755 tmpfs \"$D\"\n"
          + "cp -a \"$B\"/. \"$D\"/ 2>/dev/null || true\n"
          + "cp \"$SRC\" \"$D/su\"\n"
          + "chmod 0755 \"$D/su\"; chown 0:0 \"$D/su\" 2>/dev/null || true\n"
          + "echo reapplied\n";

    /**
     * 供 {@link BootReceiver} 调用：重启后按持久标记补挂 su。
     *
     * <p>只有用户真的装过（{@link #PATH_MARK} 存在）才动手，避免给没装过的
     * 用户凭空挂一个 tmpfs。返回值仅用于日志。
     *
     * @return 结果描述，失败时含原因
     */
    static String reapplyPathSu() {
        String has = AdbShell.run("[ -f " + PATH_MARK + " ] && echo yes || echo no", 15000)
                .text().trim();
        if (!has.contains("yes")) {
            return "skip: 用户未安装过 su";
        }
        AdbShell.Result r = AdbShell.run(REAPPLY_PATH, 40000);
        return "rc=" + r.code + " " + r.text().trim();
    }

    /** Minimal ksud shim so scripts that probe for KernelSU find something real. */
    private static final String KSUD_SHIM =
            "#!/system/bin/sh\n"
          + "# NEXUS ksud - KernelSU-compatible shim.\n"
          + "case \"$1\" in\n"
          + "  --version|-V) echo \"NEXUS ksud 1.0 (bridge shim)\" ;;\n"
          + "  su) shift; exec /data/adb/ksu/bin/su \"$@\" ;;\n"
          + "  module)\n"
          + "    shift\n"
          + "    case \"$1\" in\n"
          + "      list) ls -1 /data/adb/modules 2>/dev/null ;;\n"
          + "      install) shift; /data/adb/ksu/bin/nexus-module-install \"$@\" ;;\n"
          + "      *) echo \"usage: ksud module {list|install <zip>}\" ;;\n"
          + "    esac ;;\n"
          + "  *) echo \"usage: ksud {--version|su <cmd>|module <list|install>}\" ;;\n"
          + "esac\n";

    private LinearLayout body;
    private TextView log;
    private TextView tempState;
    private final StringBuilder sb = new StringBuilder();
    private boolean adbdHard = false;
    private String adbdNote = "";
    private ScrollView sv;
    private LinearLayout tabHolder;
    private View secHome, secSu, secSuPath, secMod, secSet;
    private int tab = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        detect();
        refreshTempState();

        String auto = getIntent() == null ? null : getIntent().getStringExtra("auto");
        if ("deploy".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { deploy(); }
            }, 2500L);
        } else if ("grant".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { selfGrant(); }
            }, 1500L);
        } else if ("patch".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { patchKernel(); }
            }, 2500L);
        } else if ("temproot".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { enableTempRoot(); }
            }, 1500L);
        } else if ("untemproot".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { disableTempRoot(); }
            }, 1500L);
        } else if ("adbd".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { toggleAdbd(); }
            }, 1500L);
        } else if ("suv".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { testSu(); }
            }, 1500L);
        } else if ("supath".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { installPathSu(); }
            }, 1500L);
        } else if ("unsupath".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { uninstallPathSu(); }
            }, 1500L);
        } else if ("reapply".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { reapplyPathSuAuto(); }
            }, 1500L);
        } else if (auto != null && auto.startsWith("tab")) {
            // 无头钩子：切到第 N 个标签页（配合截图核验）。render() 会重建视图树，
            // 所以晚一点再切一次，避免被覆盖。支持 "tab1+300" 追加滚动量。
            String rest = auto.substring(3).trim();
            int plus = rest.indexOf('+');
            final int extra = plus < 0 ? 0 : Integer.parseInt(rest.substring(plus + 1).trim());
            final int which;
            try { which = Integer.parseInt(plus < 0 ? rest : rest.substring(0, plus).trim()); }
            catch (Throwable t) { return; }
            for (int i = 0; i < 2; i++) {
                body.postDelayed(new Runnable() {
                    public void run() {
                        View t = which == 0 ? secHome : which == 1 ? secSu
                               : which == 2 ? secMod : secSet;
                        goTab(which, t);
                        if (extra > 0) {
                            // goTab 里的 smoothScrollTo 是动画，晚一点再硬滚，
                            // 否则会被动画拉回去。
                            sv.postDelayed(new Runnable() {
                                public void run() { sv.scrollTo(0, t.getTop() + extra); }
                            }, 400L);
                        }
                    }
                }, i == 0 ? 1500L : 8000L);
            }
        } else if ("templog".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { dumpTempLog(); }
            }, 1500L);
        } else if ("props".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() { sysPropDump(); }
            }, 1500L);
        } else if ("bottom".equals(auto)) {
            // 无头钩子：滚到最底，方便截图核验回显区。
            body.postDelayed(new Runnable() {
                public void run() {
                    if (sv != null) { sv.post(new Runnable() {
                        public void run() { sv.fullScroll(View.FOCUS_DOWN); }
                    }); }
                }
            }, 1500L);
        } else if (auto != null && auto.startsWith("scroll")) {
            final int y;
            try { y = Integer.parseInt(auto.substring(6).trim()); }
            catch (Throwable t) { return; }
            // detect() 的网络探测回来后 render() 会重建整棵视图树、把滚动位置清零，
            // 所以只滚一次是不稳的：在探测前后各滚一次。
            for (int i = 0; i < 2; i++) {
                body.postDelayed(new Runnable() {
                    public void run() { if (sv != null) { sv.scrollTo(0, y); } }
                }, i == 0 ? 1500L : 7000L);
            }
        } else if ("softreboot".equals(auto)) {
            body.postDelayed(new Runnable() {
                public void run() {
                    // 网络操作必须离开 UI 线程，否则只会拿到 NetworkOnMainThreadException。
                    new Thread(new Runnable() {
                        public void run() { AdbShell.run(SOFT_REBOOT, 10000); }
                    }).start();
                }
            }, 2500L);
        } else if (auto != null && auto.startsWith("tab")) {
            final int n;
            try { n = Integer.parseInt(auto.substring(3).trim()); }
            catch (Throwable t) { return; }
            body.postDelayed(new Runnable() {
                public void run() {
                    goTab(n, n == 0 ? secHome : n == 1 ? secSu : n == 2 ? secMod : secSet);
                }
            }, 2500L);
        }
    }

    private View build() {
        LinearLayout outer = Ui.column(this);
        outer.setBackgroundColor(Ui.col(this, R.color.bg));
        outer.addView(Ui.ksuBar(this, "NEXUS"));

        LinearLayout root = Ui.column(this);
        body = Ui.column(this);
        root.addView(body);
        root.addView(Ui.footer(this));

        sv = Ui.scroller(this, root);
        outer.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabHolder = Ui.column(this);
        outer.addView(tabHolder);
        showTabs();
        return outer;
    }

    /** KernelSU-style bottom navigation: 首页 / 超级用户 / 模块 / 设置. */
    private void showTabs() {
        tabHolder.removeAllViews();
        Runnable[] actions = new Runnable[] {
            new Runnable() { public void run() { goTab(0, secHome); } },
            new Runnable() { public void run() { goTab(1, secSu); } },
            new Runnable() { public void run() { goTab(2, secMod); } },
            new Runnable() { public void run() { goTab(3, secSet); } },
        };
        tabHolder.addView(Ui.tabBar(this,
                new String[] {"首页", "超级用户", "模块", "设置"}, tab, actions));
    }

    private void goTab(int which, View target) {
        tab = which;
        showTabs();
        if (target != null && sv != null) {
            final int y = target.getTop();
            sv.post(new Runnable() {
                public void run() { sv.smoothScrollTo(0, y); }
            });
        }
    }

    // =============================================================== status

    private void detect() {
        body.removeAllViews();
        body.addView(Ui.hint(this, "正在读取设备状态…"));
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result probe = AdbShell.run(
                        "cat /proc/version; echo @@; "
                      + "ls -d /data/adb/ksu /data/adb/modules 2>&1; echo @@; "
                      + "ls /data/adb/ksu/bin 2>&1; echo @@; "
                      + "pm list packages | grep -i -e kernelsu -e weishu; echo @@; "
                      + "cat " + ALLOW + " 2>/dev/null; echo @@; "
                      + "ls -1 " + MODULE_DIR + " 2>/dev/null; echo @@; "
                      + KSU_BIN + "/su -c 'echo SHIM_ALIVE' 2>&1 | tail -1; echo @@;"
                      + "dumpsys package me.weishu.kernelsu 2>/dev/null"
                      + " | grep -m1 -e versionName; echo @@;"
                      // PATH 上的 su 才是"能不能直接写 su"的唯一判据。
                      + "ls -l " + PATH_DIR + "/su 2>&1; echo @@; "
                      + "busybox setuidgid " + getApplicationInfo().uid
                      + " env PATH=" + APP_PATH + " sh -c 'command -v su' 2>&1", 40000);
                final int port = AdbShell.findPort();
                final String who = port > 0 ? AdbShell.identity() : AdbShell.lastError;
                adbdHard = AdbShell.run("iptables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP"
                        + " >/dev/null 2>&1 && echo HARD || echo SOFT").text().contains("HARD");
                final String raw = probe.text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        render(port, who, raw);
                        showTabs();
                    }
                });
            }
        }).start();
    }

    private void render(int port, String who, String raw) {
        body.removeAllViews();
        String[] p = raw.split("@@");
        String version = part(p, 0).trim();
        String dirs = part(p, 1).trim();
        String bin = part(p, 2).trim();
        String mgr = part(p, 3).trim();
        String allow = part(p, 4).trim();
        allowText = allow;
        String mods = part(p, 5).trim();
        String shim = part(p, 6).trim();
        String mgrVer = part(p, 7).trim().replace("versionName=", "").trim();
        String pathSu = part(p, 8).trim();
        String pathResolve = part(p, 9).trim();

        boolean root = port > 0;
        boolean ksuDir = dirs.indexOf("/data/adb/ksu") >= 0;
        boolean modDir = dirs.indexOf("/data/adb/modules") >= 0;
        boolean suShim = bin.indexOf("su") >= 0;
        boolean manager = mgr.length() > 0;
        // su 真正"能用"的判据不是某条路径上有文件，而是裸写 su 能解析到它。
        boolean suOnPath = pathResolve.indexOf("su") >= 0
                && pathResolve.indexOf("No such") < 0
                && pathResolve.indexOf("not found") < 0;
        boolean kernelKsu = version.toLowerCase().indexOf("ksu") >= 0
                || version.toLowerCase().indexOf("kernelsu") >= 0;

        String heroVer = mgrVer.length() == 0 ? "NEXUS"
                : (mgrVer.startsWith("v") ? mgrVer : "v" + mgrVer);
        String heroStatus = root ? "运行中 · 越狱模式" : "未运行 · 点下面找回";
        body.addView(Ui.ksuHero(this, heroVer, heroStatus, root));

        secHome = Ui.header(this, "状态");
        body.addView(secHome);

        LinearLayout tiles = Ui.row(this);
        tiles.addView(Ui.tile(this, root ? "ONLINE" : "OFFLINE", "root 通道",
                root ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.bad)));
        tiles.addView(Ui.tile(this, suOnPath ? "PATH" : (suShim ? "FILE" : "—"), "su 入口",
                suOnPath ? Ui.col(this, R.color.ok)
                         : (suShim ? Ui.col(this, R.color.warn) : Ui.col(this, R.color.bad))));
        tiles.addView(Ui.tile(this, heroVer, "管理器",
                manager ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.warn)));
        tiles.addView(Ui.tile(this, kernelKsu ? "YES" : "NO", "内核补丁",
                kernelKsu ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.warn)));
        body.addView(tiles);

        body.addView(Ui.card(this, root ? "root：uid=0 已就绪" : "root：不可用",
                root ? "127.0.0.1:" + port + " · " + who : who,
                root ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.bad), null));

        body.addView(Ui.card(this, kernelKsu ? "内核：已带 KernelSU" : "内核：未打 KernelSU 补丁",
                oneline(version),
                kernelKsu ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.warn), null));

        body.addView(Ui.card(this, suOnPath ? "su：已装进 PATH，任何 App 都能直接用"
                                            : (suShim ? "su：只在 ksu/bin，PATH 里还没有"
                                                      : "su：未安装"),
                "ksu 目录 " + mark(ksuDir) + "　模块目录 " + mark(modDir)
              + "　" + PATH_DIR + "/su " + mark(pathSu.indexOf("su") >= 0) + "\n"
              + "解析结果：" + oneline(pathResolve),
                suOnPath ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.accent), null));

        body.addView(Ui.divider(this));

        // ----------------------------------------------------- kernel patch
        body.addView(Ui.header(this, "内核补丁（自动）"));
        body.addView(Ui.hint(this,
                "自动跑完能做的那几步：从内置管理器里取出官方 ksud 与 magiskboot → "
              + "探测内核 / boot 分区 / LKM 支持 → 导出 boot.img → 尝试加载 kernelsu.ko → "
              + "调 ksud 生成打好补丁的镜像。全程只读原分区，不写块设备。"));
        body.addView(Ui.card(this, "自动打内核补丁",
                kernelKsu ? "内核已带 KernelSU，无需再打"
                          : "提取 ksud → 探测 → 导出 boot.img → LKM / boot 补丁 → 出镜像",
                Ui.col(this, R.color.accent2), new View.OnClickListener() {
                    public void onClick(View v) { patchKernel(); }
                }));
        body.addView(Ui.card(this, "只探测，不改动",
                "打印内核版本、boot 分区、kallsyms、可用 LKM，供排错",
                Ui.col(this, R.color.text_dim), new View.OnClickListener() {
                    public void onClick(View v) { probeKernel(); }
                }));

        body.addView(Ui.divider(this));

        // ------------------------------------------------ one-click deploy
        body.addView(Ui.header(this, "一键部署"));
        body.addView(Ui.hint(this,
                "把该建的都建好：/data/adb/ksu 与 bin、模块目录、su 与 ksud 入口、"
              + "授权文件、内置管理器。每一步的真实回显打在最下面。"));
        body.addView(Ui.card(this, "一键部署 KernelSU",
                "建目录 + 写 su/ksud 入口 + 装管理器 + 白名单 + 复核",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { deploy(); }
                }));
        body.addView(Ui.card(this, "一键给本 App 提权",
                "WRITE_SECURE_SETTINGS、悬浮窗、用量访问、读日志、电池白名单…",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { selfGrant(); }
                }));

        body.addView(Ui.divider(this));

        // ------------------------------------------------------ superuser
        secSu = Ui.header(this, "超级用户（su 白名单）");
        body.addView(secSu);
        body.addView(Ui.card(this, "当前策略",
                allow.length() == 0 ? "未设白名单：默认只放行 root / system / shell"
                                    : allow.replace('\n', '　'),
                Ui.col(this, R.color.accent), null));
        body.addView(Ui.hint(this, ALLOW
                + "\n点「挑应用…」直接从已装应用里勾，不用抄包名。"
                + "root(0) / system(1000) / shell(2000) 永远放行；"
                + "写 * 表示全部放行。"));
        LinearLayout g1 = Ui.row(this);
        g1.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        g1.addView(Ui.button(this, "只放行系统", new View.OnClickListener() {
            public void onClick(View v) { setAllow(""); }
        }));
        g1.addView(Ui.button(this, "放行本 App", new View.OnClickListener() {
            public void onClick(View v) { setAllow(getPackageName()); }
        }));
        g1.addView(Ui.button(this, "放行大疆系", new View.OnClickListener() {
            public void onClick(View v) { setAllow("dji.go.v5\ndji.go.v6"); }
        }));
        g1.addView(Ui.button(this, "全部放行 *", new View.OnClickListener() {
            public void onClick(View v) { setAllow("*"); }
        }));
        g1.addView(Ui.button(this, "挑应用…", new View.OnClickListener() {
            public void onClick(View v) { pickApps(); }
        }));
        g1.addView(Ui.button(this, "手动输入…", new View.OnClickListener() {
            public void onClick(View v) { askAllow(); }
        }));
        body.addView(scrollRow(g1));

        body.addView(Ui.divider(this));

        // ------------------------------------------------------------ su PATH
        secSuPath = Ui.header(this, "su 命令（PATH 可见）");
        body.addView(secSuPath);
        body.addView(Ui.hint(this, "把 su 装到 " + PATH_DIR + "，任何 App、任何脚本"
                + "裸写 su 都能直接调用，不用记路径。\n"
                + "原理：只读分区没法写文件，所以用 tmpfs 临时盖住 " + PATH_DIR
                + "（它原本只有一个 1.4KB 的脚本，已复制回去，原件照常可用），"
                + "再把 su 放进去。挂载对全机进程立即可见。\n"
                + "tmpfs 是内存态、重启即失，但 App 会在重启后自动补挂，"
                + "所以装一次就一直有效；卸载会同时删掉这个自愈标记。\n"
                + "全程没有改写任何系统分区。"));
        LinearLayout g2 = Ui.row(this);
        g2.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        g2.addView(Ui.button(this, "装到 PATH", new View.OnClickListener() {
            public void onClick(View v) { installPathSu(); }
        }));
        g2.addView(Ui.button(this, "从 PATH 卸载", new View.OnClickListener() {
            public void onClick(View v) { uninstallPathSu(); }
        }));
        g2.addView(Ui.button(this, "测试裸 su", new View.OnClickListener() {
            public void onClick(View v) { testSu(); }
        }));
        g2.addView(Ui.button(this, "重跑开机自愈", new View.OnClickListener() {
            public void onClick(View v) { reapplyPathSuAuto(); }
        }));
        body.addView(scrollRow(g2));

        body.addView(Ui.divider(this));

        // ---------------------------------------------------------- modules
        secMod = Ui.header(this, "模块（" + MODULE_DIR + "）");
        body.addView(secMod);
        List<String> list = lines(mods);
        if (list.isEmpty()) {
            body.addView(Ui.hint(this, "还没有模块。点「安装模块」选一个 zip，"
                    + "或把目录塞进 " + MODULE_DIR + "。"));
        } else {
            body.addView(Ui.hint(this, "共 " + list.size()
                    + " 个。点一下看详情 / 启用 / 停用 / 删除。"));
            for (int i = 0; i < list.size(); i++) {
                final String name = list.get(i);
                body.addView(Ui.card(this, name, MODULE_DIR + "/" + name,
                        Ui.col(this, R.color.accent), new View.OnClickListener() {
                            public void onClick(View v) { moduleMenu(name); }
                        }));
            }
        }
        body.addView(Ui.card(this, "安装模块（从 zip）",
                "弹系统文件选择器挑一个 zip，解到 " + MODULE_DIR + " 并校验 module.prop",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { pickModuleZip(); }
                }));
        body.addView(Ui.card(this, "安全模式：停用全部模块",
                "给每个模块放一个 disable 文件，重启后全部跳过",
                Ui.col(this, R.color.warn), new View.OnClickListener() {
                    public void onClick(View v) { safeMode(); }
                }));

        body.addView(Ui.divider(this));

        // -------------------------------------------------- temporary root
        body.addView(Ui.header(this, "临时 root（免解锁 / 可随时关闭）"));
        tempState = Ui.mono(this, "正在检查 su 入口…", 12f, Ui.col(this, R.color.text_dim));
        tempState.setLineSpacing(0f, 1.35f);
        tempState.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        body.addView(tempState);
        body.addView(Ui.hint(this,
                "这台机器的 root 口子在出厂的 root adbd 上（uid=0，监听 127.0.0.1:5555），"
              + "所以不需要刷机也不需要解 BL。su 入口只写在 " + KSU_BIN + " 和 /data/local/tmp，"
              + "系统分区一个字节都不动 —— 关掉开关或恢复出厂就干净了。"));
        body.addView(Ui.hint(this,
                "警告：不要解 BL 锁。这台 SoC 一旦 fastboot flashing unlock 就会永久烧掉一个"
              + "熔丝位，DJI 的校验会失败、4G 模块和大疆 App 一起废掉，刷回原厂也救不回来。"
              + "下面这条路完全不用它。"));
        body.addView(Ui.card(this, "开启临时 root",
                "生成 " + KSU_BIN + "/su 与 /data/local/tmp/su，并放开白名单",
                Ui.col(this, R.color.ok), new View.OnClickListener() {
                    public void onClick(View v) { enableTempRoot(); }
                }));
        body.addView(Ui.card(this, "关闭临时 root",
                "删掉 su 入口与 spool，白名单清空；系统分区不受影响",
                Ui.col(this, R.color.bad), new View.OnClickListener() {
                    public void onClick(View v) { disableTempRoot(); }
                }));
        body.addView(Ui.card(this, "重新写入 / 更新 su 与 ksud",
                "入口在的时候用这个修；" + KSU_BIN + "/su、ksud、/data/local/tmp/su",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { installShim(); }
                }));
        body.addView(Ui.card(this, "测试 su", shim.length() == 0
                        ? "跑一次 su -c 'id' 看结果"
                        : "上次自检：" + oneline(shim),
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { testSu(); }
                }));
        body.addView(Ui.card(this, "看临时 root 日志",
                "ready 标记、pid、注入器日志、以及本应用每次开/关的流水",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { showTempLog(); }
                }));
        body.addView(Ui.card(this, "软重启（让改动生效）",
                "只重启系统界面和全部 App，内核与临时 root 不动 —— "
              + "装完模块、换完系统文件后点这里，不用整机重启",
                Ui.col(this, R.color.accent2), new View.OnClickListener() {
                    public void onClick(View v) { softReboot(); }
                }));
        body.addView(Ui.hint(this, "任何 App 只要执行 "
                + KSU_BIN + "/su -c \"命令\" 就能拿到 uid=0 的输出。"
                + "底层是系统自带的 root adbd，它同时开在 0.0.0.0:5555 上 —— "
                + "同一个 WiFi 里任何人都能用，这是这台机器出厂就有的口子，"
                + "关掉临时 root 只会收回我们写的入口，堵不住那个口子。"));
        body.addView(Ui.card(this,
                adbdHard ? "已加固 · adbd 只认本机" : "加固 adbd（挡住同一个 WiFi 的人）",
                adbdNote.length() > 0 ? adbdNote
                        : adbdHard ? "点一下撤掉规则，恢复局域网直连"
                                   : "iptables 丢掉非 lo 进来的 5555；本机 App 完全不受影响",
                Ui.col(this, adbdHard ? R.color.ok : R.color.warn), new View.OnClickListener() {
                    public void onClick(View v) { toggleAdbd(); }
                }));

        body.addView(Ui.divider(this));

        // ------------------------------------------------------ system props
        body.addView(Ui.header(this, "系统属性（真 ksud 能力）"));
        body.addView(Ui.hint(this,
                "走真 ksud 3.3.0 内置的 resetprop：它直接改写属性内存区，"
              + "所以能改 ro.* 这种 setprop 改不动的只读属性，而且**改完立即生效、不用重启**。"
              + "这是本机唯一一个不依赖 KernelSU 内核模块也能用的原生能力"
              + "（本机内核没有 KSU 模块，resetprop 照样工作）。"
              + "注意：改动只在本次开机内有效，除非勾选「写持久区」。"));
        body.addView(Ui.card(this, "改系统属性",
                "ro.debuggable / ro.adb.secure / 机型伪装 / DPI / 时区……"
              + "每个都带真值回读校验",
                Ui.col(this, R.color.accent2), new View.OnClickListener() {
                    public void onClick(View v) { sysPropEditor(); }
                }));
        body.addView(Ui.card(this, "看全部属性（约 1300 条）",
                "把 resetprop 读到的完整属性表打在最下面的回显里",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { sysPropDump(); }
                }));

        body.addView(Ui.divider(this));

        // ---------------------------------------------------------- danger
        secSet = Ui.header(this, "设置与清理");
        body.addView(secSet);
        body.addView(Ui.card(this, "清理临时文件", "删掉 /data/local/tmp 下的暂存与 spool",
                0, new View.OnClickListener() {
                    public void onClick(View v) { cleanup(); }
                }));
        body.addView(Ui.card(this, "卸载 KernelSU 管理器", "只卸载管理器，不动模块目录",
                Ui.col(this, R.color.bad), new View.OnClickListener() {
                    public void onClick(View v) { uninstallManager(); }
                }));

        log = Ui.mono(this, sb.length() > 0 ? sb.toString() : "", 10.5f,
                Ui.col(this, R.color.text_dim));
        log.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        body.addView(log);
    }

    private static String mark(boolean b) {
        return b ? "有" : "无";
    }

    private static String part(String[] a, int i) {
        return i < a.length ? a[i] : "";
    }

    // ========================================================= kernel patch

    private static final String BOOT_DIR = "/sdcard/RC2NEXUS";

    /** Read-only kernel/boot/LKM inventory. Never writes a block device. */
    private static final String KPROBE =
            "echo '== 内核 =='; uname -r; head -1 /proc/version; echo;"
          + "echo '== 内核命令行 =='; cat /proc/cmdline 2>/dev/null; echo;"
          + "echo '== KernelSU 迹象 ==';"
          + "ls /sys/module 2>/dev/null | grep -i -e ksu -e kernelsu || echo 'sys/module: 无';"
          + "grep -i -e kernelsu -e ksu /proc/kallsyms 2>/dev/null | head -5 || echo 'kallsyms: 无';"
          + "echo;"
          + "echo '== LKM 支持 ==';"
          + "grep -w module /proc/filesystems 2>/dev/null || echo 'filesystems: 无 module 支持';"
          + "echo -n 'modules_disabled='; cat /proc/sys/kernel/modules_disabled 2>/dev/null;"
          + "echo;"
          + "echo '== 已有 .ko ==';"
          + "find /data/adb /data/local/tmp /sdcard -maxdepth 4 -name '*.ko' 2>/dev/null | head -20 || true;"
          + "echo;"
          + "echo '== boot 分区 ==';"
          + "echo -n 'slot='; getprop ro.boot.slot_suffix;"
          + "ls -l /dev/block/by-name/ 2>/dev/null | grep -i -e boot -e init_boot -e recovery || echo 'by-name: 无';"
          + "ls -l /dev/block/bootdevice/by-name/ 2>/dev/null | grep -i boot || true;"
          + "echo;"
          + "echo '== 分区表 =='; cat /proc/partitions 2>/dev/null | head -24";

    /** Pull one entry out of the bundled manager APK. */
    private String extractAsset(String entry, String dest) {
        java.util.zip.ZipInputStream zin = null;
        OutputStream out = null;
        try {
            InputStream raw = getAssets().open(MANAGER);
            zin = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(raw, 1 << 16));
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!entry.equals(e.getName())) { continue; }
                out = new FileOutputStream(dest);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = zin.read(buf)) > 0) { out.write(buf, 0, n); }
                out.flush();
                out.close();
                out = null;
                return dest;
            }
        } catch (Throwable t) {
            step("      提取失败 " + entry + " : " + t);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (zin != null) zin.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Mirror the on-screen log to a file so it survives leaving the screen. */
    private void dumpLog() {
        dumpLog("patch.log");
    }

    private void dumpLog(String name) {
        try {
            FileOutputStream fo = new FileOutputStream(new File(getCacheDir(), name));
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.flush();
            fo.close();
        } catch (Throwable ignored) { }
    }

    private void finishPatch() {
        dumpLog();
        final String cache = getCacheDir().getAbsolutePath() + "/patch.log";
        new Thread(new Runnable() {
            public void run() {
                AdbShell.run("mkdir -p " + BOOT_DIR + " && cp '" + cache + "' "
                        + BOOT_DIR + "/patch.log && chmod 0666 " + BOOT_DIR + "/patch.log",
                        20000);
            }
        }).start();
        runOnUiThread(new Runnable() {
            public void run() {
                toast("结束，日志在 " + BOOT_DIR + "/patch.log");
                if (log != null) { log.setText(sb.toString()); }
            }
        });
        step("=== 结束 ===");
    }

    private void probeKernel() {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("正在探测…");
        new Thread(new Runnable() {
            public void run() {
                step("=== 内核探测（只读）===");
                AdbShell.Result r = AdbShell.run(KPROBE, 60000);
                for (String l : lines(r.text())) { step("  " + l); }
                int port = AdbShell.findPort();
                step("  通道 " + (port > 0 ? "127.0.0.1:" + port : AdbShell.lastError));
                finishPatch();
            }
        }).start();
    }

    /**
     * The honest version of "自动打内核补丁".
     *
     * <p>Runs every step that really can run on this device — pull the official
     * {@code ksud}/{@code magiskboot} out of the bundled manager, inventory the
     * kernel and boot partition, try an LKM if one exists, dump the real boot
     * image and hand it to {@code ksud boot-patch} — and reports the genuine
     * output of each. It never writes a block device: flashing a wrong boot image
     * bricks an RC 2 with no fastboot to fall back on.
     */
    private void patchKernel() {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("开始打内核补丁…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    step("=== 自动打内核补丁 ===");

                    // ---------------------------------------------------- 1/6
                    step("[1/6] 从内置管理器提取官方工具");
                    String cache = getCacheDir().getAbsolutePath();
                    String ksud = extractAsset("lib/arm64-v8a/libksud.so", cache + "/ksud");
                    String mb = extractAsset("lib/arm64-v8a/libmagiskboot.so",
                            cache + "/magiskboot");
                    step("      ksud       "
                            + (ksud == null ? "缺失" : new File(ksud).length() + " B"));
                    step("      magiskboot "
                            + (mb == null ? "缺失" : new File(mb).length() + " B"));
                    if (ksud == null) {
                        step("      内置管理器里没有 ksud，后面几步无法执行。");
                        step("      换一个带 libksud.so 的管理器版本再试。");
                        finishPatch();
                        return;
                    }
                    AdbShell.run("chmod 0755 '" + ksud + "'", 10000);
                    if (mb != null) { AdbShell.run("chmod 0755 '" + mb + "'", 10000); }
                    AdbShell.run("mkdir -p " + KSU_BIN
                            + " && cp '" + ksud + "' " + KSU_BIN + "/ksud"
                            + " && chmod 0755 " + KSU_BIN + "/ksud", 30000);
                    if (mb != null) {
                        AdbShell.run("cp '" + mb + "' " + KSU_BIN + "/magiskboot"
                                + " && chmod 0755 " + KSU_BIN + "/magiskboot", 30000);
                    }
                    AdbShell.Result ver = AdbShell.run(KSU_BIN + "/ksud --version 2>&1;"
                            + " echo '~~'; " + KSU_BIN + "/ksud --help 2>&1 | head -32", 60000);
                    step("      已安装到 " + KSU_BIN + "/ksud");
                    for (String l : lines(ver.text())) { step("      " + l); }

                    // ---------------------------------------------------- 2/6
                    step("[2/6] 探测内核 / 分区 / LKM");
                    AdbShell.Result k = AdbShell.run(KPROBE, 90000);
                    for (String l : lines(k.text())) { step("      " + l); }

                    // ---------------------------------------------------- 3/6
                    step("[3/6] LKM 路线（kernelsu.ko）");
                    boolean triedLkm = false;
                    List<String> ko = lines(AdbShell.run(
                            "find /data/adb /data/local/tmp /sdcard /system/lib/modules"
                          + " -maxdepth 4 -name '*.ko' 2>/dev/null | head -40", 90000).text());
                    for (int i = 0; i < ko.size(); i++) {
                        String path = ko.get(i).trim();
                        if (path.length() == 0) { continue; }
                        String low = path.toLowerCase();
                        if (low.indexOf("ksu") < 0 && low.indexOf("kernelsu") < 0) { continue; }
                        triedLkm = true;
                        step("      insmod " + path);
                        AdbShell.Result r = AdbShell.run(
                                "insmod '" + path + "' 2>&1; echo '~~';"
                              + " lsmod 2>/dev/null | grep -i -e ksu -e kernelsu || true;"
                              + " ls /sys/module 2>/dev/null | grep -i -e ksu -e kernelsu || true",
                                40000);
                        for (String l : lines(r.text())) { step("        " + l); }
                    }
                    if (!triedLkm) {
                        step("      本机没有任何 kernelsu.ko。LKM 需要按这台机器的内核"
                              + "逐字编译，网上找不到能直接 insmod 的现成模块，所以跳过。");
                    }

                    // ---------------------------------------------------- 4/6
                    step("[4/6] 导出 boot 镜像（只读 dd）");
                    AdbShell.run("mkdir -p " + BOOT_DIR, 15000);
                    String dev = null;
                    AdbShell.Result devs = AdbShell.run(
                            "for p in /dev/block/by-name/boot /dev/block/by-name/init_boot"
                          + " /dev/block/bootdevice/by-name/boot /dev/block/by-name/boot_a"
                          + " /dev/block/by-name/boot_b; do"
                          + " [ -e \"$p\" ] && echo FOUND $p; done", 30000);
                    for (String l : lines(devs.text())) {
                        if (l.startsWith("FOUND ")) { dev = l.substring(6).trim(); break; }
                    }
                    String rawImg = null;
                    if (dev == null) {
                        step("      找不到 boot 分区节点，导不了。");
                    } else {
                        step("      分区 " + dev);
                        AdbShell.Result dd = AdbShell.run(
                                "dd if=" + dev + " of=" + BOOT_DIR + "/boot.img"
                              + " bs=1M 2>&1; echo '~~'; ls -l " + BOOT_DIR + "/boot.img;"
                              + " sha256sum " + BOOT_DIR + "/boot.img 2>/dev/null || true",
                                300000);
                        for (String l : lines(dd.text())) { step("      " + l); }
                        rawImg = BOOT_DIR + "/boot.img";
                    }

                    // ---------------------------------------------------- 5/6
                    step("[5/6] 用 ksud 生成打好补丁的镜像");
                    String out = BOOT_DIR + "/boot-kernelsu.img";
                    if (rawImg == null) {
                        step("      没有 boot.img，跳过。");
                    } else {
                        AdbShell.run("rm -f " + out, 10000);
                        AdbShell.Result kp = AdbShell.run(
                                KSU_BIN + "/ksud boot-patch -b " + rawImg + " -o " + out
                              + " 2>&1; echo '~~rc='$?; ls -l " + out + " 2>&1;"
                              + " sha256sum " + out + " 2>/dev/null || true", 600000);
                        for (String l : lines(kp.text())) { step("      " + l); }
                    }

                    // ---------------------------------------------------- 6/6
                    step("[6/6] 结论");
                    boolean made = lines(AdbShell.run(
                            "ls -l " + out + " 2>/dev/null", 20000).text()).size() > 0;
                    if (made) {
                        step("      补丁镜像已生成：" + out);
                        step("      刷写要用 PC 上的 fastboot：fastboot flash boot <img>");
                        step("      本 App 不会自动刷写 —— boot 写错，这台机器没有可用的"
                              + " fastboot 入口可以救回来。");
                    } else {
                        step("      没生成镜像，上面就是这台机器的真实结论。");
                        step("      5.4.210-qgki 是非 GKI 内核：官方 KernelSU 的 boot-patch "
                              + "只覆盖 GKI，非 GKI 要拿内核源码重新编译内核，"
                              + "设备上没有编译条件。");
                        step("      这台机器的 root 走的是系统自带、免认证的 root adbd，"
                              + "能力等同 uid=0，已经由本页的 su 入口提供。");
                    }
                    finishPatch();
                } catch (Throwable t) {
                    step("异常：" + t);
                    finishPatch();
                }
            }
        }).start();
    }

    // =============================================================== deploy

    private void step(String line) {
        sb.append(line).append('\n');
        dumpLog();
        runOnUiThread(new Runnable() {
            public void run() {
                if (log != null) {
                    log.setText(sb.toString());
                }
            }
        });
    }

    private void deploy() {
        sb.setLength(0);
        toast("开始部署…");
        new Thread(new Runnable() {
            public void run() {
                boolean ok = true;
                int port = AdbShell.findPort();
                step(port > 0 ? "[1/7] root 通道 OK · 127.0.0.1:" + port
                              : "[1/7] root 通道不可用 · " + AdbShell.lastError);
                if (port <= 0) {
                    done(false);
                    return;
                }

                AdbShell.Result mk = AdbShell.run(
                        "mkdir -p " + KSU_DIR + " " + KSU_BIN + " " + MODULE_DIR
                      + " /data/local/tmp/nexus-su 2>&1; "
                      + "chmod 700 " + KSU_DIR + "; chmod 755 " + KSU_BIN
                      + " " + MODULE_DIR + "; chmod 0777 /data/local/tmp/nexus-su; "
                      + "ls -ld " + KSU_DIR + " " + KSU_BIN + " " + MODULE_DIR, 25000);
                step("[2/7] 目录：" + oneline(mk.text()));

                // su / ksud entry points
                // ksud 走 ensureKsud()：真二进制（来自内置管理器的
                // libksud.so，4.9MB）优先，只有它不在时才退回 shim。
                // 早先这里无条件写 shim，会把真 ksud 覆盖掉。
                // su 要写三处：KSU_BIN（给已知道路径的脚本）、
                // /data/local/tmp/su（调试用），以及下面 INSTALL_PATH 放进 PATH。
                String w = writeScript(KSU_BIN + "/su", SU_SHIM)
                         + writeScript("/data/local/tmp/nexus-su.sh", SU_SHIM)
                         + writeScript("/data/local/tmp/su", SU_SHIM)
                         + ensureKsud();
                // 让 su 出现在 PATH 上——脚本里裸写 su 才找得到。
                AdbShell.Result ipr = AdbShell.run(INSTALL_PATH, 40000);
                boolean pathOk = ipr.code == 0 && ipr.text().indexOf(" su") > 0;
                if (!pathOk) {
                    w = w + "PATH 安装：" + oneline(ipr.text());
                }
                boolean shimOk = w.length() == 0;
                step("[3/7] su / ksud 入口：" + (shimOk ? "已写入并 chmod 755" : w)
                        + (pathOk ? "\n      PATH 遮蔽：" + PATH_DIR + "/su 就位" : ""));
                if (!shimOk || !pathOk) {
                    ok = false;
                }

                // allowlist scaffold
                AdbShell.run("touch " + ALLOW + " && chmod 600 " + ALLOW, 15000);
                step("[4/7] 授权文件：" + ALLOW);

                // bundled manager
                String staged = null;
                try {
                    InputStream in = getAssets().open(MANAGER);
                    File out = new File(getCacheDir(), MANAGER);
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    os.close();
                    in.close();
                    AdbShell.Result c = AdbShell.run(
                            "cp " + AdbShell.quote(out.getAbsolutePath()) + " " + STAGE
                          + " && chmod 644 " + STAGE + " && ls -l " + STAGE, 120000);
                    if (c.code != 0) {
                        step("[5/7] 暂存失败：" + oneline(c.text()));
                        ok = false;
                    } else {
                        staged = STAGE;
                        step("[5/7] 已暂存 " + oneline(c.text()));
                    }
                } catch (Throwable t) {
                    step("[5/7] assets 里没有 " + MANAGER + "：" + t.getClass().getSimpleName());
                    ok = false;
                }

                if (staged != null) {
                    // --abi is required: this vendor PackageManager cannot derive
                    // an ABI for an APK shipping riscv64 + x86_64 + arm64-v8a and
                    // fails with INSTALL_FAILED_INTERNAL_ERROR.
                    // 内置的是 v3.0.0，机器上很可能已经有更新的一版；那种情况下
                    // pm 会回 INSTALL_FAILED_VERSION_DOWNGRADE，这不是错误。
                    boolean alreadyNewer = false;
                    AdbShell.Result q = AdbShell.run(
                            "dumpsys package me.weishu.kernelsu 2>/dev/null "
                          + "| grep -m1 versionName", 20000);
                    String have = oneline(q.text());
                    if (have.length() > 0) {
                        step("      （设备上已有 " + have + "，内置为 " + MANAGER + "）");
                    }
                    AdbShell.Result r = AdbShell.run(
                            "pm install -r --abi arm64-v8a " + staged, 300000);
                    String out = r.text().toLowerCase();
                    boolean good = out.indexOf("success") >= 0;
                    alreadyNewer = out.indexOf("version_downgrade") >= 0
                                || out.indexOf("already exists") >= 0;
                    if (!good && alreadyNewer) {
                        step("[6/7] 管理器：设备上已有的版本更新，保留不降级");
                    } else {
                        step("[6/7] 管理器：" + (good ? "已安装" : oneline(r.text())));
                    }
                    if (!good && !alreadyNewer) {
                        ok = false;
                    }
                    AdbShell.run("dumpsys deviceidle whitelist +me.weishu.kernelsu "
                            + "2>&1 | tail -1", 20000);
                }

                // 自检用真实 app uid + 真实 PATH 走一遍，这才是用户脚本的处境。
                String appPath = "/product/bin:/apex/com.android.runtime/bin:"
                        + "/apex/com.android.art/bin:/system_ext/bin:/system/bin";
                AdbShell.Result v = AdbShell.run(
                        "echo '--- su 自检（真实 app uid " + getApplicationInfo().uid
                      + "）---'; "
                      + "busybox setuidgid " + getApplicationInfo().uid
                      + " env PATH=" + appPath + " sh -c "
                      + "'command -v su; su -c id; echo PIPELINE:; su -c \"ls /data/adb | head -2\"' "
                      + "2>&1; "
                      + "echo '--- 复核 ---'; ls -l " + PATH_DIR + "; "
                      + "cat " + ALLOW + " 2>/dev/null | head -5; "
                      + "ls -1 " + MODULE_DIR + " 2>/dev/null | head -10", 90000);
                step("[7/7] " + v.text().trim());
                dumpSu("rc2_deploy.txt");
                done(ok);
            }
        }).start();
    }

    /** Writes a script as root, 0755. Returns "" on success, else the reason. */
    private String writeScript(String path, String content) {
        String b64 = android.util.Base64.encodeToString(
                content.getBytes(), android.util.Base64.NO_WRAP);
        AdbShell.Result r = AdbShell.run(
                "echo " + b64 + " | base64 -d > " + quote(path)
              + " && chmod 755 " + quote(path) + " && ls -l " + quote(path), 30000);
        if (r.code != 0 || r.text().indexOf("No such") >= 0) {
            return oneline(r.text());
        }
        return "";
    }

    /**
     * 确保 {@code /data/adb/ksu/bin/ksud} 存在，且**优先保留真二进制**。
     *
     * <p>官方 ksud 是一个 4.9MB 的原生程序（内置管理器的
     * {@code lib/arm64-v8a/libksud.so}），带 23 个子命令；shim 是几百字节的
     * shell 脚本，只有 3 个子命令。两者都放在同一个路径上，所以写入顺序
     * 决定了用户最终拿到哪一个 —— 早先这里是无条件覆盖 shim，等于把真
     * ksud 擦掉。
     *
     * <p>判定方式用 ksud 自己的输出：shim 打印 {@code NEXUS ksud ...}，
     * 真二进制打印 {@code ksud 3.x.x (uapi: N)}。只有确认它不在或不是真的
     * 才写 shim。
     *
     * @return 空串表示 OK，非空是错误摘要（与 {@link #writeScript} 一致）
     */
    private String ensureKsud() {
        String path = KSU_BIN + "/ksud";
        AdbShell.Result probe = AdbShell.run(path + " -V 2>&1", 15000);
        String out = probe.text().trim();
        // 真 ksud：第一行形如 "ksud 3.3.0 (uapi: 2)"，且不是 shim 的自报名。
        boolean real = probe.code == 0
                && out.length() > 0
                && out.indexOf("ksud ") >= 0
                && out.indexOf("NEXUS") < 0;
        if (real) {
            step("      ksud 已存在且为真二进制：" + oneline(out));
            return "";
        }
        step("      ksud 不是真二进制（" + oneline(out) + "），写入 shim");
        return writeScript(path, KSUD_SHIM);
    }

    private void done(final boolean ok) {
        runOnUiThread(new Runnable() {
            public void run() {
                toast(ok ? "部署完成" : "部署完成（有步骤失败，见下方回显）");
                detect();
            }
        });
    }

    // =========================================================== superuser

    private void setAllow(String value) {
        final String v = value;
        new Thread(new Runnable() {
            public void run() {
                String b64 = android.util.Base64.encodeToString(
                        (v + "\n").getBytes(), android.util.Base64.NO_WRAP);
                AdbShell.run("mkdir -p " + KSU_DIR + " && echo " + b64
                        + " | base64 -d > " + ALLOW + " && chmod 600 " + ALLOW, 20000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("白名单已更新");
                        detect();
                    }
                });
            }
        }).start();
    }

    /**
     * 弹已安装应用列表让用户勾选，不用手抄包名。
     *
     * <p>勾中的包名写进 su 白名单；已放行的会预先勾上。
     */
    private void pickApps() {
        final PackageManager pm = getPackageManager();
        final List<String> rows = new ArrayList<String>();
        final List<String> pkgs = new ArrayList<String>();
        try {
            java.util.TreeMap<String, String> m = new java.util.TreeMap<String, String>();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (int i = 0; i < apps.size(); i++) {
                ApplicationInfo ai = apps.get(i);
                String lb;
                try { lb = String.valueOf(pm.getApplicationLabel(ai)); }
                catch (Throwable t) { lb = ai.packageName; }
                m.put(lb + "　" + ai.packageName, ai.packageName);
            }
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                rows.add(e.getKey());
                pkgs.add(e.getValue());
            }
        } catch (Throwable t) {
            toast("列应用失败：" + t);
            return;
        }
        final String[] items = rows.toArray(new String[0]);
        final boolean[] checked = new boolean[items.length];
        final boolean all = "*".equals(allowText.trim());
        for (int i = 0; i < items.length; i++) {
            checked[i] = all || allowText.contains(pkgs.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("勾选要放行 su 的应用（共 " + items.length + " 个）")
                .setMultiChoiceItems(items, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean on) {
                                checked[which] = on;
                            }
                        })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < items.length; i++) {
                            if (checked[i]) b.append(pkgs.get(i)).append('\n');
                        }
                        setAllow(b.toString().trim());
                    }
                })
                .setNeutralButton("全部放行 *", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { setAllow("*"); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askAllow() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setHint("每行一个 uid 或包名，* 表示全部");
        e.setTextColor(Ui.col(this, R.color.text));
        new AlertDialog.Builder(this)
                .setTitle("su 白名单")
                .setView(e)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        setAllow(e.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ========================================================== system props

    /** 列出预设属性 + 真值，点一条进编辑。 */
    private void sysPropEditor() {
        new Thread(new Runnable() {
            public void run() {
                final String[] names = new String[SysProp.PRESETS.length];
                final String[] titles = new String[SysProp.PRESETS.length];
                final String[] hints = new String[SysProp.PRESETS.length];
                for (int i = 0; i < SysProp.PRESETS.length; i++) {
                    names[i] = SysProp.PRESETS[i][0];
                    titles[i] = SysProp.PRESETS[i][1];
                    hints[i] = SysProp.PRESETS[i][2];
                }
                final String[] vals = SysProp.snapshot(names);
                runOnUiThread(new Runnable() {
                    public void run() {
                        String[] items = new String[names.length];
                        for (int i = 0; i < names.length; i++) {
                            String v = vals[i] == null ? "（读不到）" : oneline(vals[i]);
                            items[i] = titles[i] + "　= " + v;
                        }
                        new AlertDialog.Builder(KsuActivity.this)
                                .setTitle("系统属性（点一条修改）")
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        sysPropEdit(names[which], hints[which], vals[which]);
                                    }
                                })
                                .setNeutralButton("自定义属性…", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        sysPropEdit("", "输入任意属性名，如 ro.product.model", null);
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 单个属性的编辑框：可改名、改值、勾持久化。 */
    private void sysPropEdit(final String preName, String hint, String curVal) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        box.setPadding(pad, pad, pad, pad);

        final EditText eName = new EditText(this);
        eName.setHint("属性名");
        eName.setText(preName);
        box.addView(eName);

        final EditText eVal = new EditText(this);
        eVal.setHint("新值");
        eVal.setText(curVal == null ? "" : curVal);
        box.addView(eVal);

        final CheckBox cP = new CheckBox(this);
        cP.setText("同时写持久区（重启后仍在）");
        cP.setTextColor(Ui.col(this, R.color.text_dim));
        box.addView(cP);

        TextView tHint = Ui.hint(this, hint + "\n当前值：" + (curVal == null ? "（读不到）" : curVal));
        box.addView(tHint);

        new AlertDialog.Builder(this)
                .setTitle("修改系统属性")
                .setView(box)
                .setPositiveButton("写入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String n = eName.getText().toString().trim();
                        final String v = eVal.getText().toString();
                        final boolean p = cP.isChecked();
                        if (n.length() == 0) {
                            toast("属性名不能为空");
                            return;
                        }
                        sb.setLength(0);
                        step("写入 " + n + " = " + v + (p ? "（持久）" : ""));
                        new Thread(new Runnable() {
                            public void run() {
                                String err = SysProp.set(n, v, p);
                                step(err.length() == 0
                                        ? "  OK，回读 " + n + " = " + SysProp.get(n)
                                        : "  失败：" + err);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        log.setText(sb.toString());
                                        toast(err.length() == 0 ? "已写入并回读成功"
                                                                : "写入失败：" + err);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNeutralButton("读取", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String n = eName.getText().toString().trim();
                        if (n.length() == 0) { return; }
                        new Thread(new Runnable() {
                            public void run() {
                                final String got = SysProp.get(n);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        eVal.setText(got == null ? "" : got);
                                        toast(got == null ? n + " 不存在" : n + " = " + got);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把全部属性倒进回显区。 */
    private void sysPropDump() {
        sb.setLength(0);
        step("读取全部系统属性（resetprop）…");
        new Thread(new Runnable() {
            public void run() {
                String all = SysProp.all();
                int n = 0;
                for (int i = 0; i < all.length(); i++) {
                    if (all.charAt(i) == '\n') { n++; }
                }
                final int lines = n;
                sb.setLength(0);
                step("共 " + lines + " 条属性：");
                step(all.length() > 60000 ? all.substring(0, 60000) + "\n…（截断）" : all);
                runOnUiThread(new Runnable() {
                    public void run() {
                        log.setText(sb.toString());
                        toast("读到 " + lines + " 条属性");
                    }
                });
            }
        }).start();
    }

    // ============================================================= modules

    private void moduleMenu(final String name) {
        final String dir = MODULE_DIR + "/" + name;
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run(
                        "cat " + quote(dir + "/module.prop") + " 2>/dev/null; echo '@@'; "
                      + "if [ -f " + quote(dir + "/disable") + " ]; then echo disabled; "
                      + "else echo enabled; fi", 15000);
                String[] parts = r.text().split("@@");
                final String prop = parts.length > 0 ? parts[0].trim() : "";
                final boolean disabled = parts.length > 1
                        && parts[1].indexOf("disabled") >= 0;
                runOnUiThread(new Runnable() {
                    public void run() {
                        String msg = dir + "\n\n"
                                + (prop.length() == 0 ? "（没有 module.prop）" : prop)
                                + "\n状态：" + (disabled ? "已停用" : "已启用");
                        new AlertDialog.Builder(KsuActivity.this)
                                .setTitle(name)
                                .setMessage(msg)
                                .setPositiveButton(disabled ? "启用" : "停用",
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int w) {
                                                toggle(dir, !disabled);
                                            }
                                        })
                                .setNeutralButton("删除", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        confirmDelete(name, dir);
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private void toggle(final String dir, final boolean disable) {
        new Thread(new Runnable() {
            public void run() {
                AdbShell.run(disable
                        ? "touch " + quote(dir + "/disable")
                        : "rm -f " + quote(dir + "/disable"), 15000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(disable ? "已停用，重启后生效" : "已启用，重启后生效");
                        detect();
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final String name, final String dir) {
        new AlertDialog.Builder(this)
                .setTitle("删除模块 " + name + "？")
                .setMessage(dir + "\n\n不可撤销。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                AdbShell.run("rm -rf " + quote(dir), 40000);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast("已删除");
                                        detect();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void safeMode() {
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run(
                        "for d in " + MODULE_DIR + "/*; do [ -d \"$d\" ] && touch \"$d/disable\"; "
                      + "done; ls -1 " + MODULE_DIR, 30000);
                final String t = r.text().trim();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("已停用全部模块，重启后生效");
                        sb.setLength(0);
                        step("安全模式：" + t);
                        detect();
                    }
                });
            }
        }).start();
    }

    /**
     * 弹系统的文件选择器挑模块 zip。
     *
     * <p>以前是扫 /sdcard 列出来，等于要用户自己先把文件放对地方；现在直接
     * 调 DocumentsUI，U 盘、下载、大疆的目录点到哪个都行。
     */
    private void pickModuleZip() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_ZIP);
        } catch (Throwable t) {
            toast("打不开文件选择器：" + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_ZIP) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = queryName(uri);
        new Thread(new Runnable() {
            public void run() {
                final File dst = new File(getCacheDir(), "module.zip");
                String err = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(dst);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final String e = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (e != null) {
                            toast("读文件失败：" + e);
                            return;
                        }
                        step("已选模块：" + name);
                        installModule(dst.getAbsolutePath(), name);
                    }
                });
            }
        }).start();
    }

    /** 从 SAF 的 uri 上读显示名，只用于回显。 */
    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver()
                    .query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int i = c.getColumnIndex(
                                android.provider.OpenableColumns.DISPLAY_NAME);
                        if (i >= 0) return String.valueOf(c.getString(i));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) { }
        return String.valueOf(uri.getLastPathSegment());
    }

    private void installModule(final String zip) {
        installModule(zip, null);
    }

    private void installModule(final String zip, final String label) {
        new Thread(new Runnable() {
            public void run() {
                // Derive the module id from the zip's own module.prop so the
                // installed directory matches what the module expects.
                AdbShell.Result idr = AdbShell.run(
                        "busybox unzip -p " + quote(zip) + " module.prop 2>/dev/null "
                      + "| grep -m1 '^id=' | cut -d= -f2 | tr -d '\\r'", 60000);
                String id = idr.text().trim();
                if (id.length() == 0) {
                    id = new File(zip).getName().replaceAll("\\.zip$", "");
                }
                id = id.replaceAll("[^A-Za-z0-9._-]", "");
                final String mid = id;
                final String dir = MODULE_DIR + "/" + mid;
                AdbShell.Result r = AdbShell.run(
                        "rm -rf " + quote(dir) + " && mkdir -p " + quote(dir)
                      + " && busybox unzip -o " + quote(zip) + " -d " + quote(dir)
                      + " >/dev/null 2>&1; "
                      + "if [ -f " + quote(dir + "/module.prop") + " ]; then "
                      + "echo INSTALLED; cat " + quote(dir + "/module.prop")
                      + "; else echo 'NO_MODULE_PROP'; ls -la " + quote(dir)
                      + "; fi", 180000);
                final String t = r.text().trim();
                runOnUiThread(new Runnable() {
                    public void run() {
                        sb.setLength(0);
                        step("安装模块 " + (label == null ? zip : label)
                                + "\n→ " + dir + "\n\n" + t);
                        toast(t.indexOf("INSTALLED") >= 0
                                ? "模块已装入 " + mid + "，重启后生效"
                                : "这个 zip 里没有 module.prop");
                        detect();
                    }
                });
            }
        }).start();
    }

    // ================================================================== su

    /**
     * "临时 root": hand every app a working su without touching /system.
     *
     * Nothing is flashed and the bootloader is left alone — unlocking it would
     * permanently burn a tamper fuse on this SoC and take the 4G module with
     * it. The shim lives in /data and /data/local/tmp only, so the off switch
     * (or a factory reset) takes it straight back out.
     */
    /**
     * 把「临时 root 现在到底开没开」直接摆到页面上。
     *
     * 之前只有「开启 / 关闭」两个按钮，点完看不出到底成没成；而且 su 写在
     * /data/adb/ksu/bin 下，并不在 PATH 里，别的 App 直接敲 su 是找不到的，
     * 必须用完整路径。这两件事以前都只写在正文里，用户看不到，所以这里
     * 实测一次再显示状态。
     */
    // ============================================================= temp log

    /** 往 /data/adb-persist/nexus-temproot.log 追一行带时间的流水。 */
    private void logTemp(String msg) {
        final String m = msg == null ? "" : msg.replace('\n', ' ');
        final String b64 = android.util.Base64.encodeToString(
                m.getBytes(), android.util.Base64.NO_WRAP);
        new Thread(new Runnable() {
            public void run() {
                AdbShell.run("mkdir -p /data/adb-persist; "
                        + "{ printf '%s ' \"$(date '+%m-%d %H:%M:%S')\"; echo " + b64
                        + " | base64 -d; } >> " + TEMP_LOG, 20000);
            }
        }).start();
    }

    /**
     * 摊开临时 root 的全部证据：ready 标记、pid、进程数、su 自检，以及两份日志尾部。
     *
     * <p>以前只有「开 / 关」两个按钮，成没成只能靠猜；现在这里能直接看到注入器
     * 有没有留 ready、adbd-root 还在不在、以及每一次开关的流水。
     */
    /** 读临时 root 现场的那条 shell：状态 + 两份日志尾部。 */
    private static String tempLogCmd() {
        return "echo '[状态]'; date; "
             + "echo -n 'ready 标记: '; ls -l " + TempRoot.READY_FLAG + " 2>&1; "
             + "echo -n 'pid 文件: '; cat " + TempRoot.PID_FILE + " 2>&1; echo; "
             + "echo -n 'adbd-root 进程数: '; "
             + "busybox ps -A 2>/dev/null | grep -c adbd-root; "
             + "echo -n 'su 自检: '; " + KSU_BIN + "/su -c id 2>&1 | head -1; "
             + "echo; echo '[本应用流水] " + TEMP_LOG + "'; "
             + "tail -30 " + TEMP_LOG + " 2>&1; "
             + "echo; echo '[注入器日志尾部] " + TempRoot.LOG_FILE + "'; "
             + "tail -20 " + TempRoot.LOG_FILE + " 2>&1";
    }

    /** 无头自检用：把同一份日志写进 getFilesDir()/rc2_templog.txt。 */
    private void dumpTempLog() {
        logTemp("自检：读了一次临时 root 日志");
        new Thread(new Runnable() {
            public void run() {
                final String t = AdbShell.run(tempLogCmd(), 40000).text();
                try {
                    FileOutputStream o = new FileOutputStream(
                            new File(getFilesDir(), "rc2_templog.txt"), false);
                    o.write(t.getBytes("UTF-8"));
                    o.close();
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void showTempLog() {
        sb.setLength(0);
        step("读临时 root 日志…");
        new Thread(new Runnable() {
            public void run() {
                final String t = AdbShell.run(tempLogCmd(), 40000).text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        new AlertDialog.Builder(KsuActivity.this)
                                .setTitle("临时 root 日志")
                                .setMessage(t)
                                .setPositiveButton("关闭", null)
                                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        try {
                                            android.content.ClipboardManager cm =
                                                    (android.content.ClipboardManager)
                                                    getSystemService(CLIPBOARD_SERVICE);
                                            cm.setPrimaryClip(android.content.ClipData
                                                    .newPlainText("rc2-temproot", t));
                                            toast("已复制");
                                        } catch (Throwable x) {
                                            toast("复制失败：" + x);
                                        }
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    /**
     * 软重启：只重启 zygote / system_server，所有 App 重开，
     * 内核、已挂载的改动和临时 root 都不受影响。
     */
    private void softReboot() {
        new AlertDialog.Builder(this)
                .setTitle("软重启")
                .setMessage("会重启系统界面和全部 App（本工具也会退出，屏幕黑一下，几秒到几十秒，之后重新打开就行）。\n"
                        + "内核、已挂载的模块改动、临时 root 都不会动（root adbd 不会被杀）。\n\n要继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        toast("正在软重启…");
                        new Thread(new Runnable() {
                            public void run() {
                                AdbShell.run(SOFT_REBOOT, 10000);
                            }
                        }).start();
                    }
                })
                .show();
    }

    private void refreshTempState() {
        if (tempState == null) return;
        new Thread(new Runnable() {
            public void run() {
                final String ls = AdbShell.run(
                        "ls -l " + KSU_BIN + "/su /data/local/tmp/su 2>&1", 20000).text().trim();
                final String probe = AdbShell.run(
                        KSU_BIN + "/su -c 'echo __SU_OK__' 2>&1", 30000).text().trim();
                final boolean on = probe.contains("__SU_OK__");
                final boolean there = ls.contains("/su");
                final String txt;
                if (on) {
                    txt = "状态：已启用 ✓ su 能跑出 root 结果\n"
                            + "入口：" + KSU_BIN + "/su -c \"命令\"\n\n" + ls;
                } else if (there) {
                    txt = "状态：入口在，但跑不通\n" + ls + "\n\n点「测试 su」看具体报错";
                } else {
                    txt = "状态：未启用\n点下面「开启临时 root」写入口\n\n" + ls;
                }
                body.post(new Runnable() {
                    public void run() {
                        if (tempState != null) tempState.setText(txt);
                    }
                });
            }
        }).start();
    }

    private void enableTempRoot() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                step("开启临时 root…");
                AdbShell.run("mkdir -p " + KSU_BIN + " /data/local/tmp/nexus-su; "
                        + "chmod 0755 " + KSU_BIN + " /data/local/tmp; "
                        + "echo '" + Ui.version(KsuActivity.this) + "' > " + KSU_DIR
                        + "/.nexus_ver", 20000);
                String e1 = writeScript(KSU_BIN + "/su", SU_SHIM);
                String e2 = writeScript("/data/local/tmp/nexus-su.sh", SU_SHIM);
                String e2b = writeScript("/data/local/tmp/su", SU_SHIM);
                String e3 = ensureKsud();
                AdbShell.Result ipr = AdbShell.run(INSTALL_PATH, 40000);
                step(KSU_BIN + "/su        → " + ok(e1));
                step("/data/local/tmp/su → " + ok(e2) + ok(e2b));
                step(KSU_BIN + "/ksud      → " + ok(e3));
                step(PATH_DIR + "/su → " + (ipr.code == 0 ? "已就位（PATH 可见）"
                                                          : oneline(ipr.text())));
                setAllow("*");
                step("白名单已放开（*）：任何 App 都能调用");
                logTemp("开启临时 root：写入 " + KSU_BIN + "/su、ksud、"
                        + PATH_DIR + "/su，白名单=*");
                testSuInner();
            }
        }).start();
    }

    private void disableTempRoot() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                step("关闭临时 root…");
                // 先卸 PATH 遮蔽：/product/bin 立刻恢复成只读分区上的原件。
                AdbShell.Result upr = AdbShell.run(UNINSTALL_PATH, 20000);
                step(PATH_DIR + " → " + oneline(upr.text()));
                AdbShell.run("rm -f " + KSU_BIN + "/su " + KSU_BIN + "/ksud "
                        + "/data/local/tmp/su /data/local/tmp/nexus-su.sh", 15000);
                AdbShell.run("rm -rf /data/local/tmp/nexus-su /data/local/tmp/rc2-*", 15000);
                setAllow("");
                step("su / ksud 入口已删除，spool 已清空");
                step("系统分区从未被改写：重启后机器恢复出厂状态");
                logTemp("关闭临时 root：卸载 " + PATH_DIR + " 遮蔽、删除 su/ksud 入口与 spool，白名单清空");
                done(true);
            }
        }).start();
    }

    private static String ok(String err) {
        return err == null || err.length() == 0 ? "OK" : err;
    }

    private static final String RULE =
            "iptables -C INPUT -p tcp --dport 5555 ! -i lo -j DROP";

    /** 只挡非 lo 的 5555：本机 App 走 127.0.0.1 完全不受影响。 */
    private void toggleAdbd() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                boolean on = AdbShell.run(RULE + " >/dev/null 2>&1 && echo HARD || echo SOFT")
                        .text().contains("HARD");
                if (on) {
                    AdbShell.run("iptables -D INPUT -p tcp --dport 5555 ! -i lo -j DROP", 15000);
                    adbdNote = "上一次：已撤掉加固，5555 重新对局域网开放";
                } else {
                    AdbShell.Result r = AdbShell.run("iptables -I INPUT -p tcp --dport 5555"
                            + " ! -i lo -j DROP", 15000);
                    adbdNote = "上一次：" + (r.ok() ? "加固成功" : "加固失败 " + oneline(r.text()));
                }
                step(adbdNote);
                step("当前 INPUT 链：");
                step(AdbShell.run("iptables -S INPUT 2>&1 | head -8", 15000).text());
                dumpLog("adbd.log");
                AdbShell.run("mkdir -p " + BOOT_DIR + " && cp '"
                        + getCacheDir().getAbsolutePath() + "/adbd.log' " + BOOT_DIR
                        + "/adbd.log && chmod 0666 " + BOOT_DIR + "/adbd.log", 20000);
                adbdHard = !on;
                runOnUiThread(new Runnable() {
                    public void run() { detect(); }
                });
            }
        }).start();
    }

    private void installShim() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                AdbShell.run("mkdir -p " + KSU_DIR + " " + KSU_BIN
                        + " /data/local/tmp/nexus-su; chmod 0777 /data/local/tmp/nexus-su; "
                        + "echo '" + Ui.version(KsuActivity.this) + "' > " + KSU_DIR
                        + "/.nexus_ver", 20000);
                String e1 = writeScript(KSU_BIN + "/su", SU_SHIM);
                String e2 = ensureKsud();
                String e3 = writeScript("/data/local/tmp/su", SU_SHIM);
                String e4 = writeScript("/data/local/tmp/nexus-su.sh", SU_SHIM);
                AdbShell.Result ipr = AdbShell.run(INSTALL_PATH, 40000);
                step("su   → " + (e1.length() == 0 ? "OK" : e1));
                step("ksud → " + (e2.length() == 0 ? "OK" : e2));
                step("/data/local/tmp/su → " + (e3.length() == 0 ? "OK" : e3 + e4));
                step(PATH_DIR + "/su → " + (ipr.code == 0 ? "PATH 可见"
                                                          : oneline(ipr.text())));
                testSuInner();
            }
        }).start();
    }

    private void testSu() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                testSuInner();
            }
        }).start();
    }

    /** 把 su 放进 PATH（幂等：重复点会先卸载再重装）。 */
    private void installPathSu() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                step("安装 su 到 PATH（" + PATH_DIR + "）…");
                // 先确保 shim 源文件是最新的，再遮蔽。
                String e = writeScript("/data/local/tmp/nexus-su.sh", SU_SHIM);
                if (e.length() > 0) {
                    step("写 shim 失败：" + e);
                    dumpSu("rc2_supath.txt");
                    done(false);
                    return;
                }
                AdbShell.run("mkdir -p " + KSU_BIN, 15000);
                writeScript(KSU_BIN + "/su", SU_SHIM);
                AdbShell.Result r = AdbShell.run(INSTALL_PATH, 40000);
                step(r.text().trim());
                if (r.code != 0) {
                    step("安装失败，见上方回显");
                    dumpSu("rc2_supath.txt");
                    done(false);
                    return;
                }
                // 标记「用户装过」：tmpfs 重启就没了，开机自愈靠它判断。
                AdbShell.run("echo " + PATH_DIR + " > " + PATH_MARK
                        + "; chmod 0644 " + PATH_MARK, 15000);
                step("完成：现在任何脚本里直接写 su 就能用（重启后自动补挂）");
                testSuInner();
                dumpSu("rc2_supath.txt");
            }
        }).start();
    }

    private void uninstallPathSu() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                AdbShell.Result r = AdbShell.run(UNINSTALL_PATH + "ls -la " + PATH_DIR,
                        20000);
                step(r.text().trim());
                step(PATH_DIR + " 已恢复为只读分区上的原件");
                dumpSu("rc2_unsupath.txt");
            }
        }).start();
    }

    /**
     * 无头钩子：跑一遍开机自愈逻辑（{@link #reapplyPathSu()}）。
     *
     * <p>存在的意义是能真验：直接 call 静态方法，跑的就是
     * {@link BootReceiver} 重启后会跑的那段代码，只是不用真的重启。
     */
    private void reapplyPathSuAuto() {
        new Thread(new Runnable() {
            public void run() {
                sb.setLength(0);
                step("开机自愈复现（不重启，直接跑 BootReceiver 的那段逻辑）…");
                String r = reapplyPathSu();
                step("结果：" + r);
                step("--- " + PATH_DIR + " ---");
                step(AdbShell.run("ls -la " + PATH_DIR + "; echo @@; "
                        + "grep \" " + PATH_DIR + " \" /proc/mounts", 20000).text().trim());
                step("--- 开机自愈日志（" + BOOT_LOG + "）---");
                step(AdbShell.run("cat " + BOOT_LOG + " 2>/dev/null || echo \"（还没有记录）\"",
                        20000).text().trim());
                testSuInner();
                dumpSu("rc2_reapply.txt");
            }
        }).start();
    }

    /** 无头核验用：把当前回显区写进 getFilesDir()，adb 直接 pull 就能看。 */
    private void dumpSu(final String name) {
        final String t = sb.toString();
        new Thread(new Runnable() {
            public void run() {
                try {
                    FileOutputStream o = new FileOutputStream(
                            new File(getFilesDir(), name), false);
                    o.write(t.getBytes("UTF-8"));
                    o.close();
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void testSuInner() {
        String appPath = "/product/bin:/apex/com.android.runtime/bin:"
                + "/apex/com.android.art/bin:/system_ext/bin:/system/bin";
        AdbShell.Result r = AdbShell.run(
                "echo '--- 裸 su（真实 app uid，只用 PATH）---'; "
              + "busybox setuidgid " + getApplicationInfo().uid + " env PATH=" + appPath
              + " sh -c 'command -v su; su -c id' 2>&1; "
              + "echo '--- su -c getprop ---'; " + KSU_BIN + "/su -c 'getprop ro.product.model'; "
              + "echo '--- ksud --version ---'; " + KSU_BIN + "/ksud --version; "
              + "echo '--- ksud module list ---'; " + KSU_BIN + "/ksud module list", 90000);
        final String t = (r.text().trim() + "\n" + inProcessSu()).trim();
        step("su 自检：\n" + t);
        runOnUiThread(new Runnable() {
            public void run() {
                toast(t.indexOf("uid=0") >= 0 ? "su 通道正常：uid=0" : "su 自检见下方回显");
            }
        });
    }

    /**
     * 最严格的一条自检：让**本应用自己的进程**（uid 10030、
     * {@code u:r:untrusted_app:s0}）直接 fork/exec {@code su}。
     *
     * <p>上面那些 {@code busybox setuidgid} 只换了 uid，SELinux 上下文仍是
     * {@code u:r:su:s0}，等于开了后门在自证——真实第三方应用跑在 untrusted_app
     * 下，能不能成必须在这里验。
     */
    private static String inProcessSu() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 本进程内 exec su（真实 untrusted_app 上下文）---\n");
        for (String[] c : new String[][] {
                { "su", "-c", "id" },
                { "su", "-c", "getprop ro.product.model" },
                { "sh", "-c", "su -c 'id -u' ; echo rc=$?" } }) {
            try {
                ProcessBuilder pb = new ProcessBuilder(c);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                StringBuilder o = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) { o.append(ln).append('\n'); }
                br.close();
                int rc = p.waitFor();
                sb.append("$ ").append(join(c)).append('\n');
                sb.append(o.toString().trim()).append("   [rc=").append(rc).append("]\n");
            } catch (Throwable e) {
                sb.append("$ ").append(join(c)).append("  →  ")
                  .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage())
                  .append('\n');
            }
        }
        return sb.toString();
    }

    private static String join(String[] a) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) { s.append(' '); }
            s.append(a[i]);
        }
        return s.toString();
    }

    // ============================================================== danger

    private void cleanup() {
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run(
                        "rm -rf /data/local/tmp/nexus-su /data/local/tmp/rc2-KernelSU.apk "
                      + "/data/local/tmp/su /data/local/tmp/ksu300.apk; echo cleaned", 30000);
                final String t = r.text().trim();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("已清理临时文件");
                        sb.setLength(0);
                        step(t);
                        detect();
                    }
                });
            }
        }).start();
    }

    private void uninstallManager() {
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run(
                        "pm uninstall me.weishu.kernelsu 2>&1 | tail -1", 90000);
                final String t = r.text().trim();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("管理器：" + (t.length() == 0 ? "已卸载" : t));
                        detect();
                    }
                });
            }
        }).start();
    }

    private void selfGrant() {
        sb.setLength(0);
        new Thread(new Runnable() {
            public void run() {
                String pkg = getPackageName();
                step("包名：" + pkg);

                step("\n[appops]");
                String[] ops = {
                        "WRITE_SETTINGS", "SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS",
                        "READ_LOGS", "GET_APP_OPS_STATS", "PACKAGE_USAGE_STATS",
                        "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND",
                        "WRITE_SECURE_SETTINGS", "PROJECT_MEDIA",
                };
                for (int i = 0; i < ops.length; i++) {
                    AdbShell.Result r = AdbShell.run(
                            "appops set " + pkg + " " + ops[i] + " allow", 10000);
                    boolean good = r.code == 0 && r.text().trim().length() == 0;
                    step("  " + (good ? "OK  " : "skip") + " " + ops[i]
                            + (good ? "" : "  " + oneline(r.text())));
                }

                step("\n[pm grant]");
                String[] perms = {
                        "android.permission.WRITE_SECURE_SETTINGS",
                        "android.permission.READ_LOGS",
                        "android.permission.PACKAGE_USAGE_STATS",
                        "android.permission.DUMP",
                        "android.permission.REBOOT",
                        "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
                        "android.permission.WRITE_MEDIA_STORAGE",
                };
                for (int i = 0; i < perms.length; i++) {
                    AdbShell.Result r = AdbShell.run(
                            "pm grant " + pkg + " " + perms[i], 10000);
                    boolean good = r.text().trim().length() == 0;
                    step("  " + (good ? "OK  " : "skip") + " " + perms[i]
                            + (good ? "" : "  " + oneline(r.text())));
                }

                step("\n[白名单]");
                AdbShell.Result d = AdbShell.run(
                        "dumpsys deviceidle whitelist +" + pkg + " 2>&1 | tail -1", 20000);
                step("  " + oneline(d.text()));

                step("\n[复核]");
                AdbShell.Result c = AdbShell.run(
                        "appops get " + pkg + " WRITE_SETTINGS; "
                      + "appops get " + pkg + " SYSTEM_ALERT_WINDOW; "
                      + "dumpsys package " + pkg + " | grep -A3 'grantedPermissions' | head -8",
                        20000);
                step(c.text().trim());
                step("\n完成。删掉本 App 后这些授权会随之消失。");
                done(true);
            }
        }).start();
    }

    // ============================================================= helpers

    private View scrollRow(View row) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        hs.addView(row);
        return hs;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static List<String> lines(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        String[] l = s.split("\n");
        for (int i = 0; i < l.length; i++) {
            String t = l[i].trim();
            if (t.length() > 0 && !"lost+found".equals(t) && t.indexOf("@@") < 0) {
                out.add(t);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static String oneline(String s) {
        if (s == null) {
            return "（无输出）";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() == 0 ? "（无输出）" : (t.length() > 240 ? t.substring(0, 240) + "…" : t);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
