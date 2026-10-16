package com.rc2.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Installed-app browser / launcher.
 *
 * <p>Useful on a remote controller whose stock launcher hides most things, and
 * the fastest way to see exactly what DJI has installed and jump straight into
 * any of its activities' detail pages.
 */
public class AppsActivity extends Activity {

    private static final int MODE_DJI = 0;
    private static final int MODE_USER = 1;
    private static final int MODE_ALL = 2;
    private static final int MAX_ROWS = 220;

    private LinearLayout root;
    private int mode = MODE_DJI;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));
        render();
        ScrollView sc = Ui.scroller(this, root);
        sc.setBackgroundColor(Ui.col(this, R.color.bg));
        setContentView(sc);
    }

    // ------------------------------------------------------------------ render

    private void render() {
        root.removeAllViews();

        TextView t = Ui.text(this, "应用管理", 22f, Ui.col(this, R.color.text));
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 2));
        root.addView(t);
        root.addView(Ui.hint(this, "点一下直接启动；长按可以看详情、强停、卸载"));
        root.addView(Ui.divider(this));

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), 0, Ui.dp(this, 6));
        bar.addView(modeBtn("大疆系", MODE_DJI));
        bar.addView(modeBtn("用户应用", MODE_USER));
        bar.addView(modeBtn("全部", MODE_ALL));
        root.addView(bar);

        List<ApplicationInfo> apps = load();
        root.addView(Ui.hint(this, "共 " + apps.size() + " 个"
                + (apps.size() > MAX_ROWS ? "，只显示前 " + MAX_ROWS + " 个" : "")));
        root.addView(Ui.divider(this));

        PackageManager pm = getPackageManager();
        int shown = 0;
        for (int i = 0; i < apps.size() && shown < MAX_ROWS; i++, shown++) {
            ApplicationInfo ai = apps.get(i);
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Throwable t2) {
                label = ai.packageName;
            }
            final String pkg = ai.packageName;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String desc = pkg + (sys ? "　· 系统" : "")
                    + (isDji(pkg) ? "　· 大疆" : "");

            root.addView(Ui.card(this, label, desc, new View.OnClickListener() {
                public void onClick(View v) { launch(pkg); }
            }));
        }

        if (shown == 0) {
            root.addView(Ui.hint(this, "这个分类下没有应用。"));
        }

        TextView foot = Ui.hint(this,
                "\n提示：遥控器桌面太简陋时，可以在这里把常用应用直接拉起来。\n"
              + "「全部」会列出两百多个系统组件，翻起来会慢。\n");
        foot.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 28));
        root.addView(foot);
    }

    private android.widget.Button modeBtn(String label, final int m) {
        return Ui.button(this, (mode == m ? "● " : "") + label, new View.OnClickListener() {
            public void onClick(View v) {
                mode = m;
                render();
            }
        });
    }

    // ------------------------------------------------------------------- data

    private boolean isDji(String pkg) {
        String p = pkg.toLowerCase();
        return p.startsWith("dji.") || p.contains(".dji") || p.startsWith("com.dji")
                || p.contains("dji");
    }

    private List<ApplicationInfo> load() {
        List<ApplicationInfo> out = new ArrayList<ApplicationInfo>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> all;
        try {
            all = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return out;
        }
        if (all == null) return out;

        for (int i = 0; i < all.size(); i++) {
            ApplicationInfo ai = all.get(i);
            if (ai == null || ai.packageName == null) continue;
            if (ai.packageName.equals(getPackageName())) continue;   // not ourselves

            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean dji = isDji(ai.packageName);

            if (mode == MODE_DJI && !dji) continue;
            if (mode == MODE_USER && sys) continue;
            out.add(ai);
        }

        Collections.sort(out, new Comparator<ApplicationInfo>() {
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                boolean da = isDji(a.packageName);
                boolean db = isDji(b.packageName);
                if (da != db) return da ? -1 : 1;
                boolean sa = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean sb = (b.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (sa != sb) return sa ? 1 : -1;
                return labelOf(a).compareToIgnoreCase(labelOf(b));
            }
        });
        return out;
    }

    /** Never throws: a package can vanish between listing and labelling. */
    private String labelOf(ApplicationInfo ai) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(ai));
        } catch (Throwable t) {
            return ai.packageName == null ? "" : ai.packageName;
        }
    }

    // ---------------------------------------------------------------- actions

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                showMenu(pkg);
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("启动失败：" + t.getClass().getSimpleName());
            showMenu(pkg);
        }
    }

    private void showMenu(final String pkg) {
        final String[] items = {
                "查看应用详情 / 权限",
                "强行停止（后台）",
                "卸载",
                "复制包名",
        };
        new AlertDialog.Builder(this)
                .setTitle(pkg)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) details(pkg);
                        else if (which == 1) forceStop(pkg);
                        else if (which == 2) uninstall(pkg);
                        else copy(pkg);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void details(String pkg) {
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开详情页");
        }
    }

    private void forceStop(String pkg) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.killBackgroundProcesses(pkg);
            toast("已请求结束后台进程：" + pkg);
        } catch (Throwable t) {
            toast("系统不允许结束该应用");
        }
    }

    private void uninstall(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开卸载页；系统应用确实卸不掉");
        }
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("pkg", s));
            toast("已复制：" + s);
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
