package com.rc2.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The jump screen: three tabs of launchable targets.
 *
 * Tab 0 — DJI official deep links / exported pages (these really open).
 * Tab 1 — Android system settings (these really open).
 * Tab 2 — adb-only internal pages. We do NOT pretend to launch them: we show the
 *         exact `am start` command and let the user copy it. Trying to start an
 *         unexported activity from a normal app throws SecurityException, and
 *         hiding that would just look like a broken button.
 */
public class JumpActivity extends Activity {

    private LinearLayout body;
    private LinearLayout tabs;
    private int tab = 0;

    private final String[] TAB_TITLES = { "大疆", "系统设置", "诊断(adb)" };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getIntent() != null) tab = getIntent().getIntExtra("tab", 0);
        if (tab < 0 || tab > 2) tab = 0;
        build();
    }

    private void build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        TextView t = Ui.text(this, getString(R.string.title_jump), 22f, Ui.col(this, R.color.text));
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 8));
        root.addView(t);

        tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 6));
        root.addView(tabs);

        body = Ui.column(this);
        root.addView(body);

        ScrollView sc = Ui.scroller(this, root);
        sc.setBackgroundColor(Ui.col(this, R.color.bg));
        setContentView(sc);

        fillTabs();
        fillBody();
    }

    private void fillTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int idx = i;
            TextView tv = Ui.text(this, TAB_TITLES[i], 14f,
                    Ui.col(this, i == tab ? R.color.text : R.color.text_dim));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Ui.col(this, i == tab ? R.color.card_press : R.color.card));
            bg.setCornerRadius(Ui.dp(this, 16));
            tv.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { tab = idx; fillTabs(); fillBody(); }
            });
            tabs.addView(tv);
        }
    }

    private void fillBody() {
        body.removeAllViews();

        if (tab == 0) {
            for (final Catalog.Item it : Catalog.djiOfficial()) {
                body.addView(entry(it, false));
            }
            body.addView(note("说明：大疆大部分内部页面没有导出（exported=false），" +
                    "普通应用只能打开上面这些带「打开」标记的。其余只能走 adb，" +
                    "见「诊断(adb)」页。"));
            return;
        }

        if (tab == 1) {
            for (final Catalog.Item it : Catalog.system()) {
                body.addView(entry(it, false));
            }
            return;
        }

        for (final Catalog.Item it : Catalog.djiOfficial()) {
            if (it.adb != null) body.addView(entry(it, true));
        }
        body.addView(note("用法：遥控器用数据线连电脑，确认已开 USB 调试，然后执行\n" +
                "adb shell am start -n 包名/类名\n\n" +
                "这些页面写进了大疆的清单但没有对第三方开放，App 无法直接打开，" +
                "这是安卓的安全机制，不是工具坏了。"));
    }

    private View entry(final Catalog.Item it, final boolean adbOnly) {
        LinearLayout box = Ui.card(this, it.title, it.desc, new View.OnClickListener() {
            public void onClick(View v) { onEntry(it, adbOnly); }
        });

        if (it.note != null) {
            TextView n = Ui.text(this, "· " + it.note, 12f, Ui.col(this, R.color.warn));
            n.setPadding(0, Ui.dp(this, 4), 0, 0);
            box.addView(n);
        }

        TextView act = Ui.text(this, adbOnly ? "复制 adb 命令" : "打开", 13f,
                Ui.col(this, adbOnly ? R.color.warn : R.color.accent));
        act.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(act);

        return box;
    }

    private void onEntry(Catalog.Item it, boolean adbOnly) {
        if (adbOnly) {
            if (it.adb != null) {
                copy(it.adb);
                toast("已复制命令");
            }
            return;
        }
        if (it.intent == null) {
            toast(getString(R.string.not_available));
            return;
        }
        try {
            startActivity(it.intent);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName();
            if (t.getMessage() != null) msg += "：" + t.getMessage();
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.launch_failed) + it.title)
                    .setMessage(msg + "\n\n" +
                            "常见原因：该页面没有对大疆之外的应用开放，" +
                            "或本机装的大疆版本不同。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private View note(String s) {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        TextView tv = Ui.text(this, s, 12f, Ui.col(this, R.color.text_dim));
        box.addView(tv);
        return box;
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("rc2", s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
