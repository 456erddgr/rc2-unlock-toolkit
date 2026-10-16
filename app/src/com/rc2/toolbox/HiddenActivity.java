package com.rc2.toolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The hidden-page launcher.
 *
 * <p>{@code assets/hidden.tsv} is generated from DJI Fly's own manifest: every
 * activity that is <em>not</em> exported, i.e. the ones the normal launcher
 * cannot reach. {@link Target#launch} replays the failed start as uid 0, and a
 * root {@code am start} ignores the exported flag.
 */
public class HiddenActivity extends Activity {

    /**
     * DJI Fly ships under two package names depending on the build. v6 is the
     * one registered as the HOME activity on this remote, so it is tried first;
     * a page that only exists in v5 still resolves.
     */
    private static final String[] PKGS = { "dji.go.v6", "dji.go.v5" };

    /** {group, class, friendly, resolved package} */
    private final List<String[]> all = new ArrayList<String[]>();
    private LinearLayout list;
    private TextView count;
    private EditText search;
    private String filter = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(build());
        load();

        // Debug hook: `am start -n com.rc2.toolbox/.HiddenActivity --es auto <class>`
        // drives the real open() path, which is the only way to exercise the root
        // channel on this remote — DJI's build silently swallows injected input
        // events, so `input tap` cannot reach the cards.
        final String auto = getIntent() == null ? null : getIntent().getStringExtra("auto");
        if (auto != null && auto.length() > 0) {
            list.postDelayed(new Runnable() {
                public void run() {
                    open(new String[] { "自动", auto, auto, PKGS[0] });
                }
            }, 2500);
        }
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        root.addView(Ui.header(this, "隐藏页面"));
        root.addView(Ui.hint(this,
                "大疆自己在清单里注册、但没有导出的页面。普通方式打不开，"
              + "本工具用 root 通道以 uid=0 执行 am start 打开它们，"
              + "绕开 exported 限制。点一下打开，长按复制类名。"));

        count = Ui.text(this, "正在读取…", 12f, Ui.col(this, R.color.text_dim));
        count.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 6));
        root.addView(count);

        search = new EditText(this);
        search.setHint("筛选：输入关键词，比如 校准 / 回放 / 诊断");
        search.setTextSize(14f);
        search.setSingleLine(true);
        search.setTextColor(Ui.col(this, R.color.text));
        search.setHintTextColor(Ui.col(this, R.color.text_dim));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        search.setLayoutParams(slp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                filter = e.toString().trim().toLowerCase();
                render();
            }
        });
        root.addView(search);

        list = Ui.column(this);
        root.addView(list);

        ScrollView sc = Ui.scroller(this, root);
        return sc;
    }

    // ---------------------------------------------------------------- data

    private void load() {
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> rows = new ArrayList<String[]>();
                String err = null;
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            getAssets().open("hidden.tsv"), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.length() == 0 || line.charAt(0) == '#') {
                            continue;
                        }
                        String[] p = line.split("\t");
                        if (p.length < 3) {
                            continue;
                        }
                        rows.add(new String[] { p[0], p[1], p[2], "" });
                    }
                    r.close();
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            count.setText("读取内置清单失败：" + ferr);
                            return;
                        }
                        all.clear();
                        all.addAll(rows);
                        resolvePackages();
                        render();
                        if (all.isEmpty()) {
                            count.setText("内置清单是空的（assets/hidden.tsv 没打进包里）");
                        }
                    }
                });
            }
        }).start();
    }

    /** Marks which of the two DJI Fly packages actually declares each activity. */
    private void resolvePackages() {
        Set<String> present = new HashSet<String>();
        for (int i = 0; i < PKGS.length; i++) {
            try {
                PackageInfo pi = getPackageManager()
                        .getPackageInfo(PKGS[i], PackageManager.GET_ACTIVITIES);
                if (pi.activities != null) {
                    for (int k = 0; k < pi.activities.length; k++) {
                        present.add(PKGS[i] + "/" + pi.activities[k].name);
                    }
                }
            } catch (Throwable ignored) {
                // package not installed, or PackageManager refused
            }
        }
        for (int i = 0; i < all.size(); i++) {
            String[] row = all.get(i);
            for (int k = 0; k < PKGS.length; k++) {
                if (present.contains(PKGS[k] + "/" + row[1])) {
                    row[3] = PKGS[k];
                    break;
                }
            }
        }
    }

    // --------------------------------------------------------------- render

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        int shown = 0;
        String lastGroup = null;
        for (int i = 0; i < all.size(); i++) {
            final String[] row = all.get(i);
            String hay = (row[0] + " " + row[1] + " " + row[2]).toLowerCase();
            if (filter.length() > 0 && hay.indexOf(filter) < 0) {
                continue;
            }
            if (!row[0].equals(lastGroup)) {
                lastGroup = row[0];
                list.addView(Ui.header(this, row[0]));
            }
            list.addView(rowView(row));
            shown++;
        }
        int shadow = 0;
        int in6 = 0;
        int in5 = 0;
        for (int i = 0; i < all.size(); i++) {
            String pk = all.get(i)[3];
            if (pk.length() == 0) {
                shadow++;
            } else if (PKGS[0].equals(pk)) {
                in6++;
            } else {
                in5++;
            }
        }
        count.setText("共 " + all.size() + " 个隐藏页面 · 当前显示 " + shown
                + (filter.length() > 0 ? "（已筛选）" : "")
                + " · " + PKGS[0] + " " + in6 + " 个"
                + " · " + PKGS[1] + " " + in5 + " 个"
                + (shadow > 0 ? " · 本机没有 " + shadow + " 个" : ""));
    }

    private View rowView(final String[] row) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Ui.col(this, R.color.card));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 3), Ui.dp(this, 14), Ui.dp(this, 3));
        box.setLayoutParams(lp);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));

        TextView title = Ui.text(this, row[2], 14f, Ui.col(this, R.color.text));
        box.addView(title);

        TextView sub = Ui.text(this, row[1], 10f, Ui.col(this, R.color.text_dim));
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setPadding(0, Ui.dp(this, 3), 0, 0);
        box.addView(sub);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open(row);
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                copy(row[1]);
                return true;
            }
        });
        return box;
    }

    private void open(String[] row) {
        final String pkg = row[3].length() > 0 ? row[3] : PKGS[0];
        Target t = Target.component(row[2], row[1], pkg, row[1]);
        t.launchAsync(this, new Target.Then() {
            public void done(String err) {
                if (err == null) {
                    return;
                }
                toast("打不开：" + err);
            }
        });
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("class", s));
            toast("已复制类名");
        } catch (Throwable t) {
            toast("复制失败：" + t.getClass().getSimpleName());
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
