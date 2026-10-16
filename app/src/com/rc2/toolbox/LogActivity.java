package com.rc2.toolbox;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Live logcat through the root shell. The point is diagnosis: when a DJI screen
 * refuses to open, or an audio stream will not stay muted, the reason is almost
 * always in here.
 */
public class LogActivity extends Activity {

    private static final int LINES = 500;
    private static final long TICK = 3000L;

    private static final String[][] FILTERS = {
            { "全部", "" },
            { "错误", "*:E" },
            { "警告", "*:W" },
            { "DJI", "dji:* uav:* DJI:*" },
            { "我们的 App", "rc2toolbox:* AndroidRuntime:E" },
    };

    private TextView out;
    private TextView stat;
    private int filter = 0;
    private boolean auto = true;

    private final Handler ticker = new Handler();
    private final Runnable poll = new Runnable() {
        public void run() {
            refresh();
            if (auto) {
                ticker.postDelayed(this, TICK);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        auto = true;
        ticker.removeCallbacks(poll);
        ticker.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(poll);
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));
        root.addView(Ui.header(this, "系统日志（root）"));
        root.addView(Ui.hint(this,
                "直接读 logcat。想查「为什么打不开」就选「错误」，"
              + "想查大疆内部在干什么就选「DJI」。"));

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        for (int i = 0; i < FILTERS.length; i++) {
            final int idx = i;
            bar.addView(Ui.button(this, FILTERS[i][0], new View.OnClickListener() {
                public void onClick(View v) {
                    filter = idx;
                    refresh();
                }
            }));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(bar);
        root.addView(hs);

        LinearLayout bar2 = Ui.row(this);
        bar2.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        bar2.addView(Ui.button(this, "暂停 / 继续", new View.OnClickListener() {
            public void onClick(View v) {
                auto = !auto;
                ticker.removeCallbacks(poll);
                if (auto) {
                    ticker.post(poll);
                }
                toast(auto ? "自动刷新已开" : "自动刷新已暂停");
            }
        }));
        bar2.addView(Ui.button(this, "立即刷新", new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        }));
        bar2.addView(Ui.button(this, "清空", new View.OnClickListener() {
            public void onClick(View v) { clear(); }
        }));
        bar2.addView(Ui.button(this, "存到 /sdcard", new View.OnClickListener() {
            public void onClick(View v) { save(); }
        }));
        root.addView(bar2);

        stat = Ui.text(this, "读取中…", 11f, Ui.col(this, R.color.text_dim));
        stat.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 6));
        root.addView(stat);

        out = Ui.text(this, "", 10.5f, Ui.col(this, R.color.text));
        out.setTypeface(Typeface.MONOSPACE);
        out.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 20));
        root.addView(out);

        root.addView(Ui.footer(this));
        return Ui.scroller(this, root);
    }

    private void refresh() {
        final int f = filter;
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run(cmd(f), 15000);
                final String text = r.text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        out.setText(trim(text));
                        stat.setText(FILTERS[f][0] + " · " + countLines(text)
                                + " 行 · " + (auto ? "每 " + (TICK / 1000) + " 秒自动刷新"
                                : "已暂停"));
                    }
                });
            }
        }).start();
    }

    private static String cmd(int f) {
        String extra = FILTERS[f][1];
        return "logcat -d -v time -t " + LINES
                + (extra.length() > 0 ? " " + extra : "");
    }

    private void clear() {
        new Thread(new Runnable() {
            public void run() {
                AdbShell.run("logcat -c", 10000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        out.setText("");
                        toast("已清空");
                    }
                });
            }
        }).start();
    }

    private void save() {
        new Thread(new Runnable() {
            public void run() {
                String dest = "/sdcard/rc2-log.txt";
                AdbShell.Result r = AdbShell.run(
                        "logcat -d -v time > " + dest + " && wc -l " + dest, 30000);
                final String t = r.text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("已保存 " + dest + " · " + t.trim());
                    }
                });
            }
        }).start();
    }

    private static int countLines(String s) {
        if (s == null || s.length() == 0) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() == 0) {
            return "（没有匹配的日志）";
        }
        return t.length() > 60000 ? t.substring(t.length() - 60000) : t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
