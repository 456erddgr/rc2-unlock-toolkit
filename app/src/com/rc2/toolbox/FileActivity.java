package com.rc2.toolbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Root file manager. Everything goes through the loopback root shell, so the
 * whole filesystem is reachable — including /data and /system, which a normal
 * app cannot even list.
 */
public class FileActivity extends Activity {

    /** Text files larger than this are previewed, not loaded whole. */
    private static final long MAX_PREVIEW = 128 * 1024L;

    private static final String[] BOOKMARKS = {
            "/storage/emulated/0", "/data/adb", "/data/local/tmp", "/system", "/vendor", "/"
    };

    private String cwd = "/storage/emulated/0";
    private LinearLayout list;
    private TextView pathView;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        navigate(cwd);
    }

    private View build() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        root.addView(Ui.header(this, "文件管理器（root）"));
        root.addView(Ui.hint(this,
                "以 uid=0 读写整个文件系统。点文件夹进入，点文件查看/编辑/删除。"
              + "改动系统分区前请先备份。"));

        pathView = Ui.text(this, "/storage/emulated/0", 12f, Ui.col(this, R.color.accent));
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 8));
        root.addView(pathView);

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 6));
        bar.addView(Ui.button(this, "↑ 上级", new View.OnClickListener() {
            public void onClick(View v) { up(); }
        }));
        bar.addView(Ui.button(this, "刷新", new View.OnClickListener() {
            public void onClick(View v) { navigate(cwd); }
        }));
        bar.addView(Ui.button(this, "路径…", new View.OnClickListener() {
            public void onClick(View v) { askPath(); }
        }));
        root.addView(bar);

        LinearLayout marks = Ui.row(this);
        marks.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        for (int i = 0; i < BOOKMARKS.length; i++) {
            final String p = BOOKMARKS[i];
            marks.addView(Ui.button(this, p, new View.OnClickListener() {
                public void onClick(View v) { navigate(p); }
            }));
        }
        ScrollView marksScroll = new ScrollView(this);
        marksScroll.setHorizontalScrollBarEnabled(false);
        marksScroll.addView(marks);
        root.addView(marksScroll);

        status = Ui.text(this, "读取中…", 11f, Ui.col(this, R.color.text_dim));
        status.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 8));
        root.addView(status);

        list = Ui.column(this);
        root.addView(list);

        root.addView(Ui.footer(this));
        return Ui.scroller(this, root);
    }

    // ------------------------------------------------------------- listing

    private void up() {
        if ("/".equals(cwd)) {
            return;
        }
        int i = cwd.lastIndexOf('/');
        navigate(i <= 0 ? "/" : cwd.substring(0, i));
    }

    private void askPath() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(cwd);
        e.setTextSize(14f);
        new AlertDialog.Builder(this)
                .setTitle("跳到路径")
                .setView(e)
                .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        navigate(e.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void navigate(final String path) {
        final String p = path == null || path.length() == 0 ? "/" : path;
        status.setText("读取 " + p + " …");
        list.removeAllViews();
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run("ls -la " + AdbShell.quote(p), 15000);
                final List<String[]> rows = parse(r.text());
                final String raw = r.text();
                final int code = r.code;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (rows == null) {
                            status.setText("列目录失败：" + firstLine(raw));
                            return;
                        }
                        cwd = p;
                        pathView.setText(p);
                        render(rows);
                    }
                });
            }
        }).start();
    }

    /** Parses {@code ls -la} output. Returns null when nothing looked like a listing. */
    private static List<String[]> parse(String raw) {
        if (raw == null) {
            return null;
        }
        List<String[]> rows = new ArrayList<String[]>();
        String[] lines = raw.split("\n");
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.length() < 10 || ln.startsWith("total ")) {
                continue;
            }
            char c0 = ln.charAt(0);
            if (c0 != '-' && c0 != 'd' && c0 != 'l' && c0 != 'c' && c0 != 'b'
                    && c0 != 's' && c0 != 'p') {
                continue;
            }
            any = true;
            String[] f = ln.split("\\s+", 8);
            if (f.length < 8) {
                continue;
            }
            String name = f[7];
            int arrow = name.indexOf(" -> ");
            String link = null;
            if (arrow > 0) {
                link = name.substring(arrow + 4);
                name = name.substring(0, arrow);
            }
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            rows.add(new String[] { name, f[0], f[4], link == null ? "" : link,
                    link == null ? "" : "→ " + link });
        }
        if (!any) {
            return null;
        }
        Collections.sort(rows, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                boolean da = a[1].charAt(0) == 'd';
                boolean db = b[1].charAt(0) == 'd';
                if (da != db) {
                    return da ? -1 : 1;
                }
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        return rows;
    }

    private void render(List<String[]> rows) {
        int dirs = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i)[1].charAt(0) == 'd') {
                dirs++;
            }
        }
        status.setText(rows.size() + " 项 · " + dirs + " 个文件夹");

        for (int i = 0; i < rows.size(); i++) {
            final String[] e = rows.get(i);
            boolean dir = e[1].charAt(0) == 'd';
            String title = (dir ? "📁  " : "📄  ") + e[0];
            String desc = e[1] + "   " + size(e[2]) + (e[4].length() > 0 ? "   " + e[4] : "");
            LinearLayout card = Ui.card(this, title, desc,
                    dir ? Ui.col(this, R.color.accent) : 0, new View.OnClickListener() {
                        public void onClick(View v) {
                            if (e[1].charAt(0) == 'd') {
                                navigate(join(cwd, e[0]));
                            } else {
                                viewFile(join(cwd, e[0]));
                            }
                        }
                    });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    menu(join(cwd, e[0]));
                    return true;
                }
            });
            list.addView(card);
        }
        if (rows.isEmpty()) {
            list.addView(Ui.hint(this, "（空目录）"));
        }
    }

    private static String join(String dir, String name) {
        if (name.startsWith("/")) {
            return name;
        }
        return "/".equals(dir) ? "/" + name : dir + "/" + name;
    }

    private static String size(String s) {
        try {
            long n = Long.parseLong(s.trim());
            if (n < 1024) {
                return n + " B";
            }
            if (n < 1024 * 1024) {
                return (n / 1024) + " KB";
            }
            if (n < 1024L * 1024 * 1024) {
                return (n / (1024 * 1024)) + " MB";
            }
            return (n / (1024L * 1024 * 1024)) + " GB";
        } catch (Throwable t) {
            return s;
        }
    }

    // ---------------------------------------------------------------- file

    private void viewFile(final String path) {
        toast("读取 " + path);
        new Thread(new Runnable() {
            public void run() {
                AdbShell.Result r = AdbShell.run(
                        "wc -c < " + AdbShell.quote(path) + " ; head -c " + MAX_PREVIEW
                      + " " + AdbShell.quote(path), 15000);
                final String out = r.text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        showText(path, out);
                    }
                });
            }
        }).start();
    }

    private void showText(final String path, String raw) {
        String body = raw == null ? "" : raw;
        long bytes = -1;
        int nl = body.indexOf('\n');
        if (nl > 0) {
            try {
                bytes = Long.parseLong(body.substring(0, nl).trim());
            } catch (Throwable ignored) {
                bytes = -1;
            }
            body = body.substring(nl + 1);
        }

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        TextView head = Ui.text(this, path + (bytes >= 0 ? "\n" + size(String.valueOf(bytes)) : ""),
                11f, Ui.col(this, R.color.accent));
        head.setTypeface(Typeface.MONOSPACE);
        box.addView(head);

        final EditText ed = new EditText(this);
        ed.setText(body);
        ed.setTextSize(12f);
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        ed.setMinLines(8);
        box.addView(ed);

        ScrollView sc = Ui.scroller(this, box);
        new AlertDialog.Builder(this)
                .setTitle("查看 / 编辑")
                .setView(sc)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        write(path, ed.getText().toString());
                    }
                })
                .setNeutralButton("更多", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        menu(path);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void write(final String path, final String content) {
        new Thread(new Runnable() {
            public void run() {
                // Heredoc keeps the payload out of shell word-splitting.
                String cmd = "cat > " + AdbShell.quote(path) + " << 'RC2EOF'\n"
                        + content + "\nRC2EOF";
                AdbShell.Result r = AdbShell.run(cmd, 20000);
                final String out = r.text();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("已写入 " + path + (out.trim().length() > 0
                                ? "（" + firstLine(out) + "）" : ""));
                        navigate(cwd);
                    }
                });
            }
        }).start();
    }

    /** Per-file actions: copy path, rename, delete, chmod. */
    private void menu(final String path) {
        final String[] items = { "复制路径", "重命名 / 移动", "改权限 644", "改权限 755", "删除" };
        new AlertDialog.Builder(this)
                .setTitle(path)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            copy(path);
                        } else if (which == 1) {
                            rename(path);
                        } else if (which == 2) {
                            chmod(path, "644");
                        } else if (which == 3) {
                            chmod(path, "755");
                        } else {
                            confirmDelete(path);
                        }
                    }
                })
                .show();
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", s));
            toast("已复制路径");
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void rename(final String path) {
        final EditText e = new EditText(this);
        e.setText(path);
        e.setTextSize(13f);
        new AlertDialog.Builder(this)
                .setTitle("重命名 / 移动到")
                .setView(e)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String to = e.getText().toString().trim();
                        if (to.length() == 0 || to.equals(path)) {
                            return;
                        }
                        new Thread(new Runnable() {
                            public void run() {
                                AdbShell.Result r = AdbShell.run(
                                        "mv " + AdbShell.quote(path) + " " + AdbShell.quote(to), 15000);
                                final String out = r.text();
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast("已移动" + (out.trim().length() > 0
                                                ? "：" + firstLine(out) : ""));
                                        navigate(cwd);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chmod(final String path, final String mode) {
        new Thread(new Runnable() {
            public void run() {
                AdbShell.run("chmod " + mode + " " + AdbShell.quote(path), 10000);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("chmod " + mode + " 完成");
                        navigate(cwd);
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final String path) {
        new AlertDialog.Builder(this)
                .setTitle("删除？")
                .setMessage(path + "\n\n这一步不可撤销。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                AdbShell.Result r = AdbShell.run("rm -rf " + AdbShell.quote(path), 20000);
                                final String out = r.text();
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        toast(out.trim().length() == 0 ? "已删除"
                                                : "删除返回：" + firstLine(out));
                                        navigate(cwd);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int i = t.indexOf('\n');
        return i > 0 ? t.substring(0, i) : t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
