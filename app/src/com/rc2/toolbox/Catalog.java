package com.rc2.toolbox;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Every entry the "hidden settings" screen can open.
 *
 * Two different worlds live here and we never blur them:
 *
 *  1. OFFICIAL DEEP LINKS — DJI registers these in its manifest (scheme `djifly`
 *     hosts `flight`/`linkapi`, scheme `dji` host `fly`, scheme `light`, scheme
 *     `sadf677ff1`). A normal app CAN fire these, because the owning activities
 *     are exported. These actually work.
 *
 *  2. ANDROID SYSTEM SETTINGS — exported by the platform, always openable.
 *
 * Anything that is NOT exported (most internal DJI screens) is listed in the
 * adb section of the same screen as a copyable `am start` command, clearly
 * marked as needing adb. We never pretend the app can open those.
 */
public final class Catalog {

    public static final String DJI_PKG = "dji.go.v6";
    public static final String DJI_FLY_PKG = "dji.go.v5";

    /** A menu entry. */
    public static final class Item {
        public final String title;
        public final String desc;
        public final Intent intent;      // null when adb-only
        public final String adb;         // null when directly launchable
        public final String note;        // extra hint / why it may fail

        Item(String title, String desc, Intent intent, String adb, String note) {
            this.title = title; this.desc = desc;
            this.intent = intent; this.adb = adb; this.note = note;
        }
    }

    private static Intent act(String action) {
        Intent i = new Intent(action);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent uri(String action, String data) {
        Intent i = new Intent(action, Uri.parse(data));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent detail(String action, String pkg) {
        Intent i = new Intent(action, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static Intent comp(String pkg, String cls) {
        Intent i = new Intent();
        i.setClassName(pkg, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // ================================================================ DJI official

    /** Deep links DJI itself builds and honours — verified from the APK. */
    public static List<Item> djiOfficial() {
        List<Item> l = new ArrayList<Item>();

        l.add(new Item("大疆飞行界面（FPV）",
                "官方深链 djifly://flight/fpv，直接进飞行画面",
                uri(Intent.ACTION_VIEW, "djifly://flight/fpv"),
                null,
                "需要已安装大疆 Fly（" + DJI_PKG + "）"));

        l.add(new Item("回放页 + 显示设置",
                "官方深链 djifly://flight/playback?showSettings=true —— 会带出回放里的设置面板",
                uri(Intent.ACTION_VIEW, "djifly://flight/playback?showSettings=true"),
                null, null));

        l.add(new Item("回放页",
                "官方深链 djifly://flight/playback",
                uri(Intent.ACTION_VIEW, "djifly://flight/playback"),
                null, null));

        l.add(new Item("退出登录",
                "官方深链 djifly://com.dji.account/logout（会退出大疆账号，慎点）",
                uri(Intent.ACTION_VIEW, "djifly://com.dji.account/logout"),
                null, "会清掉登录状态"));

        l.add(new Item("寻找航点 / Findspot",
                "官方深链 dji://fly/rn/FlyFindspotRn（React Native 页面）",
                uri(Intent.ACTION_VIEW, "dji://fly/rn/FlyFindspotRn"),
                null, null));

        l.add(new Item("相册回放主界面",
                "直接拉起 DJI 回放模块",
                comp("dji.go.v5", "com.uav.playback.main.PlaybackMainActivity"),
                null, "未导出，普通 App 可能被拒；失败请用下面的 adb 命令"));

        l.add(new Item("新机引导",
                "com.uav.flighttutorial 的引导页（已导出，可直接打开）",
                comp(DJI_PKG, "com.uav.flighttutorial.FlightTutorialWelcomeActivity"),
                null, null));

        l.add(new Item("设备新手引导",
                "简单遥控器版新手引导（已导出）",
                comp(DJI_PKG, "com.uav.flighttutorial.simplerc.DeviceNewbieGuideActivity"),
                null, null));

        l.add(new Item("遥控器信标教程",
                "Beacon 教程页（已导出）",
                comp(DJI_PKG, "com.uav.flighttutorial.BeaconTutorialActivity"),
                null, null));

        l.add(new Item("USB 配件模式",
                "DJIAoaActivity，USB 配件接入页（已导出）",
                comp(DJI_PKG, "com.dji.component.application.activity.DJIAoaActivity"),
                null, null));

        l.add(new Item("云台进阶校准（adb）",
                "设置 → 开发模式 → 云台校准，未导出，App 打不开",
                null,
                "adb shell am start -n " + DJI_PKG
                        + "/com.uav.accountcenterui.setting.developer.gimbalcali.AdvancedGimbalCaliActivity",
                "需要 root 或 adb shell"));

        l.add(new Item("开发模式扫码页（adb）",
                "设置里的开发模式入口，未导出",
                null,
                "adb shell am start -n " + DJI_PKG
                        + "/com.uav.accountcenterui.setting.developermode.DevelopAppCaptureBarCodeActivity",
                "需要 root 或 adb shell"));

        l.add(new Item("隐私埋点记录（adb）",
                "UT 埋点记录页，未导出",
                null,
                "adb shell am start -n " + DJI_PKG
                        + "/com.uav.accountcenterui.setting.privacy.utmissrecord.ui.UtmissRecordActivity",
                "需要 root 或 adb shell"));

        l.add(new Item("图传模块 APN 设置（adb）",
                "SettingDongleApnActivity，未导出",
                null,
                "adb shell am start -n " + DJI_PKG
                        + "/com.uav.service.dongledetail.apn.SettingDongleApnActivity",
                "需要 root 或 adb shell"));

        l.add(new Item("账号中心（adb）",
                "AccountCenterMainActivity，未导出",
                null,
                "adb shell am start -n " + DJI_PKG
                        + "/com.uav.accountcenterui.AccountCenterMainActivity",
                "需要 root 或 adb shell"));

        return l;
    }

    // ================================================================ system

    public static List<Item> system() {
        List<Item> l = new ArrayList<Item>();

        l.add(new Item("本应用详情", "看权限、清缓存、强停",
                detail(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "com.rc2.toolbox"), null, null));
        l.add(new Item("大疆 Fly 应用详情", "查看/修改大疆 Fly 的权限",
                detail(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, DJI_PKG), null, null));
        l.add(new Item("本应用通知设置", "单独关掉本应用的通知",
                detail(Settings.ACTION_APP_NOTIFICATION_SETTINGS, "com.rc2.toolbox"), null, null));
        l.add(new Item("通知使用权", "通知监听服务列表",
                act(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null, null));
        l.add(new Item("勿扰权限", "允许哪些应用打扰",
                act(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), null, null));
        l.add(new Item("免打扰模式设置", "自动规则",
                act(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS), null, null));

        l.add(new Item("声音 / 音量设置", "系统音量，含各音轨",
                act(Settings.ACTION_SOUND_SETTINGS), null, null));
        l.add(new Item("显示设置", "亮度、息屏、字体",
                act(Settings.ACTION_DISPLAY_SETTINGS), null, null));
        l.add(new Item("夜间模式", "护眼 / 夜间显示",
                act(Settings.ACTION_NIGHT_DISPLAY_SETTINGS), null, null));
        l.add(new Item("无障碍设置", "TalkBack 等",
                act(Settings.ACTION_ACCESSIBILITY_SETTINGS), null, null));
        l.add(new Item("字幕设置", "隐藏字幕",
                act(Settings.ACTION_CAPTIONING_SETTINGS), null, null));

        l.add(new Item("电池优化忽略名单", "把应用加进白名单，避免被杀",
                act(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), null, null));
        l.add(new Item("省电模式", "电池保护",
                act(Settings.ACTION_BATTERY_SAVER_SETTINGS), null, null));
        l.add(new Item("本应用后台流量限制", "允许后台用流量",
                detail(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, "com.rc2.toolbox"), null, null));
        l.add(new Item("数据用量", "流量统计",
                act(Settings.ACTION_DATA_USAGE_SETTINGS), null, null));

        l.add(new Item("无线网络（WiFi）", "WiFi 列表与高级项",
                act(Settings.ACTION_WIFI_SETTINGS), null, null));
        l.add(new Item("WiFi 高级 / IP 设置", "静态 IP 等",
                act(Settings.ACTION_WIFI_IP_SETTINGS), null, null));
        l.add(new Item("添加网络", "手动添加 WiFi",
                act(Settings.ACTION_WIFI_ADD_NETWORKS), null, null));
        l.add(new Item("移动网络 / 无线设置", "无线与网络总入口",
                act(Settings.ACTION_WIRELESS_SETTINGS), null, null));
        l.add(new Item("APN 设置（系统）", "接入点",
                act(Settings.ACTION_APN_SETTINGS), null, null));
        l.add(new Item("网络运营商", "选择运营商",
                act(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), null, null));
        l.add(new Item("VPN", "VPN 配置",
                act(Settings.ACTION_VPN_SETTINGS), null, null));
        l.add(new Item("蓝牙设置", "配对与已连设备",
                act(Settings.ACTION_BLUETOOTH_SETTINGS), null, null));
        l.add(new Item("飞行模式", "关掉所有无线",
                act(Settings.ACTION_AIRPLANE_MODE_SETTINGS), null, null));
        l.add(new Item("投屏 / Cast", "无线投屏",
                act(Settings.ACTION_CAST_SETTINGS), null, null));

        l.add(new Item("应用管理（全部）", "所有应用列表",
                act(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS), null, null));
        l.add(new Item("默认应用", "默认浏览器/桌面等",
                act(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), null, null));
        l.add(new Item("安装未知来源应用", "允许侧载",
                act(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), null, null));
        l.add(new Item("悬浮窗权限", "绘制在其他应用上层",
                detail(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "com.rc2.toolbox"), null, null));
        l.add(new Item("修改系统设置权限", "WRITE_SETTINGS",
                detail(Settings.ACTION_MANAGE_WRITE_SETTINGS, "com.rc2.toolbox"), null, null));
        l.add(new Item("所有文件访问权限", "Android 11 的 MANAGE_EXTERNAL_STORAGE",
                detail(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "com.rc2.toolbox"),
                null, "Android 11 起才存在"));
        l.add(new Item("使用情况访问", "应用使用统计权限",
                act(Settings.ACTION_USAGE_ACCESS_SETTINGS), null, null));

        l.add(new Item("存储 / 内部存储", "空间与清理",
                act(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), null, null));
        l.add(new Item("存储卷访问", "SD 卡等",
                act(Settings.ACTION_STORAGE_VOLUME_ACCESS_SETTINGS), null, null));
        l.add(new Item("存储卡设置", "外置卡",
                act(Settings.ACTION_MEMORY_CARD_SETTINGS), null, null));

        l.add(new Item("位置信息", "定位开关与来源",
                act(Settings.ACTION_LOCATION_SOURCE_SETTINGS), null, null));
        l.add(new Item("安全设置", "锁屏、加密",
                act(Settings.ACTION_SECURITY_SETTINGS), null, null));
        l.add(new Item("隐私设置", "权限总览",
                act(Settings.ACTION_PRIVACY_SETTINGS), null, null));
        l.add(new Item("日期和时间", "时区、自动时间",
                act(Settings.ACTION_DATE_SETTINGS), null, null));
        l.add(new Item("语言与输入法", "语言 / 区域",
                act(Settings.ACTION_LOCALE_SETTINGS), null, null));
        l.add(new Item("输入法设置", "键盘",
                act(Settings.ACTION_INPUT_METHOD_SETTINGS), null, null));
        l.add(new Item("输入法子类型", "具体布局",
                act(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS), null, null));
        l.add(new Item("用户词典", "自定义词",
                act(Settings.ACTION_USER_DICTIONARY_SETTINGS), null, null));
        l.add(new Item("开发者选项", "USB 调试、动画倍率等（已开启才能进）",
                act(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null,
                "若未开启会跳回设置首页"));
        l.add(new Item("设备信息", "型号、版本、内核",
                act(Settings.ACTION_DEVICE_INFO_SETTINGS), null, null));
        l.add(new Item("法规信息", "认证标志",
                act(Settings.ACTION_SHOW_REGULATORY_INFO), null, null));
        l.add(new Item("辅助功能快捷方式 / 手势", "系统手势",
                act(Settings.ACTION_HOME_SETTINGS), null, null));
        l.add(new Item("主设置页", "系统设置首页",
                act(Settings.ACTION_SETTINGS), null, null));

        return l;
    }

    // ================================================================ device

    public static List<Item> device() {
        List<Item> l = new ArrayList<Item>();

        l.add(new Item("大疆 Fly 写入参数通道",
                "确认大疆 Fly 是否为 dji.go.v6 / v5，用于跳转与权限",
                null, null, null));

        return l;
    }
}
