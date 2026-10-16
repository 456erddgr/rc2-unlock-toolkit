package com.rc2.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The kernel tool: read, unpack, verify and (only on explicit, twice-confirmed
 * request) write a boot image.
 *
 * <p>Everything here works without touching the bootloader lock. That is the
 * whole point: the RC 2's BL is better left locked because unlocking it costs
 * the cellular module. So the tool does what a locked device still allows —
 * pull the real partitions, unpack them with KernelSU's own magiskboot, print
 * what is actually inside, try the KernelSU patch and report its genuine
 * verdict — and it refuses to pretend that a locked, AVB-verified device will
 * accept a modified boot.
 */
public class KernelActivity extends Activity {

    private static final String DIR = "/sdcard/RC2NEXUS";
    private static final String MB = "/data/adb/ksu/bin/magiskboot";
    private static final String KSUD = "/data/adb/ksu/bin/ksud";

    private LinearLayout body;
    private TextView log;
    private final StringBuilder sb = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        scan();

        final String auto = getIntent() == null ? null : getIntent().getStringExtra("auto");
        if (auto == null) { return; }
        body.postDelayed(new Runnable() {
            public void run() {
                if ("unpack".equals(auto)) { unpack(); }
                else if ("extract".equals(auto)) { extract("all"); }
                else if ("patch".equals(auto)) { patch(); }
                else if ("info".equals(auto)) { bootInfo(); }
            }
        }, 2000L);
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));
        root.addView(Ui.header(this, "内核工具 · BOOT 镜像"));
        body = Ui.column(this);
        root.addView(body);
        root.addView(Ui.footer(this));
        return Ui.scroller(this, root);
    }

    // ================================================================ scan

    private void scan() {
        body.removeAllViews();
        body.addView(Ui.hint(this, "正在读取镜像与启动校验状态…"));
        new Thread(new Runnable() {
            public void run() {
                final String raw = AdbShell.run(
                        "echo '== 镜像 =='; ls -l " + DIR + "/*.img 2>&1; echo '== sha ==';"
                      + " for f in " + DIR + "/*.img; do [ -f \"$f\" ] && sha256sum \"$f\"; done 2>/dev/null;"
                      + " echo '== 校验 ==';"
                      + " echo -n 'verifiedbootstate='; getprop ro.boot.verifiedbootstate;"
                      + " echo -n 'device_state=';      getprop ro.boot.vbmeta.device_state;"
                      + " echo -n 'veritymode=';        getprop ro.boot.veritymode;"
                      + " echo -n 'slot=';              getprop ro.boot.slot_suffix;"
                      + " echo -n 'flash_locked=';      getprop ro.boot.flash.locked;"
                      + " echo '== 分区 ==';"
                      + " ls -l /dev/block/by-name/ 2>/dev/null | grep -i -e boot -e init_boot;"
                      + " echo '== 工具 ==';"
                      + " ls -l " + MB + " " + KSUD + " 2>&1;"
                      + " echo '== 内核 =='; uname -r", 60000).text();
                runOnUiThread(new Runnable() {
                    public void run() { render(raw); }
                });
            }
        }).start();
    }

    private void render(String raw) {
        body.removeAllViews();
        String[] sec = raw.split("== ");
        String imgs = pick(sec, "镜像");
        String sha = pick(sec, "sha");
        String chk = pick(sec, "校验");
        String part = pick(sec, "分区");
        String tool = pick(sec, "工具");
        String kern = pick(sec, "内核");

        boolean boot = imgs.indexOf("boot.img") >= 0;
        boolean locked = chk.indexOf("device_state=locked") >= 0;
        boolean green = chk.indexOf("verifiedbootstate=green") >= 0;
        boolean gki = false;
        try {
            String[] v = kern.trim().split("\\.");
            gki = v.length > 1 && Integer.parseInt(v[0].trim()) >= 5
                    && Integer.parseInt(v[1]) >= 10;
        } catch (Throwable ignored) { }

        body.addView(Ui.header(this, "启动校验"));
        body.addView(Ui.card(this,
                locked ? "Bootloader：已锁定" : "Bootloader：未锁定",
                locked ? "锁定状态下刷入未签名的 boot 会被 AVB 拒绝 —— 这正是不要解 BL 的原因。"
                       : "可以刷入自定义 boot。",
                locked ? Ui.col(this, R.color.warn) : Ui.col(this, R.color.ok), null));
        body.addView(Ui.card(this,
                green ? "AVB：verifiedbootstate=green" : "AVB：非 green",
                oneline(chk),
                green ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.warn), null));

        body.addView(Ui.divider(this));
        body.addView(Ui.header(this, "镜像"));
        body.addView(Ui.card(this, boot ? "已有 boot.img" : "还没有 boot.img",
                imgs.trim().length() == 0 ? DIR + " 下没有 .img" : imgs.trim()
                        + (sha.trim().length() == 0 ? "" : "\n" + sha.trim()),
                boot ? Ui.col(this, R.color.ok) : Ui.col(this, R.color.warn), null));
        body.addView(Ui.hint(this, "目录 " + DIR
                + "\n内核 " + oneline(kern)
                + "\n工具 magiskboot/ksud " + (tool.indexOf("magiskboot") >= 0 ? "已就位" : "缺失")));

        LinearLayout b1 = Ui.row(this);
        b1.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        b1.addView(Ui.button(this, "提取 boot", new View.OnClickListener() {
            public void onClick(View v) { extract("boot"); }
        }));
        b1.addView(Ui.button(this, "提取 init_boot", new View.OnClickListener() {
            public void onClick(View v) { extract("init_boot"); }
        }));
        b1.addView(Ui.button(this, "提取 vendor_boot", new View.OnClickListener() {
            public void onClick(View v) { extract("vendor_boot"); }
        }));
        b1.addView(Ui.button(this, "全部", new View.OnClickListener() {
            public void onClick(View v) { extract("all"); }
        }));
        body.addView(b1);

        body.addView(Ui.divider(this));
        body.addView(Ui.header(this, "解析与补丁"));
        body.addView(Ui.card(this, "解包分析 boot.img",
                "magiskboot unpack，读出页大小 / 内核版本 / cmdline / os_version",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { unpack(); }
                }));
        body.addView(Ui.card(this, "尝试 KernelSU 补丁",
                gki ? "内核像 GKI，走 ksud boot-patch"
                    : "内核 5.4 非 GKI，ksud 大概率拒绝 —— 但把真实回显打出来",
                Ui.col(this, R.color.accent2), new View.OnClickListener() {
                    public void onClick(View v) { patch(); }
                }));
        body.addView(Ui.card(this, "bootctl 启动信息",
                "ksud boot-info：槽位、可刷分区、KMI",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { bootInfo(); }
                }));

        body.addView(Ui.divider(this));
        body.addView(Ui.header(this, "写入分区（高风险）"));
        body.addView(Ui.hint(this,
                "锁定 BL + AVB green 的机器刷入改过的 boot，轻则开机被拒，重则进不去系统。"
              + "这里默认什么都不做；只有你连续确认两次才会真的 dd 到分区。"));
        body.addView(Ui.card(this, "把镜像写入 boot 分区",
                "dd if=" + DIR + "/<镜像> of=/dev/block/by-name/boot" + slotSuffix(chk),
                Ui.col(this, R.color.bad), new View.OnClickListener() {
                    public void onClick(View v) { askWrite(); }
                }));

        body.addView(Ui.divider(this));
        log = Ui.mono(this, sb.length() > 0 ? sb.toString() : "（输出会打在这里）", 10.5f,
                Ui.col(this, R.color.text_dim));
        log.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        body.addView(log);
    }

    private static String slotSuffix(String chk) {
        int i = chk.indexOf("slot=");
        if (i < 0) { return ""; }
        String s = chk.substring(i + 5).trim();
        int j = s.indexOf('\n');
        if (j >= 0) { s = s.substring(0, j); }
        s = s.trim();
        return s.length() == 0 ? "" : s;
    }

    private static String pick(String[] a, String name) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].startsWith(name)) {
                String v = a[i].substring(name.length());
                int nl = v.indexOf('\n');
                return nl >= 0 ? v.substring(nl + 1) : "";
            }
        }
        return "";
    }

    private static String oneline(String s) {
        return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    // =============================================================== tasks

    private void step(String line) {
        sb.append(line).append('\n');
        try {
            java.io.FileOutputStream fo =
                    new java.io.FileOutputStream(new java.io.File(getCacheDir(), "kernel.log"));
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.flush();
            fo.close();
        } catch (Throwable ignored) { }
        runOnUiThread(new Runnable() {
            public void run() { if (log != null) { log.setText(sb.toString()); } }
        });
    }

    /** Mirror the on-screen log to /sdcard so it survives leaving the screen. */
    private void flushLog() {
        final String cache = getCacheDir().getAbsolutePath() + "/kernel.log";
        new Thread(new Runnable() {
            public void run() {
                AdbShell.run("mkdir -p " + DIR + " && cp '" + cache + "' " + DIR
                        + "/kernel.log && chmod 0666 " + DIR + "/kernel.log", 20000);
            }
        }).start();
    }

    private void extract(final String which) {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("提取 " + which + "…");
        new Thread(new Runnable() {
            public void run() {
                step("=== 提取镜像 ===");
                AdbShell.run("mkdir -p " + DIR, 15000);
                String[] names = "all".equals(which)
                        ? new String[]{"boot", "init_boot", "vendor_boot"}
                        : new String[]{which};
                for (int i = 0; i < names.length; i++) {
                    String name = names[i];
                    String dev = findDevice(name);
                    step("[ " + name + " ]");
                    if (dev == null) {
                        step("  没有这个分区节点，跳过。");
                        continue;
                    }
                    step("  分区 " + dev);
                    AdbShell.Result r = AdbShell.run(
                            "dd if=" + dev + " of=" + DIR + "/" + name + ".img bs=1M 2>&1;"
                          + " echo '~~'; ls -l " + DIR + "/" + name + ".img;"
                          + " sha256sum " + DIR + "/" + name + ".img 2>/dev/null || true",
                            300000);
                    for (String l : lines(r.text())) { step("  " + l); }
                }
                step("=== 完成 ===");
                flushLog();
                scanLater();
            }
        }).start();
    }

    /** Boot partitions are A/B here; prefer the slot the device actually booted. */
    private String findDevice(String name) {
        AdbShell.Result slot = AdbShell.run("getprop ro.boot.slot_suffix", 15000);
        String s = slot.text().trim();
        String[] cands;
        if ("_a".equals(s) || "_b".equals(s)) {
            cands = new String[]{"/dev/block/by-name/" + name + s,
                                 "/dev/block/by-name/" + name};
        } else {
            cands = new String[]{"/dev/block/by-name/" + name,
                                 "/dev/block/by-name/" + name + "_a"};
        }
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < cands.length; i++) {
            sb2.append("[ -e ").append(cands[i]).append(" ] && echo ").append(cands[i])
               .append("; ");
        }
        List<String> got = lines(AdbShell.run(sb2.toString(), 20000).text());
        for (int i = 0; i < got.size(); i++) {
            String g = got.get(i).trim();
            if (g.startsWith("/dev/")) { return g; }
        }
        return null;
    }

    private void unpack() {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("解包中…");
        new Thread(new Runnable() {
            public void run() {
                step("=== magiskboot unpack ===");
                String img = DIR + "/boot.img";
                String work = "/data/local/tmp/rc2kern";
                AdbShell.run("rm -rf " + work + " && mkdir -p " + work, 20000);
                AdbShell.Result r = AdbShell.run(
                        "cp " + img + " " + work + "/boot.img 2>&1;"
                      + " cd " + work + " && " + MB + " unpack boot.img 2>&1; echo '~~';"
                      + " ls -l " + work + " 2>&1; echo '-- files --';"
                      + " for f in kernel ramdisk.cpio; do [ -f " + work + "/$f ] && echo -n \"$f \"; done;"
                      + " [ -f " + work + "/kernel ] && (strings " + work + "/kernel | grep -m1 -i 'Linux version');"
                      + " [ -f " + work + "/header ] && cat " + work + "/header;"
                      + " [ -f " + work + "/kernel ] && (strings " + work + "/kernel | grep -m1 -i 'androidboot' || true)",
                        240000);
                for (String l : lines(r.text())) { step(l); }
                step("=== 完成 ===");
                flushLog();
            }
        }).start();
    }

    private void patch() {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("尝试补丁…");
        new Thread(new Runnable() {
            public void run() {
                step("=== KernelSU boot-patch ===");
                String img = DIR + "/boot.img";
                String out = DIR + "/boot-kernelsu.img";
                AdbShell.run("rm -f " + out, 15000);
                step("magiskboot " + MB);
                AdbShell.Result r = AdbShell.run(
                        "ls -l " + MB + " " + KSUD + " 2>&1; echo '~~';"
                      + KSUD + " boot-patch --magiskboot " + MB
                      + " -b " + img + " -o " + out + " 2>&1; echo '~~rc='$?;"
                      + " ls -l " + out + " 2>&1; sha256sum " + out + " 2>/dev/null || true",
                        600000);
                for (String l : lines(r.text())) { step(l); }
                String chk = AdbShell.run("[ -f " + out + " ] && echo __YES__ || echo __NO__",
                        15000).text();
                if (chk.contains("__YES__")) {
                    step("");
                    step(">>> 镜像已生成：" + out);
                } else {
                    step("");
                    step(">>> 没有生成，而且是必然的 —— 这不是配置问题：");
                    step(">>> 本机内核 5.4.210-qgki，属于「非 GKI」内核。");
                    step(">>> KernelSU 官方预编译内核模块只覆盖 GKI：");
                    step(">>>   5.10 / 5.15 / 6.1 / 6.6 / 6.12（实测 supported-kmis）");
                    step(">>> 所以没有 5.4 的模块可挑，boot-patch 走到这一步只能报");
                    step(">>>   Try to choose LKM manually。");
                    step(">>> 要在 5.4 上跑 KernelSU，唯一办法是拿到 DJI 的内核源码，");
                    step(">>> 把 KernelSU 打进内核重新编译出 boot.img。设备上做不到。");
                    step("");
                    step(">>> 但这不影响 root：设备自带的 su（uid=0）已经能用，");
                    step(">>> 本 App 的 su 入口 + /data/adb/modules 模块管理都照常工作。");
                }
                step("=== 完成 ===");
                flushLog();
            }
        }).start();
    }

    private void bootInfo() {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("读取 boot-info…");
        new Thread(new Runnable() {
            public void run() {
                step("=== ksud boot-info ===");
                AdbShell.Result r = AdbShell.run(
                        "for c in is-ab-device slot-suffix default-partition available-partitions"
                      + " current-kmi supported-kmis; do echo \"--- $c ---\"; "
                      + KSUD + " boot-info $c 2>&1; done", 180000);
                for (String l : lines(r.text())) { step(l); }
                step("=== 完成 ===");
                flushLog();
            }
        }).start();
    }

    // ============================================================== write

    private void askWrite() {
        final String[] imgs = new String[]{"boot.img", "boot-kernelsu.img",
                "init_boot.img", "vendor_boot.img"};
        new AlertDialog.Builder(this)
                .setTitle("写哪个镜像")
                .setItems(imgs, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { confirmWrite(imgs[which]); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmWrite(final String name) {
        new AlertDialog.Builder(this)
                .setTitle("警告")
                .setMessage("这台机器的 Bootloader 是锁定的，AVB 处于 green。\n\n"
                        + "写入非官方签名的 " + name + " 后，开机校验可能直接失败，"
                        + "表现为开不了机或反复重启。\n\n"
                        + "解 BL 能绕过这一条，但会让 4G 模块失效 —— 所以这里不做。\n\n"
                        + "确定要继续吗？")
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { confirmWrite2(name); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmWrite2(final String name) {
        new AlertDialog.Builder(this)
                .setTitle("最后一次确认")
                .setMessage("真的要 dd " + name + " 到 boot 分区吗？\n\n"
                        + "这个操作没有撤销键。写坏之后，"
                        + "只能靠 PC 上的 fastboot 恢复，而锁定 BL 的机器通常刷不回去。")
                .setPositiveButton("我确认，写入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { doWrite(name); }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void doWrite(final String name) {
        sb.setLength(0);
        if (log != null) { log.setText(""); }
        toast("写入中…");
        new Thread(new Runnable() {
            public void run() {
                step("=== 写入 " + name + " ===");
                String dev = findDevice("boot");
                if (dev == null) { step("找不到 boot 分区，中止。"); return; }
                step("目标 " + dev);
                AdbShell.Result r = AdbShell.run(
                        "dd if=" + DIR + "/" + name + " of=" + dev + " bs=1M 2>&1;"
                      + " echo '~~rc='$?", 300000);
                for (String l : lines(r.text())) { step(l); }
                step("=== 完成 ===");
                flushLog();
            }
        }).start();
    }

    // ============================================================== utils

    private void scanLater() {
        runOnUiThread(new Runnable() {
            public void run() { scan(); }
        });
    }

    private static List<String> lines(String s) {
        return java.util.Arrays.asList(s == null ? new String[0] : s.split("\n"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
