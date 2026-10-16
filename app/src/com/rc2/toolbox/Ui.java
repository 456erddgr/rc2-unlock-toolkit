package com.rc2.toolbox;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Programmatic-UI toolkit. Everything is built in code so the app needs almost
 * no layout XML — which matters here, because this project is assembled with a
 * hand-rolled aapt2/dx pipeline rather than Gradle.
 *
 * <p>The visual language: deep navy field, thin cold strokes, one electric cyan
 * accent, wide letter-spacing on labels, monospace for anything the device
 * actually reported.
 */
public final class Ui {

    public static final String APP = "RC2 NEXUS";
    public static final String TAGLINE = "遥控器控制台";
    public static final String AUTHOR = "软糖布丁";
    public static final String VERSION = "7.19";
    public static final String CODENAME = "NEXUS";

    /** Monospace, for values read off the device. */
    public static final android.graphics.Typeface MONO =
            android.graphics.Typeface.MONOSPACE;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int color(String hex) {
        return Color.parseColor(hex);
    }

    public static int col(Context ctx, int resId) {
        return ctx.getResources().getColor(resId);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    public static ScrollView scroller(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setBackgroundColor(col(c, R.color.bg));
        // Keep focus on the scroller itself so no child auto-focus drags the
        // view down to the middle of the page on first layout.
        s.setFocusable(true);
        s.setFocusableInTouchMode(true);
        s.addView(child, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    public static TextView text(Context c, String s, float sp, int col) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(col);
        t.setLineSpacing(dp(c, 4), 1f);
        return t;
    }

    /** Monospace variant, for numbers and raw device output. */
    public static TextView mono(Context c, String s, float sp, int colCol) {
        TextView t = text(c, s, sp, colCol);
        t.setTypeface(MONO);
        return t;
    }

    // ------------------------------------------------------------ drawables

    public static GradientDrawable round(Context c, int fill, float radius, int strokeCol) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        if (strokeCol != 0) {
            g.setStroke(Math.max(1, dp(c, 1)), strokeCol);
        }
        return g;
    }

    /** Vertical two-stop gradient with a stroke — the house card background. */
    public static GradientDrawable graded(Context c, int top, int bottom,
                                          float radius, int strokeCol) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[] { top, bottom });
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, radius));
        if (strokeCol != 0) {
            g.setStroke(Math.max(1, dp(c, 1)), strokeCol);
        }
        return g;
    }

    private static Drawable cardBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed },
                round(c, col(c, R.color.card_press), 18, 0));
        s.addState(new int[] { android.R.attr.state_focused },
                round(c, col(c, R.color.card_press), 18, 0));
        s.addState(new int[] {},
                graded(c, col(c, R.color.card), col(c, R.color.card_low), 18,
                        col(c, R.color.stroke)));
        return s;
    }

    private static Drawable buttonBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed },
                graded(c, col(c, R.color.accent), col(c, R.color.accent_dim), 22, 0));
        s.addState(new int[] {},
                graded(c, col(c, R.color.btn), col(c, R.color.btn_low), 22,
                        col(c, R.color.stroke)));
        return s;
    }

    // --------------------------------------------------------------- pieces

    /** Section header: accent bar, spaced label, then a hairline to the edge. */
    public static View header(Context c, String s) {
        LinearLayout r = row(c);
        r.setPadding(dp(c, 16), dp(c, 24), dp(c, 16), dp(c, 8));

        View bar = new View(c);
        bar.setBackground(graded(c, col(c, R.color.accent), col(c, R.color.accent2),
                2, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 3), dp(c, 15));
        blp.setMargins(0, 0, dp(c, 9), 0);
        bar.setLayoutParams(blp);
        r.addView(bar);

        TextView t = text(c, s, 13f, col(c, R.color.accent));
        t.setLetterSpacing(0.16f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        r.addView(t);

        View rule = new View(c);
        rule.setBackgroundColor(col(c, R.color.stroke));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, Math.max(1, dp(c, 0.8f)), 1f);
        rlp.setMargins(dp(c, 12), 0, 0, 0);
        rule.setLayoutParams(rlp);
        r.addView(rule);
        return r;
    }

    public static TextView hint(Context c, String s) {
        TextView t = text(c, s, 12f, col(c, R.color.text_dim));
        t.setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 10));
        return t;
    }

    /** Hero header: gradient slab, glow bar, title, tagline, status pills. */
    public static LinearLayout banner(Context c, String title, String sub) {
        LinearLayout box = column(c);
        box.setBackground(graded(c, col(c, R.color.hero), col(c, R.color.bg2), 20,
                col(c, R.color.stroke)));
        box.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        box.setElevation(dp(c, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 6));
        box.setLayoutParams(lp);

        // glow bar
        View glow = new View(c);
        glow.setBackground(graded(c, col(c, R.color.accent), col(c, R.color.accent2), 2, 0));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(c, 46), dp(c, 3));
        glp.setMargins(0, 0, 0, dp(c, 14));
        glow.setLayoutParams(glp);
        box.addView(glow);

        LinearLayout titleRow = row(c);
        TextView t = text(c, title, 26f, col(c, R.color.text));
        t.setLetterSpacing(0.06f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleRow.addView(t);

        TextView cn = text(c, "  " + TAGLINE, 11f, col(c, R.color.text_faint));
        cn.setLetterSpacing(0.08f);
        titleRow.addView(cn);
        box.addView(titleRow);

        if (sub != null && sub.length() > 0) {
            TextView d = text(c, sub, 11.5f, col(c, R.color.text_dim));
            d.setPadding(0, dp(c, 7), 0, 0);
            box.addView(d);
        }

        LinearLayout pills = row(c);
        pills.setPadding(0, dp(c, 13), 0, 0);
        pills.addView(pill(c, "v" + version(c) + " " + CODENAME, col(c, R.color.accent), col(c, R.color.accent_bg)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(dp(c, 7), 0, 0, 0);

        TextView a = pill(c, AUTHOR, col(c, R.color.text_dim), col(c, R.color.card_press));
        a.setLayoutParams(plp);
        pills.addView(a);

        TextView s = pill(c, "API " + Build.VERSION.SDK_INT, col(c, R.color.text_dim),
                col(c, R.color.card_press));
        s.setLayoutParams(plp);
        pills.addView(s);
        box.addView(pills);
        return box;
    }

    /**
     * KernelSU's own app-bar shape: upstream logo, upstream title, upstream
     * spacing. Ours only carries the version pill on the right.
     */
    public static LinearLayout ksuBar(Context c, String right) {
        LinearLayout bar = row(c);
        bar.setBackground(graded(c, col(c, R.color.hero), col(c, R.color.bg2), 0,
                col(c, R.color.stroke)));
        bar.setPadding(dp(c, 16), dp(c, 15), dp(c, 16), dp(c, 15));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 2));
        bar.setLayoutParams(lp);

        android.widget.ImageView mark = new android.widget.ImageView(c);
        mark.setImageResource(R.drawable.ic_ksu);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34));
        mlp.setMargins(0, 0, dp(c, 12), 0);
        mark.setLayoutParams(mlp);
        bar.addView(mark);

        LinearLayout stack = column(c);
        stack.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = text(c, "KernelSU", 20f, col(c, R.color.text));
        t.setLetterSpacing(0.02f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stack.addView(t);
        TextView s = text(c, "NEXUS 内置版", 10.5f, col(c, R.color.text_faint));
        s.setLetterSpacing(0.07f);
        stack.addView(s);
        bar.addView(stack);

        if (right != null && right.length() > 0) {
            bar.addView(pill(c, right, col(c, R.color.accent), col(c, R.color.accent_bg)));
        }
        return bar;
    }

    /**
     * The original KernelSU home header: the stock hexagon mark, the "KernelSU"
     * wordmark and the manager version, centered on a single card.
     */
    public static LinearLayout ksuHero(Context c, String version, String status, boolean ok) {
        LinearLayout box = column(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(graded(c, col(c, R.color.hero), col(c, R.color.bg2), 0,
                col(c, R.color.stroke)));
        box.setPadding(dp(c, 18), dp(c, 22), dp(c, 18), dp(c, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 4));
        box.setLayoutParams(lp);

        android.widget.ImageView mark = new android.widget.ImageView(c);
        mark.setImageResource(R.drawable.ic_ksu);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(c, 92), dp(c, 92));
        mark.setLayoutParams(mlp);
        box.addView(mark);

        TextView t = text(c, "KernelSU", 27f, col(c, R.color.text));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.01f);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, dp(c, 12), 0, dp(c, 3));
        t.setLayoutParams(tlp);
        box.addView(t);

        TextView v = text(c, version, 11.5f, col(c, R.color.text_faint));
        v.setGravity(Gravity.CENTER);
        v.setLetterSpacing(0.04f);
        box.addView(v);

        LinearLayout wrap = row(c);
        wrap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, dp(c, 12), 0, 0);
        wrap.setLayoutParams(wlp);
        wrap.addView(pill(c, status, ok ? col(c, R.color.ok) : col(c, R.color.warn),
                ok ? col(c, R.color.ok_bg) : col(c, R.color.warn_bg)));
        box.addView(wrap);
        return box;
    }

    /** A small rounded label, e.g. a status pill. */
    public static TextView pill(Context c, String s, int fg, int bg) {
        TextView t = text(c, s, 10.5f, fg);
        t.setLetterSpacing(0.05f);
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setBackground(round(c, bg, 10, 0));
        return t;
    }

    /** Big number + caption, for status grids. */
    public static LinearLayout tile(Context c, String value, String label, int colCol) {
        LinearLayout box = column(c);
        box.setBackground(graded(c, col(c, R.color.card), col(c, R.color.card_low), 16,
                col(c, R.color.stroke)));
        box.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
        box.setLayoutParams(lp);

        TextView v = mono(c, value, 17f, colCol);
        box.addView(v);
        TextView l = text(c, label, 10f, col(c, R.color.text_faint));
        l.setLetterSpacing(0.08f);
        l.setPadding(0, dp(c, 4), 0, 0);
        box.addView(l);
        return box;
    }

    // ---------------------------------------------------------------- cards

    public static LinearLayout card(Context c, String title, String desc, View.OnClickListener click) {
        return card(c, title, desc, 0, click);
    }

    /** Card with an optional accent stripe on the left. {@code stripe == 0} = neutral. */
    public static LinearLayout card(Context c, String title, String desc, int stripe,
                                    View.OnClickListener click) {
        LinearLayout outer = row(c);
        outer.setBackground(cardBg(c));
        outer.setGravity(Gravity.CENTER_VERTICAL);
        if (stripe == 0) {
            outer.setPadding(dp(c, 17), dp(c, 15), dp(c, 13), dp(c, 15));
        } else {
            outer.setPadding(dp(c, 13), dp(c, 15), dp(c, 13), dp(c, 15));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 5), dp(c, 14), dp(c, 5));
        outer.setLayoutParams(lp);

        if (stripe != 0) {
            View bar = new View(c);
            bar.setBackground(graded(c, stripe, stripe, 2, 0));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 3),
                    ViewGroup.LayoutParams.MATCH_PARENT);
            blp.setMargins(0, dp(c, 2), dp(c, 13), dp(c, 2));
            bar.setLayoutParams(blp);
            outer.addView(bar);
        }

        LinearLayout box = column(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.setLayoutParams(blp);

        TextView t = text(c, title, 15f, col(c, R.color.text));
        box.addView(t);

        if (desc != null && desc.length() > 0) {
            TextView d = text(c, desc, 11f, col(c, R.color.text_dim));
            d.setPadding(0, dp(c, 5), 0, 0);
            box.addView(d);
        }
        outer.addView(box);

        if (click != null) {
            TextView arrow = text(c, "›", 22f, col(c, R.color.text_faint));
            arrow.setPadding(dp(c, 10), 0, dp(c, 2), 0);
            arrow.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            outer.addView(arrow);
            outer.setClickable(true);
            outer.setFocusable(true);
            outer.setOnClickListener(click);
        }
        return outer;
    }

    public static Button button(Context c, String label, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        b.setTextColor(col(c, R.color.text));
        b.setBackground(buttonBg(c));
        b.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 2), dp(c, 6), dp(c, 8), dp(c, 6));
        b.setLayoutParams(lp);
        return b;
    }

    public static View spacer(Context c, int height) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, height)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(col(c, R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.8f)));
        lp.setMargins(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * KernelSU-style bottom navigation bar. Titles are laid out evenly; the
     * active cell gets an accent pill plus an underline, exactly like the
     * upstream manager's bottom bar.
     */
    public static View tabBar(Context c, String[] titles, int active, Runnable[] actions) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(col(c, R.color.bg2));

        View line = new View(c);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.8f))));
        line.setBackgroundColor(col(c, R.color.divider));
        wrap.addView(line);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));

        for (int i = 0; i < titles.length; i++) {
            final int idx = i;
            boolean on = i == active;

            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(c, 2), dp(c, 9), dp(c, 2), dp(c, 9));
            cell.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cell.setBackground(round(c, on ? col(c, R.color.accent_bg) : 0x00000000,
                    dp(c, 14), 0));

            TextView t = text(c, titles[i], 11.5f,
                    col(c, on ? R.color.accent : R.color.text_dim));
            t.setGravity(Gravity.CENTER);
            t.setLetterSpacing(0.03f);
            if (on) t.setTypeface(t.getTypeface(), Typeface.BOLD);
            cell.addView(t);

            if (on) {
                View bar = new View(c);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        dp(c, 20), Math.max(1, dp(c, 2.2f)));
                blp.topMargin = dp(c, 5);
                bar.setLayoutParams(blp);
                bar.setBackground(round(c, col(c, R.color.accent), dp(c, 2), 0));
                cell.addView(bar);
            }

            final Runnable action = actions != null && idx < actions.length ? actions[idx] : null;
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (action != null) action.run();
                }
            });
            row.addView(cell);
        }
        wrap.addView(row);
        return wrap;
    }

    /** Footer shown at the bottom of every screen. */
    public static TextView footer(Context c) {
        TextView t = text(c, APP + " " + version(c) + "　·　" + AUTHOR
                + "\n为 DJI RC 2 制作　·　" + sdkLine(), 10f, col(c, R.color.text_faint));
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(c, 18), dp(c, 26), dp(c, 18), dp(c, 26));
        return t;
    }

    /** Live versionName from the package, so the UI can never drift from the build. */
    public static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return VERSION;
        }
    }

    public static String sdkLine() {
        return "Android " + Build.VERSION.RELEASE + "  (API " + Build.VERSION.SDK_INT + ")";
    }
}












