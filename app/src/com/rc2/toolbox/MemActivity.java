package com.rc2.toolbox;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 扩展运行内存（换页 / 压缩内存）。
 *
 * 这台 RC 2 只有 1.75 GB 物理内存，开机就被吃掉 1.6 GB 左右，厂商自带的
 * zram 只有 1.5 GB。内存一紧张系统就开始杀后台，飞行 App 也跟着卡。这里给
 * 两条可以同时用、也可以单独用的路子：
 *
 *   一、压缩内存（zram）——往 /sys/class/zram-control/hot_add 要一个新设备，
 *       按设置的大小建 zram1。页面在内存里压缩存放，速度最快，立即可用，
 *       不占存储空间。代价是压缩本身要花一点 CPU。
 *
 *   二、交换文件（swapfile）——在 /data 上开一个文件当 swap。完全不动物理
 *       内存，但是落在闪存上，速度比 zram 慢很多，而且占存储空间。
 *
 * 两个设置都会写进 SharedPreferences，开机后由 BootReceiver 重新应用，
 * 所以是真的「重启生效」，不是只对本次开机有效。
 *
 * 所有命令都走设备自带的 root adbd（uid=0），不需要额外授权。
 */
public class MemActivity extends Activity {

    // ---------------------------------------------------------------- 常量

    /** 偏好文件名，独立存放，不与其它模块互相影响。 */
    public static final String PREF = "rc2_mem";
    public static final String KEY_ZRAM = "zram_mb";
    public static final String KEY_FILE = "file_mb";

    /** 交换文件放这里；/data 是 f2fs，可以正常建交换文件。 */
    public static final String SWAP_DIR = "/data/rc2swap";
    public static final String SWAP_IMG = SWAP_DIR + "/swap.img";

    /** 我们自己热插的那个 zram 设备。厂商只给了 zram0，不动它。 */
    private static final String ZRAM_DEV = "/dev/block/zram1";
    private static final String ZRAM_SYS = "/sys/block/zram1";
    private static final String ZRAM_HOT_ADD = "/sys/class/zram-control/hot_add";
    private static final String ZRAM_HOT_DEL = "/sys/class/zram-control/hot_remove";

    /** zram 的优先级给高一点，让内核优先往压缩内存里放。 */
    private static final int ZRAM_PRIO = 5;
    /** 交换文件优先级低，只有在 zram 也满了才会用到闪存。 */
    private static final int FILE_PRIO = 1;

    /** 建交换文件很慢（实测 2 GB 要 100 秒左右），超时给足。 */
    private static final int DD_TIMEOUT = 15 * 60 * 1000;

    private LinearLayout body;
    private TextView log;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        refresh();
        auto();
    }

    /**
     * 调试钩子。这台机器的触摸注入坐标系和显示是对不上的，用 `input tap`
     * 点不准，所以留一个可以直接从 adb 触发动作的口子：
     *   am start -n com.rc2.toolbox/.MemActivity --es auto zram3
     * zram2 / zram3 / zram4 / zram6、file1 / file2 / file3 / file4、alloff
     */
    private void auto() {
        if (getIntent() == null) return;
        final String a = getIntent().getStringExtra("auto");
        if (a == null) return;
        ui.postDelayed(new Runnable() {
            public void run() {
                try {
                    if ("alloff".equals(a)) { go(0, 0); return; }
                    if ("file1".equals(a)) { go(nowZramMb(), 1024); return; }
                    if ("file2".equals(a)) { go(nowZramMb(), 2048); return; }
                    if ("file3".equals(a)) { go(nowZramMb(), 3072); return; }
                    if ("file4".equals(a)) { go(nowZramMb(), 4096); return; }
                    if (a.startsWith("zram")) {
                        int gb = Integer.parseInt(a.substring(4));
                        go(gb * 1024, nowFileMb());
                    }
                } catch (Throwable ignored) { }
            }
        }, 1500L);
    }

    private void say(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (log != null) log.setText(s);
            }
        });
    }

    private void append(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (log != null) log.setText(log.getText() + "\n" + s);
            }
        });
    }

    // ---------------------------------------------------------------- 界面

    private void build() {
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 32));

        body.addView(Ui.header(this, "扩展运行内存"));
        body.addView(Ui.hint(this,
                "这台机器只有 1.75 GB 物理内存，厂商给的压缩内存只有 1.5 GB。"
                        + "下面两种可以叠加使用，设置完会记住，重启后自动重新应用。"));

        body.addView(buildStatus());

        body.addView(Ui.header(this, "一、压缩内存（zram，推荐）"));
        body.addView(Ui.hint(this,
                "在内存里划一块压缩区当交换用。速度最快，不占存储。"
                        + "不会动厂商原本那块 zram0，是另外加一块。"));
        body.addView(buildZramRow());

        body.addView(Ui.header(this, "二、交换文件（用存储换内存）"));
        body.addView(Ui.hint(this,
                "在 /data 上开一个文件当 swap。不占物理内存，但落在闪存上，"
                        + "速度慢不少，而且会吃掉存储空间。内存实在不够时再开。"));
        body.addView(buildFileRow());
        body.addView(buildFreeSpace());

        body.addView(Ui.divider(this));

        body.addView(Ui.header(this, "全部关掉"));
        body.addView(Ui.card(this, "关闭所有额外内存",
                "撤掉我们加的 zram 和交换文件，恢复成出厂状态（厂商那块 zram0 保留）",
                0, new View.OnClickListener() {
                    public void onClick(View v) {
                        go(0, 0);
                    }
                }));

        body.addView(Ui.divider(this));

        log = Ui.mono(this, "（输出会打在这里）", 11f, Ui.col(this, R.color.text_dim));
        log.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(log);

        body.addView(Ui.footer(this));

        ScrollView sc = Ui.scroller(this, body);
        setContentView(sc);
    }

    private View buildStatus() {
        LinearLayout box = Ui.column(this);
        box.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView tv = Ui.mono(this, "读取中…", 12f, Ui.col(this, R.color.text));
        tv.setLineSpacing(0f, 1.35f);
        box.addView(tv);
        statusView = tv;
        return box;
    }

    private TextView statusView;

    private View buildZramRow() {
        LinearLayout wrap = Ui.column(this);
        wrap.addView(choices(
                new int[]{ 0, 2048, 3072, 4096, 6144 },
                new String[]{ "关", "2 GB", "3 GB", "4 GB", "6 GB" }, true));
        return wrap;
    }

    private View buildFileRow() {
        LinearLayout wrap = Ui.column(this);
        wrap.addView(choices(
                new int[]{ 0, 1024, 2048, 3072, 4096 },
                new String[]{ "关", "1 GB", "2 GB", "3 GB", "4 GB" }, false));
        return wrap;
    }

    private TextView freeView;

    private View buildFreeSpace() {
        freeView = Ui.mono(this, "", 11f, Ui.col(this, R.color.text_dim));
        freeView.setPadding(0, Ui.dp(this, 6), 0, 0);
        return freeView;
    }

    /** 一排按钮；zram 那排走 KEY_ZRAM，文件那排走 KEY_FILE。 */
    private View choices(final int[] sizes, String[] labels, final boolean zram) {
        LinearLayout row = Ui.row(this);
        for (int i = 0; i < sizes.length; i++) {
            final int mb = sizes[i];
            Button btn = Ui.button(this, labels[i], new View.OnClickListener() {
                public void onClick(View v) {
                    int z = prefs().getInt(KEY_ZRAM, 0);
                    int f = prefs().getInt(KEY_FILE, 0);
                    if (zram) z = mb; else f = mb;
                    go(z, f);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, 6);
            btn.setLayoutParams(lp);
            row.addView(btn);
        }
        return row;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private int nowZramMb() {
        SharedPreferences p = prefs();
        return p.getInt(KEY_ZRAM, 0);
    }

    private int nowFileMb() {
        SharedPreferences p = prefs();
        return p.getInt(KEY_FILE, 0);
    }

    // ---------------------------------------------------------------- 动作

    private void go(final int zramMb, final int fileMb) {
        prefs().edit().putInt(KEY_ZRAM, zramMb).putInt(KEY_FILE, fileMb).apply();
        log.setText("（正在应用…）");
        new Thread(new Runnable() {
            public void run() {
                String out = apply(zramMb, fileMb);
                append(out);
                refresh();
            }
        }).start();
    }

    private void refresh() {
        new Thread(new Runnable() {
            public void run() {
                final String mem = AdbShell.run("free -m", 20000).text();
                final String sw = AdbShell.run("cat /proc/swaps", 20000).text();
                final String df = AdbShell.run("df -h /data 2>/dev/null | tail -1", 20000).text();
                ui.post(new Runnable() {
                    public void run() {
                        if (statusView != null) {
                            statusView.setText(summary(mem, sw));
                        }
                        if (freeView != null) {
                            freeView.setText("/data 空间：" + df.trim());
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------- 真正干活的逻辑

    /**
     * 把 free -m 与 /proc/swaps 拼成一张给人看的内存总览。
     *
     * 为什么要这么写：用户按"扩展内存"之后跑去系统设置里看，发现数字没变，
     * 就以为没生效。根因是系统设置那一页只显示物理 RAM，而扩展出来的是
     * 压缩交换（swap），任何软件都改不了物理 RAM 的显示。所以这里把
     * "物理内存 / 厂商原有 / 我扩展的 / 合计可调度" 四行摆在一起，
     * 让效果有地方看。
     */
    private static String summary(String mem, String swaps) {
        int ram = 0, swapTotal = 0, swapUsed = 0;
        for (String raw : mem.split("\n")) {
            String s = raw.trim();
            String[] p = s.split("\\s+");
            if (s.startsWith("Mem:") && p.length >= 3) {
                ram = num(p[1]);
            } else if (s.startsWith("Swap:") && p.length >= 3) {
                swapTotal = num(p[1]);
                swapUsed = num(p[2]);
            }
        }

        // zram0 出厂就带（优先级 32758），我加进来的那块优先级是 5。
        int vendor = 0, mine = 0;
        for (String raw : swaps.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("Filename")) continue;
            String[] p = s.split("\\s+");
            if (p.length < 5) continue;
            int mb = num(p[2]) / 1024;
            if (num(p[4]) > 1000) vendor += mb;
            else mine += mb;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("物理运行内存  ").append(ram).append(" MB");
        sb.append("\n   系统「设置」里显示的就是这一项，任何软件都改不动它");
        sb.append("\n\n厂商原有压缩内存  ").append(vendor).append(" MB");
        sb.append("\n本次扩展  ").append(mine).append(" MB");
        if (mine > 0) {
            sb.append("   已生效，重启后自动恢复");
        } else {
            sb.append("   （当前没开扩展）");
        }
        sb.append("\n\n合计可调度  ").append(ram + swapTotal).append(" MB");
        if (mine > 0) {
            sb.append("   比出厂多 ").append(mine).append(" MB");
        }
        sb.append("\n已用交换  ").append(swapUsed).append(" MB / ").append(swapTotal).append(" MB");
        return sb.toString();
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 把额外的压缩内存 / 交换文件设成指定大小。单位 MB，0 表示关掉。
     * 返回一段可以直接打在界面上的记录。
     *
     * 这个方法被 BootReceiver 复用，所以不要碰任何界面对象。
     */
    public static String apply(int zramMb, int fileMb) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 应用设置：zram ").append(zramMb).append(" MB，")
          .append("交换文件 ").append(fileMb).append(" MB ===");

        // ---------- 1. 先撤掉旧的，避免残留 ----------
        sh("swapoff " + ZRAM_DEV + " 2>/dev/null");
        sh("swapoff " + SWAP_IMG + " 2>/dev/null");
        sb.append("\n[1/4] 已撤下旧的扩展");

        // ---------- 2. 压缩内存 ----------
        if (zramMb > 0) {
            // 设备不在就热插一个。厂商只给了 zram0，这是额外的第二块。
            if (!exists(ZRAM_SYS)) {
                String added = sh("cat " + ZRAM_HOT_ADD);
                sb.append("\n[2/4] hot_add → zram").append(added.trim());
                sleep(300);
            } else {
                // 已经存在：清干净再改大小，否则内核不认。
                sh("echo 1 > " + ZRAM_SYS + "/reset");
                sb.append("\n[2/4] 复用已有的 zram1");
            }
            // zstd 压得比 lz4 狠，换来的可用内存更多；不支持就算了。
            sh("echo zstd > " + ZRAM_SYS + "/comp_algorithm 2>/dev/null");
            long bytes = (long) zramMb * 1024L * 1024L;
            sh("echo " + bytes + " > " + ZRAM_SYS + "/disksize");
            String mk = sh("mkswap " + ZRAM_DEV + " 2>&1");
            String on = sh("swapon -p " + ZRAM_PRIO + " " + ZRAM_DEV + " 2>&1");
            sb.append("\n    ").append(bytes).append(" 字节 → ")
              .append(one(mk)).append(" / ").append(one(on));
        } else {
            // 关掉：能摘就摘掉，摘不掉至少保持 swapoff。
            if (exists(ZRAM_SYS)) {
                sh("echo 1 > " + ZRAM_SYS + "/reset");
                String rm = sh("echo 1 > " + ZRAM_HOT_DEL + " 2>&1");
                sb.append("\n[2/4] 已移除 zram1 ").append(one(rm));
            } else {
                sb.append("\n[2/4] 跳过（没有 zram1）");
            }
        }

        // ---------- 3. 交换文件 ----------
        if (fileMb > 0) {
            String rm = sh("rm -f " + SWAP_IMG + " 2>&1");
            sh("mkdir -p " + SWAP_DIR + " 2>&1");
            // dd 比 fallocate 稳：f2fs 上 fallocate 出来的文件内核有时不认。
            String dd = sh("dd if=/dev/zero of=" + SWAP_IMG + " bs=1M count=" + fileMb
                    + " 2>&1 | tail -1", DD_TIMEOUT);
            sh("chmod 0600 " + SWAP_IMG);
            String mk = sh("mkswap " + SWAP_IMG + " 2>&1");
            String on = sh("swapon -p " + FILE_PRIO + " " + SWAP_IMG + " 2>&1");
            sb.append("\n[3/4] 交换文件：").append(one(dd)).append(" | ")
              .append(one(mk)).append(" | ").append(one(on)).append(rm.length() > 0 ? "" : "");
        } else {
            sh("rm -rf " + SWAP_DIR + " 2>&1");
            sb.append("\n[3/4] 已删除交换文件");
        }

        // ---------- 4. 复核 ----------
        sb.append("\n[4/4] 当前状态：\n").append(sh("cat /proc/swaps").trim());
        sb.append("\n").append(sh("free -m").trim());
        return sb.toString();
    }

    /** 开机时把存下来的设置重新套一遍。 */
    public static String reapply(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int z = p.getInt(KEY_ZRAM, 0);
        int f = p.getInt(KEY_FILE, 0);
        if (z == 0 && f == 0) return "没有开启内存扩展";
        return apply(z, f);
    }

    // ---------------------------------------------------------------- 杂项

    private static String sh(String cmd) {
        return sh(cmd, 60000);
    }

    private static String sh(String cmd, int timeoutMs) {
        try {
            return AdbShell.run(cmd, timeoutMs).text();
        } catch (Throwable t) {
            return "命令失败: " + t;
        }
    }

    private static boolean exists(String path) {
        String r = sh("[ -e " + path + " ] && echo Y || echo N");
        return r.contains("Y");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) { }
    }

    /** 多行输出压成一行，方便塞进日志。 */
    private static String one(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
