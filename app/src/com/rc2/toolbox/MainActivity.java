package com.rc2.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The dashboard.
 *
 * Design rule: everything that "does something" is a visible switch or button,
 * and the app never silently writes anything the user cannot undo.
 */
public class MainActivity extends Activity {

    private LinearLayout root;
    private TextView pill;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        probeRoot();
        auto();
    }

    /**
     * 无头自检入口。
     *
     * 遥控器上点不动界面时，用 adb 驱动本应用跑一次检查，结果写到
     * getFilesDir()/rc2_state.txt，再用 root 通道读回来：
     *
     *   am start -n com.rc2.toolbox/.MainActivity --es auto root
     */
    private void auto() {
        Intent it = getIntent();
        final String a = it == null ? null : it.getStringExtra("auto");
        if (a == null) return;
        root.postDelayed(new Runnable() {
            public void run() {
                if ("root".equals(a)) dumpRoot(a);
                else if ("temproot".equals(a)) dumpTempRoot(a);
                else if ("portrait".equals(a)) dumpRotation(a, 0);
                else if ("landscape".equals(a)) dumpRotation(a, 90);
                else if ("upside".equals(a)) dumpRotation(a, 180);
                else if ("gravity".equals(a)) dumpRotation(a, -1);
                else if ("orient".equals(a)) dumpOrient(it.getIntExtra("deg", 0));
                else if ("rootadb".equals(a)) dumpRootAdb(a);
                else if ("fastboot".equals(a)) dumpFastboot(a, it.getBooleanExtra("go", false));
            }
        }, 1500L);
    }

    /**
     * 试另一条路：不写 Settings.System，直接让这个 Activity 请求方向。
     *
     *   am start -n com.rc2.toolbox/.MainActivity --es auto orient --ei deg 0
     */
    private void dumpOrient(final int deg) {
        if (deg == -1 || deg == -2) {
            // 全局转屏：这台机器 rotation 锁死，只能反过来设屏幕尺寸。
            final String cmd = deg == -1 ? "wm size 1920x1080" : "wm size reset";
            new Thread(new Runnable() {
                public void run() {
                    AdbShell.Result r = AdbShell.run(cmd, 8000);
                    String wm = AdbShell.run("wm size", 5000).text();
                    writeState("tag=orient\ndeg=" + deg + "\ncmd=" + cmd + "\ncode=" + r.code
                            + "\nout=" + r.text() + "\nwm=" + wm + "\n");
                }
            }).start();
            return;
        }
        final int o;
        if (deg == 90) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        else if (deg == 180) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
        else if (deg == 270) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        else if (deg < 0) o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        else o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        StringBuilder sb = new StringBuilder();
        sb.append("tag=orient\n").append("deg=").append(deg).append('\n');
        try {
            setRequestedOrientation(o);
            sb.append("call.ok=true\n").append("requested=").append(o).append('\n');
        } catch (Throwable t) {
            sb.append("call.ok=false\n").append("err=").append(t).append('\n');
        }
        writeState(sb.toString());
        // 转屏是异步的，2.5 秒后把真实结果补一次。
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(2500L); } catch (Throwable ignored) { }
                StringBuilder s2 = new StringBuilder();
                s2.append("tag=orient\n").append("deg=").append(deg).append('\n');
                s2.append("call.ok=true\n").append("requested=").append(o).append('\n');
                try {
                    s2.append("requested.now=").append(getRequestedOrientation()).append('\n');
                } catch (Throwable ignored) { }
                try {
                    android.view.Display d = getWindowManager().getDefaultDisplay();
                    s2.append("display.rotation=").append(d.getRotation()).append('\n');
                    s2.append("display.w=").append(d.getWidth()).append('\n');
                    s2.append("display.h=").append(d.getHeight()).append('\n');
                } catch (Throwable ignored) { }
                writeState(s2.toString());
            }
        }).start();
    }

    /** 直接跑一遍屏幕方向逻辑，把结果写进 rc2_state.txt。 */
    private void dumpRotation(final String tag, final int degrees) {
        final boolean ok = degrees < 0
                ? SysSettings.releaseRotation(this)
                : SysSettings.forceRotation(this, degrees);
        StringBuilder sb = new StringBuilder();
        sb.append("tag=").append(tag).append('\n');
        sb.append("degrees=").append(degrees).append('\n');
        sb.append("call.ok=").append(ok).append('\n');
        sb.append("accelerometer_rotation=")
          .append(SysSettings.getInt(this, android.provider.Settings.System.ACCELEROMETER_ROTATION, -1)).append('\n');
        sb.append("user_rotation=")
          .append(SysSettings.getInt(this, android.provider.Settings.System.USER_ROTATION, -1)).append('\n');
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            sb.append("display.rotation=").append(d.getRotation()).append('\n');
            sb.append("display.w=").append(d.getWidth()).append('\n');
            sb.append("display.h=").append(d.getHeight()).append('\n');
        } catch (Throwable ignored) { }
        writeState(sb.toString());
    }

    /** 原子落盘，供 adb 侧读取。 */
    private void writeState(final String body) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "rc2_state.txt");
            java.io.FileOutputStream o = new java.io.FileOutputStream(f, false);
            o.write(body.getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) { }
    }

    /** 把 root 通道的实况落盘，绕过界面直接看结果。 */
    private void dumpRoot(final String tag) {
        // 全部必须在后台线程：ADB 通道是真实 socket，主线程会抛 NetworkOnMainThreadException。
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("tag=").append(tag).append('\n');
                sb.append("port=").append(AdbShell.findPort()).append('\n');
                sb.append("identity=").append(AdbShell.identity()).append('\n');
                sb.append("lastError=").append(AdbShell.lastError).append('\n');
                AdbShell.Result r = AdbShell.run("id", 8000);
                sb.append("id.code=").append(r.code).append('\n');
                sb.append("id.out=").append(r.text().trim()).append('\n');
                writeState(sb.toString());
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        probeRoot();
    }

    /** Keeps the hero pill honest: it talks to the loopback daemon off the UI thread. */
    private void probeRoot() {
        final TextView p = pill;
        if (p == null) {
            return;
        }
        p.setText("正在检测 root…");
        p.setTextColor(Ui.col(this, R.color.warn));
        new Thread(new Runnable() {
            public void run() {
                final int port = AdbShell.findPort();
                final String id = port > 0 ? AdbShell.identity() : null;
                final boolean isRoot = port > 0 && id != null && id.indexOf("uid=0") >= 0;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (p != pill) {
                            return;
                        }
                        if (isRoot) {
                            p.setText("root 已就绪 · 127.0.0.1:" + port);
                            p.setTextColor(Ui.col(MainActivity.this, R.color.ok));
                        } else if (port > 0) {
                            p.setText("已连上 " + port + " 但不是 root");
                            p.setTextColor(Ui.col(MainActivity.this, R.color.bad));
                        } else {
                            p.setText("root 通道不可用：" + AdbShell.lastError);
                            p.setTextColor(Ui.col(MainActivity.this, R.color.bad));
                        }
                    }
                });
            }
        }).start();
    }

    private void build() {
        root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        // ---- hero
        root.addView(Ui.banner(this, Ui.APP, "DJI RC 2 上的多功能工具　·　"
                + Ui.sdkLine()));

        LinearLayout stat = Ui.row(this);
        stat.setPadding(Ui.dp(this, 18), Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 4));
        pill = Ui.pill(this, "正在检测 root…", Ui.col(this, R.color.warn),
                Ui.col(this, R.color.card));
        stat.addView(pill);
        root.addView(stat);
        root.addView(Ui.divider(this));

        // ================================================== SCREEN
        root.addView(Ui.header(this, "屏幕与旋转"));
        root.addView(Ui.hint(this,
                "强制竖屏/横屏/翻转 180°、熄屏时间、亮度。"
              + "方向控制会关掉自动旋转再锁定，所以不会点了没反应。"));

        root.addView(Ui.card(this, "屏幕与旋转（完整面板）",
                "熄屏时间、亮度、自动旋转、强制方向，都在这一页",
                new View.OnClickListener() {
                    public void onClick(View v) { openSysPanel(); }
                }));

        root.addView(Ui.divider(this));

        // ================================================== ROOT
        root.addView(Ui.header(this, "ROOT 通道（v2.0 核心）"));
        root.addView(Ui.hint(this,
                "root 来自本机 5555 端口上那个以 root 身份启动的 adbd（adbd-root），"
              + "而 ro.adb.secure=0、不校验认证，所以本工具不用电脑就能连上去拿 uid=0。\n"
              + "它是「临时」的：重启后系统自带 adbd 会回来，root 掉一次，"
              + "用下面那张「临时 root（一键找回）」再把它拉起来即可。"));

        root.addView(Ui.card(this, "root 命令行",
                "以 uid=0 执行任意 shell 命令，内置电池/温度/音量流/日志等常用项",
                new View.OnClickListener() {
                    public void onClick(View v) { openShell(); }
                }));

        root.addView(Ui.card(this, "检测 root 通道",
                "点一下就知道现在能不能拿到 uid=0(root)",
                new View.OnClickListener() {
                    public void onClick(View v) { checkRoot(); }
                }));

        root.addView(Ui.card(this, "临时 root（一键找回）",
                "重启后 root 会掉。点一下执行 setuid 启动器，"
              + "让它重新注入并拉起 5555 上的 root adbd",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { startTempRoot(); }
                }));

        root.addView(Ui.card(this, "root ADB 状态",
                "看 5555 上那个 adbd-root 到底在不在：pid、ready 标记、"
              + "日志尾部，以及从 PC 连上去要用的命令",
                new View.OnClickListener() {
                    public void onClick(View v) { showRootAdb(); }
                }));

        root.addView(Ui.card(this, "重启进 Fastboot 模式",
                "进 bootloader 调试。带 svc / setprop / reboot 三级回退，"
              + "并给出 fastboot continue 的回来方法",
                Ui.col(this, R.color.warn), new View.OnClickListener() {
                    public void onClick(View v) { confirmFastboot(); }
                }));

        root.addView(Ui.card(this, "隐藏页面（135 个）",
                "从大疆自己的清单里挖出来的未导出页面：工厂校准、固件升级、"
              + "图传 APN、埋点记录、日志上传、飞控诊断……用 root 直接打开",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { openHidden(); }
                }));

        root.addView(Ui.card(this, "文件管理器（root）",
                "以 uid=0 浏览、编辑、删除整个文件系统，含 /data 与 /system",
                Ui.col(this, R.color.accent), new View.OnClickListener() {
                    public void onClick(View v) { open(FileActivity.class); }
                }));

        root.addView(Ui.card(this, "系统日志",
                "实时 logcat，按错误/警告/DJI 过滤，可存到 /sdcard",
                0, new View.OnClickListener() {
                    public void onClick(View v) { open(LogActivity.class); }
                }));

        root.addView(Ui.card(this, "KernelSU",
                "检测内核是否已打补丁、安装内置管理器、管理 /data/adb/modules 模块",
                0, new View.OnClickListener() {
                    public void onClick(View v) { open(KsuActivity.class); }
                }));

        root.addView(Ui.card(this, "内核工具",
                "提取 boot / init_boot / vendor_boot，解包分析，尝试 KernelSU 补丁",
                0, new View.OnClickListener() {
                    public void onClick(View v) { open(KernelActivity.class); }
                }));

        root.addView(Ui.divider(this));

        // ============================================ 大疆工程工具 + 内存扩展
        root.addView(Ui.header(this, "大疆工程工具"));
        root.addView(Ui.hint(this,
                "固件里自带、但出厂被禁用的大疆工厂工具。进来先把包启用，"
                        + "再一个个点开：MCU、SDR、TypeC、FTM、结温、产测 2.0……"));

        root.addView(Ui.card(this, "大疆工程工具（37 个页面）",
                "开发助手 · 产测 2.0 · 设置向导，全部页面一键拉起",
                0, new View.OnClickListener() {
                    public void onClick(View v) { open(DjiActivity.class); }
                }));

        root.addView(Ui.card(this, "扩展运行内存",
                "加压缩内存 / 交换文件，自己定扩展多少，设置好重启也还在",
                0, new View.OnClickListener() {
                    public void onClick(View v) { open(MemActivity.class); }
                }));

        root.addView(Ui.divider(this));

        // ================================================== JUMP
        root.addView(Ui.header(this, getString(R.string.sec_jump)));
        root.addView(Ui.hint(this, getString(R.string.sec_jump_desc)));
        root.addView(Ui.card(this, getString(R.string.jump_dji),
                "大疆官方深链与已导出页面，点开即用",
                new View.OnClickListener() {
                    public void onClick(View v) { openJump(0); }
                }));
        root.addView(Ui.card(this, getString(R.string.jump_sys),
                "安卓系统里平时被藏起来的设置页",
                new View.OnClickListener() {
                    public void onClick(View v) { openJump(1); }
                }));
        root.addView(Ui.card(this, getString(R.string.jump_diag),
                "只能通过 adb 打开的内部页面（带命令，可复制）",
                new View.OnClickListener() {
                    public void onClick(View v) { openJump(2); }
                }));

        root.addView(Ui.divider(this));

        // ================================================== TOOLS
        root.addView(Ui.header(this, getString(R.string.sec_tools)));
        root.addView(Ui.hint(this, getString(R.string.sec_tools_desc)));

        root.addView(Ui.card(this, "应用管理 / 桌面启动器",
                "列出大疆全家桶和自装应用，点一下直接启动；长按可强停、卸载、看权限",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, AppsActivity.class));
                    }
                }));

        root.addView(Ui.card(this, "释放大疆 Fly 的电池限制",
                "打开系统的电池优化白名单，避免大疆 Fly 被系统杀后台",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        jump("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
                    }
                }));

        root.addView(Ui.card(this, "给大疆 Fly 开悬浮窗权限",
                "打开系统的悬浮窗授权页",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        jumpPkg("android.settings.action.MANAGE_OVERLAY_PERMISSION", Catalog.DJI_PKG);
                    }
                }));

        root.addView(Ui.card(this, "大疆 Fly 的所有权限",
                "查看并修改大疆 Fly 拿到的权限",
                new View.OnClickListener() {
                    public void onClick(View v) { appDetails(); }
                }));

        root.addView(Ui.divider(this));

        // ================================================== INFO
        root.addView(Ui.header(this, getString(R.string.sec_info)));
        root.addView(Ui.hint(this, getString(R.string.sec_info_desc)));
        root.addView(Ui.card(this, getString(R.string.title_info),
                "遥控器型号、系统版本、大疆 App 版本，可一键复制",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, InfoActivity.class));
                    }
                }));

        root.addView(Ui.card(this, "关于本工具",
                "说明它到底做了什么、以及做不到什么",
                new View.OnClickListener() {
                    public void onClick(View v) { about(); }
                }));

        TextView foot = Ui.hint(this, "\n屏幕方向的改动可随时交还给重力感应。");
        foot.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        root.addView(foot);
        root.addView(Ui.footer(this));

        ScrollView sc = Ui.scroller(this, root);
        sc.setBackgroundColor(Ui.col(this, R.color.bg));
        setContentView(sc);
    }

    // ------------------------------------------------------------------- nav ops

    private void openSysPanel() {
        startActivity(new Intent(this, SysPanelActivity.class));
    }

    private void openShell() {
        startActivity(new Intent(this, ShellActivity.class));
    }

    private void openHidden() {
        startActivity(new Intent(this, HiddenActivity.class));
    }

    private void open(Class<?> c) {
        startActivity(new Intent(this, c));
    }

    /** Probes the loopback root daemon without leaving the dashboard. */
    private void checkRoot() {
        toast("正在检测 root 通道…");
        new Thread(new Runnable() {
            public void run() {
                final int port = AdbShell.findPort();
                final String id = port > 0 ? AdbShell.identity() : "";
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (port == 0) {
                            toast("root 通道不可用：" + AdbShell.lastError);
                        } else {
                            toast("127.0.0.1:" + port + "  →  " + id);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 临时 root：点一下等于替用户执行那把 setuid 钥匙，再把结果读回来。
     * 全程在后台线程，最长等 90 秒（脚本自己要等注入完成）。
     */
    private void startTempRoot() {
        if (!TempRoot.installed()) {
            new AlertDialog.Builder(this)
                    .setTitle("临时 root 不可用")
                    .setMessage("本机没有装那套载荷（" + TempRoot.LAUNCHER + " 不存在）。\n\n"
                            + "这个功能只是替你把已经装好的 setuid 启动器跑一遍，"
                            + "它自己不会安装载荷。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        toast("正在启动临时 root，最多等 90 秒…");
        new Thread(new Runnable() {
            public void run() {
                final String report = TempRoot.start(90000L);
                final boolean ok = report.contains("ok=true");
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ok ? "临时 root 已就绪（uid=0）" : "没拿到 root，看下面的详情");
                        if (!ok) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("临时 root 没起来")
                                    .setMessage(report)
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                        probeRoot();
                    }
                });
            }
        }).start();
    }

    /**
     * root ADB 状态卡：把 5555 上那个 adbd-root 的实况摆出来，并给出从 PC 连的用法。
     */
    private void showRootAdb() {
        toast("正在读取 root ADB 状态…");
        // 状态探测要连 socket，不能放在主线程。
        new Thread(new Runnable() {
            public void run() {
                final RootAdb.State s = RootAdb.state();
                final String log = RootAdb.tailLog(12);
                runOnUiThread(new Runnable() {
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        sb.append(s.summary).append("\n\n");
                        sb.append("载荷已安装　").append(s.installed ? "是" : "否").append('\n');
                        sb.append("维护标记　　").append(s.disabled ? "存在（启动被跳过）" : "无").append('\n');
                        sb.append("ready 标记　").append(s.ready ? "有" : "无").append('\n');
                        sb.append("adbd-root pid　").append(s.pid.length() > 0 ? s.pid : "（没有）").append('\n');
                        sb.append("TCP ").append(RootAdb.TCP_PORT).append(" 监听　")
                          .append(s.listening ? "在" : "不在").append('\n');
                        sb.append("当前身份　　").append(s.identity.length() > 0 ? s.identity : "（拿不到）").append('\n');

                        sb.append("\n从 PC 连进来的用法：\n");
                        sb.append("  1. adb connect <本机IP>:").append(RootAdb.TCP_PORT).append('\n');
                        sb.append("  2. adb devices -l　　　（确认认到机器）\n");
                        sb.append("  3. adb shell id　　　　（应显示 uid=0(root)）\n");
                        sb.append("\n连上之后不需要认证，直接就是 root。\n");

                        if (log.length() > 0) {
                            sb.append("\n日志尾部：\n").append(log);
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("root ADB 状态")
                                .setMessage(sb.toString())
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /**
     * Fastboot 的二次确认。
     *
     * <p>这一步必须拦一下：RC 2 锁着 bootloader，进 fastboot 之后刷任何分区都回不来。
     * 确认框里把「怎么回来」写在前面，把「不要刷什么」写在后面。
     */
    private void confirmFastboot() {
        new AlertDialog.Builder(this)
                .setTitle("重启进 Fastboot 模式？")
                .setMessage("设备会立刻重启并停在 bootloader（FASTBOOT）界面。\n\n"
                        + "回来只要：\n"
                        + "  fastboot continue　（推荐，继续正常启动）\n"
                        + "  fastboot reboot　　（直接重启）\n\n"
                        + "警告：这台机器的 bootloader 是锁定的，"
                        + "不要执行 fastboot flash / erase / flashing unlock —— "
                        + "没有可刷回的分区镜像，一旦刷写或解锁就无法恢复。\n\n"
                        + "确定要继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("进 Fastboot", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        doFastboot();
                    }
                })
                .show();
    }

    /** 真正执行 fastboot 重启，并展示三级回退的记录。 */
    private void doFastboot() {
        toast("正在尝试进入 bootloader…");
        new Thread(new Runnable() {
            public void run() {
                final RootAdb.Fastboot r = RootAdb.rebootBootloader();
                runOnUiThread(new Runnable() {
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        sb.append(r.verdict()).append("\n\n");
                        sb.append("如果设备真的进了 FASTBOOT 界面，按下面这样回来：\n");
                        for (String line : RootAdb.NEED_ROOT) {
                            sb.append("  ").append(line).append('\n');
                        }
                        sb.append("\n执行记录：\n").append(r.log);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(r.ok ? "已发出 Fastboot 指令" : "Fastboot 指令没发出去")
                                .setMessage(sb.toString())
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 无头自检：root ADB 状态落盘。 */
    private void dumpRootAdb(final String tag) {
        new Thread(new Runnable() {
            public void run() {
                RootAdb.State s = RootAdb.state();
                writeState("tag=" + tag
                        + "\ninstalled=" + s.installed
                        + "\ndisabled=" + s.disabled
                        + "\nready=" + s.ready
                        + "\npid=" + s.pid
                        + "\nlistening=" + s.listening
                        + "\nidentity=" + s.identity
                        + "\nusable=" + s.usable()
                        + "\nsummary=" + s.summary
                        + "\n--- log tail ---\n" + RootAdb.tailLog(15));
            }
        }).start();
    }

    /**
     * 无头自检：Fastboot 三级回退。
     *
     * <p>默认只做 dry-run（列出每条命令是否具备执行条件），不会真重启。
     * 要真的执行必须显式加 {@code --ez go true} —— 这是故意的：
     * 这台机器进 bootloader 之后要人工拿 PC 才能拉回来，不能有任何
     * 路径让一次误触或一次自检把设备踢进去。
     *
     *   am start -n com.rc2.toolbox/.MainActivity --es auto fastboot
     *   am start -n com.rc2.toolbox/.MainActivity --es auto fastboot --ez go true
     */
    private void dumpFastboot(final String tag, final boolean go) {
        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("tag=").append(tag).append('\n');
                sb.append("go=").append(go).append('\n');
                if (!go) {
                    sb.append("mode=dry-run（没有真的重启）\n\n");
                    sb.append("svc 存在      = ").append(new File("/system/bin/svc").exists()).append('\n');
                    sb.append("setprop 存在  = ").append(new File("/system/bin/setprop").exists()).append('\n');
                    sb.append("reboot 存在   = ").append(new File("/system/bin/reboot").exists()).append('\n');
                    sb.append("identity      = ").append(String.valueOf(AdbShell.identity())).append('\n');
                    sb.append("\n想真执行请加 --ez go true\n");
                    writeState(sb.toString());
                    return;
                }
                RootAdb.Fastboot r = RootAdb.rebootBootloader();
                sb.append("ok=").append(r.ok).append('\n');
                sb.append("used=").append(r.usedCommand).append('\n');
                sb.append(r.log);
                writeState(sb.toString());
            }
        }).start();
    }

    /** 无头自检：跑一遍临时 root 并把报告落盘。 */
    private void dumpTempRoot(final String tag) {
        // ADB 通道是真实 socket，必须离开主线程。
        new Thread(new Runnable() {
            public void run() {
                writeState("tag=" + tag + "\ninstalled=" + TempRoot.installed()
                        + "\ndisabled=" + TempRoot.disabled()
                        + "\nreadyFlag=" + TempRoot.readyFlag()
                        + "\nidentity.before=" + TempRoot.currentIdentity()
                        + "\n" + TempRoot.start(90000L));
            }
        }).start();
    }

    private void openJump(int tab) {
        Intent i = new Intent(this, JumpActivity.class);
        i.putExtra("tab", tab);
        startActivity(i);
    }

    private void jump(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast(getString(R.string.launch_failed) + t.getClass().getSimpleName());
        }
    }

    private void jumpPkg(String action, String pkg) {
        try {
            Intent i = new Intent(action,
                    android.net.Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast(getString(R.string.launch_failed) + t.getClass().getSimpleName());
        }
    }

    private void appDetails() {
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + Catalog.DJI_PKG));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开大疆 Fly 的详情页，可能未安装");
        }
    }

    private void about() {
        String s =
            "这个工具做三件事：\n\n" +
            "1. 强制屏幕方向。\n" +
            "   遥控器是横屏设备，但有些页面在竖屏下更好用。\n" +
            "   这里可以一键锁定竖屏 / 横屏 / 翻转 180°，\n" +
            "   也能随时交还给重力感应。\n\n" +
            "2. 一键跳转大疆和安卓里藏起来的页面。\n" +
            "   大疆自己的深链（djifly://、dji://、light://）能直接打开；\n" +
            "   未导出的内部页面只能通过 adb 打开，会在页面里给出命令。\n\n" +
            "3. 提供设备与系统信息。\n\n" +
            "屏幕方向的改动都可在应用内还原。\n" +
            "本工具不做音量、提示音、扬声器方面的任何修改。";
        new AlertDialog.Builder(this)
                .setTitle("关于 " + getString(R.string.app_name))
                .setMessage(s)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
