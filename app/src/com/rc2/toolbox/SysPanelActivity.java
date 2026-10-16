package com.rc2.toolbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 屏幕与旋转面板。
 *
 * <p>熄屏时间/自动旋转/亮度走 {@code Settings.System}；
 * 全局转屏走 root 的 {@code wm size} —— 这台机器的系统把旋转锁死在横屏，
 * 写 {@code user_rotation} 是没有反应的，只能反过来设屏幕尺寸。
 */
public class SysPanelActivity extends Activity {

    private LinearLayout root;
    private ScrollView sv;
    private TextView orientState;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));
        render();
        ScrollView sc = Ui.scroller(this, root);
        sc.setBackgroundColor(Ui.col(this, R.color.bg));
        setContentView(sc);
        sv = sc;
        // 无头自检：--es auto bottom 翻到底；--es auto scroll --ei y N 滚到指定位置。
        String auto = getIntent() == null ? null : getIntent().getStringExtra("auto");
        if ("bottom".equals(auto)) {
            sv.postDelayed(new Runnable() {
                public void run() { sv.fullScroll(View.FOCUS_DOWN); }
            }, 1500L);
        } else if ("scroll".equals(auto)) {
            final int y = getIntent().getIntExtra("y", 0);
            sv.postDelayed(new Runnable() {
                public void run() { sv.scrollTo(0, y); }
            }, 1500L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // ------------------------------------------------------------------ render

    private void render() {
        root.removeAllViews();

        TextView t = Ui.text(this, "屏幕与旋转", 22f, Ui.col(this, R.color.text));
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 2));
        root.addView(t);
        root.addView(Ui.hint(this, "改的是系统设置本身，退出应用后依然生效"));
        root.addView(Ui.divider(this));

        if (!SysSettings.canWrite(this)) {
            root.addView(Ui.header(this, "先授权"));
            root.addView(Ui.hint(this,
                    "安卓要求修改系统设置必须单独授权。点下面这张卡去打开开关；"
                  + "遥控器上不方便操作时，也可以直接在电脑上跑一行 adb 命令。"));
            root.addView(Ui.card(this, "点这里授权「修改系统设置」",
                    "系统会跳到授权页，找到 RC2 工具箱并把开关打开",
                    new View.OnClickListener() {
                        public void onClick(View v) { SysSettings.requestWrite(SysPanelActivity.this); }
                    }));
            final String cmd = SysSettings.adbGrant(this);
            root.addView(Ui.card(this, "或者用 adb 一行搞定",
                    cmd + "\n（点一下复制）",
                    new View.OnClickListener() {
                        public void onClick(View v) { copy(cmd); }
                    }));
            root.addView(Ui.divider(this));
        }

        // ------------------------------------------------------------ 震动
        root.addView(Ui.header(this, "触摸反馈"));

        final boolean hf = SysSettings.hapticFeedback(this);
        root.addView(Ui.card(this, hf ? "触摸震动：开着" : "触摸震动：已关闭",
                "震动反馈，和声音无关；嫌遥控器震可以关掉",
                new View.OnClickListener() {
                    public void onClick(View v) { flipHaptic(hf); }
                }));

        final boolean vib = SysSettings.vibrateOn(this);
        root.addView(Ui.card(this, vib ? "震动总开关：开着" : "震动总开关：已关闭",
                "关掉后所有震动都不会触发",
                new View.OnClickListener() {
                    public void onClick(View v) { flipVibrate(vib); }
                }));

        root.addView(Ui.divider(this));

        // ------------------------------------------------------------- 屏幕
        root.addView(Ui.header(this, "屏幕"));
        root.addView(Ui.card(this, "熄屏时间：" + SysSettings.describeTimeout(SysSettings.timeout(this)),
                "飞的时候屏幕老自动黑掉，就把它调长或者设成永不",
                null));

        LinearLayout tr = Ui.row(this);
        tr.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
        tr.addView(btn("15 秒", 15000));
        tr.addView(btn("30 秒", 30000));
        tr.addView(btn("1 分钟", 60000));
        tr.addView(btn("2 分钟", 120000));
        root.addView(tr);

        LinearLayout tr2 = Ui.row(this);
        tr2.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
        tr2.addView(btn("5 分钟", 300000));
        tr2.addView(btn("10 分钟", 600000));
        tr2.addView(btn("永不熄屏", SysSettings.TIMEOUT_NEVER));
        root.addView(tr2);

        final boolean rot = SysSettings.autoRotate(this);
        root.addView(Ui.card(this, rot ? "自动旋转：开着" : "自动旋转：已锁定",
                "遥控器被翻来翻去时，锁住可以避免界面跟着转",
                new View.OnClickListener() {
                    public void onClick(View v) { flipRotate(rot); }
                }));

        root.addView(Ui.card(this, "整块屏幕转方向（全局）",
                "这台机器的系统把旋转锁死在横屏，普通的「强制方向」写 user_rotation 是没反应的；"
              + "这里用 root 把屏幕尺寸反过来设一次，整块屏就真的立起来，所有 App 一起竖过来。",
                null));

        orientState = Ui.mono(this, "全局方向：读取中…", 12f, Ui.col(this, R.color.text_dim));
        orientState.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 8));
        root.addView(orientState);

        LinearLayout go = Ui.row(this);
        go.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
        go.addView(gbtn("全局竖屏", true));
        go.addView(gbtn("恢复全局横屏", false));
        root.addView(go);

        root.addView(Ui.card(this, "只转本应用（不需要 root）",
                "写 Activity 的请求方向。系统转不动整块屏时会把它缩成一条放中间，"
              + "所以真正要用竖屏请点上面的「全局竖屏」。现在是：" + SysSettings.describeRotation(this),
                null));

        LinearLayout rr = Ui.row(this);
        rr.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
        rr.addView(dir("仅本应用竖屏", 0));
        rr.addView(dir("仅本应用横屏", 90));
        root.addView(rr);

        root.addView(Ui.card(this, "交还给重力感应",
                "解除上面锁定的方向，跟着遥控器怎么拿就怎么显示",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        if (guard()) return;
                        SysSettings.releaseRotation(SysPanelActivity.this);
                        toast("已交还重力感应");
                        render();
                    }
                }));

        root.addView(Ui.card(this, "当前亮度：" + brightnessPct() + "%",
                "自动亮度：" + (SysSettings.brightnessMode(this)
                        == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        ? "开" : "关")
              + "　·　下面是直接写死的档位",
                null));

        LinearLayout br = Ui.row(this);
        br.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 6));
        br.addView(bright("10%", 26));
        br.addView(bright("25%", 64));
        br.addView(bright("50%", 128));
        br.addView(bright("75%", 191));
        br.addView(bright("100%", 255));
        root.addView(br);

        root.addView(Ui.card(this, "恢复自动亮度",
                "交还给系统的环境光调节",
                new View.OnClickListener() {
                    public void onClick(View v) {
                        SysSettings.setBrightnessAuto(SysPanelActivity.this);
                        toast("已恢复自动亮度");
                        render();
                    }
                }));

        TextView foot = Ui.hint(this,
                "\n这些改动影响整台遥控器，不只是本应用。\n"
              + "数值都可在这一页改回去；「全局竖屏」用「恢复全局横屏」还原。\n");
        foot.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 28));
        root.addView(foot);
        refreshGlobal();
    }

    // ---------------------------------------------------------------- actions

    private void flipHaptic(boolean cur) {
        if (guard()) return;
        SysSettings.setHapticFeedback(this, !cur);
        render();
    }

    private void flipVibrate(boolean cur) {
        if (guard()) return;
        SysSettings.setVibrateOn(this, !cur);
        render();
    }

    private void flipRotate(boolean cur) {
        if (guard()) return;
        SysSettings.setAutoRotate(this, !cur);
        render();
    }

    /** Returns true when the write is not allowed and the user was redirected. */
    private boolean guard() {
        if (SysSettings.canWrite(this)) return false;
        toast("还没有授权「修改系统设置」，先点上面的授权卡");
        SysSettings.requestWrite(this);
        return true;
    }

    /** 只转本应用：真正让 Activity 请求方向（写 Settings.System 在这台机器上没反应）。 */
    private android.widget.Button dir(String label, final int deg) {
        return Ui.button(this, label, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    setRequestedOrientation(deg == 90
                            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            : deg == 180
                              ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                              : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } catch (Throwable ignored) { }
                toast("本应用已请求：" + label + "（要整块屏转请点上面的全局竖屏）");
            }
        });
    }

    /** 全局转屏：竖屏 = 把未旋转的屏幕尺寸设成 1920x1080；横屏 = wm size reset。 */
    private android.widget.Button gbtn(String label, final boolean portrait) {
        return Ui.button(this, label, new View.OnClickListener() {
            public void onClick(View v) {
                globalOrientation(portrait);
            }
        });
    }

    private void globalOrientation(final boolean portrait) {
        toast(portrait ? "正在把整块屏转成竖屏…" : "正在恢复横屏…");
        new Thread(new Runnable() {
            public void run() {
                final AdbShell.Result r =
                        AdbShell.run(portrait ? "wm size 1920x1080" : "wm size reset", 8000);
                final String err = AdbShell.lastError;
                final boolean ok = r.ok() && AdbShell.isRoot();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ok) {
                            toast(portrait ? "已切到全局竖屏" : "已恢复全局横屏");
                        } else {
                            toast("没成功：" + (err.length() > 0 ? err
                                    : "先去 KSU 页点「临时 root（一键找回）」"));
                        }
                        refreshGlobal();
                    }
                });
            }
        }).start();
    }

    /** 读当前全局方向（wm size 的 Override size 就是全局竖屏的标记）。 */
    private void refreshGlobal() {
        final TextView tv = orientState;
        if (tv == null) return;
        new Thread(new Runnable() {
            public void run() {
                final AdbShell.Result r = AdbShell.run("wm size", 5000);
                final String out = r.text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (tv != orientState) return;
                        String t = out == null ? "" : out.trim().replace('\n', ' ');
                        if (t.indexOf("Override size: 1920x1080") >= 0) {
                            tv.setText("全局方向：竖屏（整块屏已转过来）");
                        } else if (t.indexOf("Override size:") >= 0) {
                            tv.setText("全局方向：已改 → " + t);
                        } else if (t.length() == 0) {
                            tv.setText("全局方向：读不到（没 root？）");
                        } else {
                            tv.setText("全局方向：横屏（原生）");
                        }
                    }
                });
            }
        }).start();
    }

    private android.widget.Button btn(String label, final int ms) {
        return Ui.button(this, label, new View.OnClickListener() {
            public void onClick(View v) {
                if (guard()) return;
                SysSettings.setTimeout(SysPanelActivity.this, ms);
                toast("熄屏时间已设为 " + SysSettings.describeTimeout(ms));
                render();
            }
        });
    }

    private android.widget.Button bright(String label, final int val) {
        return Ui.button(this, label, new View.OnClickListener() {
            public void onClick(View v) {
                if (guard()) return;
                SysSettings.setBrightness(SysPanelActivity.this, val);
                toast("亮度已设为 " + label);
                render();
            }
        });
    }

    private int brightnessPct() {
        int b = SysSettings.brightness(this);
        if (b < 0) b = 0;
        if (b > 255) b = 255;
        return b * 100 / 255;
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("adb", s));
            toast("已复制：" + s);
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
