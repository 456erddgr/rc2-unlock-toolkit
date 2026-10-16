package com.rc2.toolbox;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Root console.
 *
 * <p>Ports the one genuinely useful page of DJI's own engineer tool
 * ({@code com.dpad.fuli.ShellCommandActivity}) — but that tool relied on
 * {@code Runtime.exec("adb shell su")}, which cannot work here because the
 * remote controller has neither an {@code adb} nor a {@code su} binary.
 * This page goes through {@link AdbShell} instead, so it actually runs.
 */
public class ShellActivity extends Activity {

    private static final String[][] QUICK = {
        { "身份", "id" },
        { "系统版本", "getprop ro.build.display.id" },
        { "电池", "dumpsys battery" },
        { "存储", "df -h /data /sdcard 2>/dev/null" },
        { "内存/进程", "top -b -n 1 | head -20" },
        { "温度", "dumpsys thermalservice | head -40" },
        { "音量流", "dumpsys audio | sed -n '/Stream volumes/,/^$/p' | head -40" },
        { "最近日志", "logcat -d -t 120" },
        { "无线状态", "dumpsys wifi | head -30" },
        { "已装应用数", "pm list packages | wc -l" },
    };

    private TextView status;
    private EditText input;
    private TextView output;
    private ScrollView outScroll;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(build());
        probe();
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        status = Ui.text(this, "正在检测 root 通道…", 12f, Ui.col(this, R.color.text_dim));
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 4));
        root.addView(status);

        root.addView(Ui.hint(this,
                "命令以 uid=0 在本机执行。设备没有 su / adb 二进制，走的是 127.0.0.1:5555 的 root shell。"));

        input = new EditText(this);
        input.setHint("输入 shell 命令");
        input.setTextSize(14f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setTextColor(Ui.col(this, R.color.text));
        input.setHintTextColor(Ui.col(this, R.color.text_dim));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        input.setLayoutParams(ilp);
        root.addView(input);

        LinearLayout runRow = Ui.row(this);
        Button runBtn = Ui.button(this, "执行", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exec(input.getText().toString());
            }
        });
        Button clearBtn = Ui.button(this, "清屏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log.setLength(0);
                render();
            }
        });
        runRow.addView(runBtn);
        runRow.addView(clearBtn);
        root.addView(runRow);

        root.addView(Ui.header(this, "常用命令"));
        LinearLayout quickWrap = Ui.column(this);
        LinearLayout quickRow = Ui.row(this);
        quickRow.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        for (int i = 0; i < QUICK.length; i++) {
            final String cmd = QUICK[i][1];
            Button b = Ui.button(this, QUICK[i][0], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(cmd);
                    exec(cmd);
                }
            });
            b.setTextSize(12f);
            quickRow.addView(b);
            if (quickRow.getChildCount() == 3 || i == QUICK.length - 1) {
                quickWrap.addView(quickRow);
                quickRow = Ui.row(this);
                quickRow.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            }
        }
        root.addView(quickWrap);

        root.addView(Ui.header(this, "输出"));
        output = Ui.text(this, "", 11f, Ui.col(this, R.color.text));
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setGravity(Gravity.START);
        output.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 12));

        outScroll = new ScrollView(this);
        outScroll.setBackgroundColor(Ui.col(this, R.color.card));
        outScroll.addView(output, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 260));
        slp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 20));
        outScroll.setLayoutParams(slp);
        root.addView(outScroll);

        return Ui.scroller(this, root);
    }

    private void probe() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int port = AdbShell.findPort();
                final String id = port > 0 ? AdbShell.identity() : "";
                final boolean root = id.indexOf("uid=0") >= 0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (port == 0) {
                            status.setText("root 通道不可用：" + AdbShell.lastError);
                            status.setTextColor(Ui.col(ShellActivity.this, R.color.bad));
                        } else if (root) {
                            status.setText("root 通道正常  ·  127.0.0.1:" + port + "  ·  " + id);
                            status.setTextColor(Ui.col(ShellActivity.this, R.color.ok));
                        } else {
                            status.setText("端口 " + port + " 可连，但拿到的不是 root：" + id);
                            status.setTextColor(Ui.col(ShellActivity.this, R.color.warn));
                        }
                    }
                });
            }
        }).start();
    }

    private void exec(final String cmd) {
        final String c = cmd == null ? "" : cmd.trim();
        if (c.length() == 0) {
            return;
        }
        append("$ " + c);
        append("…执行中");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AdbShell.Result r = AdbShell.run(c, 30000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Replace the placeholder line with the real result.
                        int idx = log.lastIndexOf("…执行中");
                        if (idx >= 0) {
                            log.setLength(idx);
                        }
                        String body = r.text();
                        if (body == null || body.length() == 0) {
                            body = "(无输出)";
                        }
                        append(body);
                        append("[exit " + r.code + "]");
                    }
                });
            }
        }).start();
    }

    private void append(String s) {
        log.append(s).append('\n');
        render();
    }

    private void render() {
        output.setText(log.toString());
        outScroll.post(new Runnable() {
            @Override
            public void run() {
                outScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
