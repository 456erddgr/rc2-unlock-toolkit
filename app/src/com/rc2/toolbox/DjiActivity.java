package com.rc2.toolbox;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 大疆工程工具总入口。
 *
 * RC 2 的固件里其实塞了一整套大疆自己用的工厂工具，只是出厂时被禁用了，
 * 所以桌面上看不到：
 *
 *   com.dpad.fuli        开发助手    —— 麦控、SDR、TypeC、FTM、结温、硬件 LOG、
 *                                     Shell 命令测试、大包升级、产测跳转……
 *   com.dpad.devicetest  产测 2.0    —— 整机测试、老化、充电老化、QA
 *   com.dpad.setup       设置向导
 *
 * 这个页面把三个包重新启用，并且把里面每一个 Activity 都列出来，点一下直接
 * 用 root 通道拉起来。不需要记类名，也不需要自己去翻 PackageManager。
 *
 * 启动走的是设备自带的 root adbd（uid=0），所以即使某个 Activity 没有导出
 * （android:exported="false"）也能拉起来——这是普通 App 做不到的。
 */
public class DjiActivity extends Activity {

    // ------------------------------------------------------------ 常量

    private static final String FULI = "com.dpad.fuli";
    private static final String DEV = "com.dpad.devicetest";
    private static final String SETUP = "com.dpad.setup";

    /**
     * 只有这两个可以放进自动启用列表；它们启用后就是桌面上两个普通图标。
     *
     * com.dpad.setup 绝对不在这个列表里：它是首次开机设置向导。实测只要
     * `pm enable --user 0 com.dpad.setup`，设备几秒后就会自己重启，重启完
     * 系统又重新把三个包全禁用 —— 也就是只能看它一眼，代价是一次重启。
     * 所以它单独做成一个带警告的按钮，让用户自己决定。
     */
    private static final String[] PKGS = { FULI, DEV };

    /** 开发助手：{显示名, 类名}。顺序按官方助手里那几排按钮来。 */
    private static final String[][] FULI_ITEMS = {
            { "开发助手主页",       "com.dpad.fuli.DevActivity" },
            { "协议测试",           "com.dpad.fuli.ProtocalActivity" },
            { "SHELL 命令测试",     "com.dpad.fuli.ShellCommandActivity" },
            { "结温",               "com.dpad.fuli.TemperatureActivity" },
            { "角色",               "com.dpad.fuli.RoleActivity" },
            { "AMT 工具",           "com.dpad.fuli.amt.AMTActivity" },
            { "MCU 升级",           "com.dpad.fuli.McuUpdateActivity" },
            { "MCU 摇杆",           "com.dpad.fuli.McuStickActivity" },
            { "MCU 摇杆异常",       "com.dpad.fuli.mcu.McuStickAbnormalActivity" },
            { "MCU 灯调试",         "com.dpad.fuli.mcu.McuLedDebugActivity" },
            { "SDR 开发工具",       "com.dpad.fuli.SdrDevActivity" },
            { "SDR 演示",           "com.dpad.fuli.SdrDemoActivity" },
            { "TypeC 功能",         "com.dpad.fuli.TypeCFunctionActivity" },
            { "FTM 模式",           "com.dpad.fuli.FtmModeActivity" },
            { "OTA 升级",           "com.dpad.fuli.RecoveryTestActivity" },
            { "大包升级",           "com.dpad.fuli.upgrade.UpgradeTestActivity" },
            { "系统升级",           "com.dpad.fuli.systemupdater.SystemUpdaterActivity" },
            { "多媒体 / MM",        "com.dpad.fuli.MmTestActivity" },
            { "外部设备助手",       "com.dpad.fuli.ExternalDeviceActivity" },
            { "存储",               "com.dpad.fuli.StorageActivity" },
            { "UFS",                "com.dpad.fuli.UfsActivity" },
            { "SoC",                "com.dpad.fuli.SocActivity" },
            { "其他信息",           "com.dpad.fuli.OtherTestActivity" },
            { "配置 WM232 / 多镜",  "com.dpad.fuli.MultiImageActivity" },
            { "网络时间 NTP",       "com.dpad.fuli.NtpActivity" },
            { "报告",               "com.dpad.fuli.ReportActivity" },
            { "Mycat",              "com.dpad.fuli.MycatActivity" },
            { "GoShare 教程页",     "com.dji.systemui.goshare.TutorActivity" },
    };

    /** 产测 2.0。 */
    private static final String[][] DEV_ITEMS = {
            { "产测主页",           "com.dpad.devicetest/MainActivity" },
            { "产测开发入口",       "com.dpad.devicetest/.DevActivity" },
            { "整机测试",           "com.dpad.devicetest/.TestsActivity" },
            { "QA 测试",            "com.dpad.devicetest/.QaActivity" },
            { "老化测试",           "com.dpad.devicetest/.aging.MainAgingActivity" },
            { "充电老化",           "com.dpad.devicetest/.ChargerAgingActivity" },
    };

    /** 设置向导。 */
    private static final String[][] SETUP_ITEMS = {
            { "设置向导",           "com.dpad.setup/.activity.SetupIndexActivity" },
    };

    private LinearLayout body;
    private TextView log;
    private TextView stateLine;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        enableAll();
    }

    // ---------------------------------------------------------------- 界面

    private void build() {
        body = Ui.column(this);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 32));

        body.addView(Ui.header(this, "大疆工程工具"));
        body.addView(Ui.hint(this,
                "这些是大疆自己的工厂/开发工具，固件里就有，只是出厂被禁用所以桌面看不到。"
                        + "进来会自动把「开发助手」重新打开（系统会时不时又把它禁掉），"
                        + "然后每一个页面都能直接拉起来。"));

        stateLine = Ui.mono(this, "正在启用…", 11f, Ui.col(this, R.color.text_dim));
        stateLine.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        body.addView(stateLine);

        body.addView(Ui.card(this, "把工程包放回桌面",
                "启用 com.dpad.fuli 与 com.dpad.devicetest，桌面就会多出「开发助手」"
                        + "和「产测 2.0」两个图标。系统每次开机会把它们重新禁用，"
                        + "图标也就跟着消失，回来点一下即可",
                0, new View.OnClickListener() {
                    public void onClick(View v) { enableAll(); }
                }));

        body.addView(Ui.divider(this));

        body.addView(Ui.header(this, "开发助手（" + FULI_ITEMS.length + " 个页面）"));
        body.addView(Ui.hint(this,
                "协议、MCU、SDR、TypeC、FTM、结温、硬件采样、存储、UFS、升级……都在这里。"));
        addItems(FULI, FULI_ITEMS);

        body.addView(Ui.divider(this));
        body.addView(Ui.header(this, "产测 2.0（" + DEV_ITEMS.length + " 个页面）"));
        body.addView(Ui.hint(this,
                "注意：点这里会先把产测包打开。整机测试 / 老化测试 / 充电老化是真的会跑流程，"
                        + "不是看一眼就完事，电池不满别乱点。"));
        addItems(DEV, DEV_ITEMS);

        body.addView(Ui.divider(this));
        body.addView(Ui.header(this, "设置向导（默认不启用）"));
        body.addView(Ui.hint(this,
                "com.dpad.setup 是首次开机向导，不是工具箱。实测只要启用它，设备几秒后就会"
                        + "自己重启（系统以为设备还没设置完），重启后三个包又会被系统重新禁用。"
                        + "所以默认不碰它。"));
        body.addView(Ui.card(this, "仍然启用设置向导（会导致设备重启一次）",
                "点下去设备会自己重启；重启后桌面会短暂出现设置向导图标，然后又被系统禁掉",
                Ui.col(this, R.color.bad), new View.OnClickListener() {
                    public void onClick(View v) { enableSetup(); }
                }));

        body.addView(Ui.divider(this));

        log = Ui.mono(this, "（输出会打在这里）", 11f, Ui.col(this, R.color.text_dim));
        log.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(log);

        body.addView(Ui.footer(this));

        ScrollView sc = Ui.scroller(this, body);
        setContentView(sc);
    }

    /**
     * 一排一项。用 Ui.card 而不用按钮，是因为类名太长，卡片能把类名当副标题
     * 显示出来，出问题时一眼能看出是哪个页面没起来。
     */
    private void addItems(String pkg, String[][] items) {
        for (int i = 0; i < items.length; i++) {
            final String label = items[i][0];
            String cls = items[i][1];
            // 列表里既有 "com.dpad.fuli.DevActivity" 这种全名，也有 ".DevActivity"
            // 这种简写，还有跨包的（GoShare 那项属于 com.dji.systemui）。
            // 所以：带 "/" 或本身已经是完整包名的直接用，其余补上当前包名。
            final String full = (cls.indexOf('/') > 0 || cls.startsWith("com."))
                    ? cls : pkg + cls;
            body.addView(Ui.card(this, label, full.replace(pkg, ""), 0,
                    new View.OnClickListener() {
                        public void onClick(View v) { launch(full); }
                    }));
        }
    }

    // ---------------------------------------------------------------- 动作

    private void enableAll() {
        stateLine.setText("正在启用…");
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < PKGS.length; i++) {
                    // 必须带 --user 0。不带的话这台机器只会回一句
                    // "new state: enabled"，包其实还是禁用状态（enabled=2），
                    // 于是里面每个 Activity 都报 "class does not exist" ——
                    // 用户看到的就是"所有功能都无法使用"。
                    AdbShell.run("pm enable --user 0 " + PKGS[i], 30000);
                }
                // 不信 pm 的返回值，直接拿禁用列表反查。
                String disabled = AdbShell.run("pm list packages -d", 30000).text();
                StringBuilder r = new StringBuilder();
                for (int i = 0; i < PKGS.length; i++) {
                    boolean off = disabled.contains("package:" + PKGS[i]);
                    r.append(PKGS[i]).append(off ? "  仍是禁用" : "  已启用").append('\n');
                }
                final String t = r.toString().trim();
                ui.post(new Runnable() {
                    public void run() { stateLine.setText(t); }
                });
                append("=== 启用结果 ===\n" + t);
            }
        }).start();
    }

    /** 单独放行设置向导。实测会触发一次重启，所以单独一个按钮。 */
    private void enableSetup() {
        append("→ 启用 com.dpad.setup（预计设备几秒后会重启）");
        new Thread(new Runnable() {
            public void run() {
                String out = AdbShell.run(
                        "pm enable --user 0 " + SETUP + " 2>&1", 30000).text().trim();
                append(out);
                append("设备重启起来之后，回来点一次「把工程包放回桌面」。");
            }
        }).start();
    }

    private void launch(final String component) {
        append("→ " + component);
        new Thread(new Runnable() {
            public void run() {
                // 这台机器会时不时把这三个工厂包重新禁用，一旦禁用，
                // am start 只会回一句 "Activity class does not exist"。
                // 所以每次点都先补一次启用。
                int slash = component.indexOf('/');
                String pkg = slash > 0 ? component.substring(0, slash) : component;
                AdbShell.run("pm enable --user 0 " + pkg, 30000);
                // 直接用 root 的 am，绕过 exported 限制。
                String out = AdbShell.run(
                        "am start -n " + component + " 2>&1 | tail -3", 30000).text().trim();
                append(out);
                if (out.contains("does not exist")) {
                    append("（打不开：这个组件不在包里。最上面点一下「重新启用三个工程包」再试）");
                } else if (out.contains("Error")) {
                    append("（系统拒绝了这次启动，通常是这个页面需要连上飞机才肯开）");
                }
            }
        }).start();
    }

    private void append(final String s) {
        ui.post(new Runnable() {
            public void run() {
                if (log != null) log.setText(log.getText() + "\n" + s);
            }
        });
    }
}
