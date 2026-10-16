package com.rc2.toolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Device / version report, plus a copyable volume snapshot. */
public class InfoActivity extends Activity {

    private String report = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        TextView t = Ui.text(this, getString(R.string.title_info), 22f, Ui.col(this, R.color.text));
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 8));
        root.addView(t);

        root.addView(Ui.button(this, getString(R.string.info_copy), new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("rc2-info", report));
                toast(getString(R.string.info_copied));
            }
        }));

        StringBuilder sb = new StringBuilder();
        sb.append("== 遥控器 ==").append('\n');
        sb.append("型号      ").append(Build.MODEL).append('\n');
        sb.append("厂商      ").append(Build.MANUFACTURER).append('\n');
        sb.append("设备名    ").append(Build.DEVICE).append('\n');
        sb.append("产品名    ").append(Build.PRODUCT).append('\n');
        sb.append("硬件      ").append(Build.HARDWARE).append('\n');
        sb.append("主板      ").append(Build.BOARD).append('\n');
        sb.append("指纹      ").append(Build.FINGERPRINT).append('\n');
        sb.append("CPU ABI   ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("CPU ABI2  ").append(join(Build.SUPPORTED_32_BIT_ABIS)).append('\n');
        sb.append('\n');

        sb.append("== 系统 ==").append('\n');
        sb.append("Android   ").append(Build.VERSION.RELEASE)
          .append("  (API ").append(Build.VERSION.SDK_INT).append(')').append('\n');
        sb.append("版本类型  ").append(Build.TYPE).append('\n');
        sb.append("增量版本  ").append(Build.VERSION.INCREMENTAL).append('\n');
        sb.append("安全补丁  ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        sb.append("内核      ").append(System.getProperty("os.version")).append('\n');
        sb.append("语言/地区 ").append(Locale.getDefault().toString()).append('\n');
        sb.append("时区      ").append(java.util.TimeZone.getDefault().getID()).append('\n');
        sb.append('\n');

        sb.append(djiInfo());
        sb.append('\n');

        sb.append("== 电源 ==").append('\n');
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                sb.append("电量      ").append(pct).append('%').append('\n');
                boolean charging = bm.isCharging();
                sb.append("充电      ").append(charging ? "是" : "否").append('\n');
            }
        } catch (Throwable ignored) { }

        report = sb.toString();

        TextView body = Ui.text(this, report, 13f, Ui.col(this, R.color.text));
        body.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 24));
        body.setTextIsSelectable(true);
        root.addView(body);

        ScrollView sc = Ui.scroller(this, root);
        sc.setBackgroundColor(Ui.col(this, R.color.bg));
        setContentView(sc);
    }

    private String djiInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("== 大疆应用 ==").append('\n');
        String[] pkgs = { Catalog.DJI_PKG, Catalog.DJI_FLY_PKG };
        PackageManager pm = getPackageManager();
        for (String p : pkgs) {
            try {
                PackageInfo pi = pm.getPackageInfo(p, 0);
                ApplicationInfo ai = pi.applicationInfo;
                sb.append(p).append('\n');
                sb.append("  版本名    ").append(pi.versionName).append('\n');
                if (Build.VERSION.SDK_INT >= 28) {
                    sb.append("  版本号    ").append(pi.getLongVersionCode()).append('\n');
                } else {
                    sb.append("  版本号    ").append(pi.versionCode).append('\n');
                }
                sb.append("  安装时间  ").append(fmt(pi.firstInstallTime)).append('\n');
                sb.append("  更新时间  ").append(fmt(pi.lastUpdateTime)).append('\n');
                sb.append("  目标 SDK  ").append(ai != null ? ai.targetSdkVersion : -1).append('\n');
                sb.append("  数据目录  ").append(ai != null ? ai.dataDir : "?").append('\n');
            } catch (Throwable t) {
                sb.append(p).append("  未安装\n");
            }
        }
        return sb.toString();
    }

    private static String join(String[] a) {
        if (a == null || a.length == 0) return "?";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) b.append(", ");
            b.append(a[i]);
        }
        return b.toString();
    }

    private static String fmt(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new java.util.Date(ms));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Kept for symmetry with older drafts; unused but harmless. */
    static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }
}
