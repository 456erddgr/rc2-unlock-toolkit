package com.rc2.toolbox;

/**
 * 系统属性改写（走真 ksud 3.3.0 的 {@code resetprop}）。
 *
 * <p>这是本机上唯一一个**已实测可用**的 KernelSU 原生能力：ksud 内嵌的
 * resetprop 是 Magisk 兼容的属性工具，它直接 mmap {@code /dev/__properties__}
 * 的属性区，因此：
 * <ul>
 *   <li>能改 {@code ro.*} 只读属性（{@code setprop} 做不到）；</li>
 *   <li>即时生效，不用重启；</li>
 *   <li>不依赖 KernelSU 内核模块 —— 本机内核没有 KSU，它照样工作。</li>
 * </ul>
 *
 * <p>实测（Android 11 / SDK 30）：
 * {@code resetprop ro.build.version.sdk} → {@code 30}；
 * 写入 {@code rc2.test.prop=hello} 后 {@code getprop} 立即读到。
 *
 * <p>注意：属性改写只在**当前这次开机**内有效（除非加 {@code -p} 写持久区），
 * 重启后回到原值。这是 resetprop 的固有行为，不是缺陷。
 */
public final class SysProp {

    /** 真 ksud 路径。KsuActivity 部署它会把它放这里。 */
    public static final String KSUD = "/data/adb/ksu/bin/ksud";

    private SysProp() {}

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 读一条属性。
     *
     * @return 属性值；不存在时返回 null。
     */
    public static String get(String name) {
        AdbShell.Result r = AdbShell.run(
                KSUD + " resetprop " + q(name) + " 2>&1", 15000);
        String t = r.text().trim();
        if (r.code != 0 || t.length() == 0
                || t.indexOf("not found") >= 0
                || t.indexOf("resetprop:") >= 0) {
            return null;
        }
        return t;
    }

    /**
     * 写一条属性（只影响本次开机）。
     *
     * @param persistent true 时加 {@code -p} 同时写持久属性区，重启后仍在。
     * @return 空串表示成功，否则是错误摘要。
     */
    public static String set(String name, String value, boolean persistent) {
        AdbShell.Result r = AdbShell.run(
                KSUD + " resetprop " + (persistent ? "-p " : "")
              + q(name) + " " + q(value) + " 2>&1", 20000);
        if (r.code != 0) {
            return oneline(r.text());
        }
        // 回读校验：resetprop 成功不代表真写进去了。
        String back = get(name);
        if (back == null) {
            return "写入后回读不到";
        }
        if (!back.equals(value)) {
            return "写入后回读不一致：" + back;
        }
        return "";
    }

    /** 删掉一条属性。 */
    public static String del(String name) {
        AdbShell.Result r = AdbShell.run(
                KSUD + " resetprop -d " + q(name) + " 2>&1", 15000);
        return r.code == 0 ? "" : oneline(r.text());
    }

    /** 列出全部属性（约 1300 条）。 */
    public static String all() {
        AdbShell.Result r = AdbShell.run(KSUD + " resetprop 2>/dev/null", 20000);
        return r.text();
    }

    /**
     * 抓一批属性值，用 {@code |} 分隔返回，供界面显示。
     *
     * <p>这样比逐条调用少开 10 个 root shell，界面刷新快得多。
     */
    public static String[] snapshot(String... names) {
        StringBuilder cmd = new StringBuilder();
        for (String n : names) {
            cmd.append(KSUD).append(" resetprop ").append(q(n))
               .append(" 2>/dev/null; echo '@';");
        }
        AdbShell.Result r = AdbShell.run(cmd.toString(), 25000);
        String[] parts = r.text().split("@");
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String v = i < parts.length ? parts[i].trim() : "";
            out[i] = (v.length() == 0 || v.indexOf("resetprop:") >= 0) ? null : v;
        }
        return out;
    }

    /** 常见「值得看/值得改」的属性，界面上直接列出来。 */
    public static final String[][] PRESETS = {
        // 属性名, 标题, 说明
        { "ro.debuggable", "ro.debuggable", "调试模式开关，1=开启" },
        { "ro.adb.secure", "ro.adb.secure", "0 = 免密钥 adb，1 = 需要授权" },
        { "ro.secure", "ro.secure", "0 = adbd 以 root 跑" },
        { "service.adb.root", "service.adb.root", "1 = 让 adbd 用 root 身份" },
        { "ro.build.version.sdk", "SDK 版本", "系统 API 级别（本机 30）" },
        { "ro.product.model", "产品型号", "改它可伪装机型" },
        { "ro.product.brand", "品牌", "改它可伪装品牌" },
        { "ro.build.fingerprint", "指纹", "整机身份串，改它骗过部分应用" },
        { "ro.sf.lcd_density", "屏幕 DPI", "改完需软重启生效" },
        { "persist.sys.timezone", "时区", "如 Asia/Shanghai" },
        { "persist.sys.locale", "语言", "如 zh-CN" },
        { "ro.boot.slot_suffix", "启动槽位", "本机 _b（A/B 双槽）" },
    };

    private static String oneline(String s) {
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
