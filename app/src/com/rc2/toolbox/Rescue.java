package com.rc2.toolbox;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;

/**
 * 急救通道。
 *
 * 这个类存在的唯一理由：adb 的 shell 服务在 adbd 里是写死
 * `exec /system/bin/sh -c ...` 的。一旦 /system/bin 被某个挂载点盖住，
 * adb shell、am、以及所有 `Runtime.exec("字符串")` 全部失效，
 * 从外部就再也没有办法操作这台设备了。
 *
 * 但 App 自己的 Java 代码此刻仍然活着，而且有两条不经过 sh 的路：
 *
 *   1) Runtime.exec(String[]) 走的是 execve，内核直接加载程序，不经过 shell。
 *      所以只要把一个小程序解到自己的私有目录、chmod +x，就能执行它。
 *      （assets/rescue 就是这么来的：一个 168 字节的 32 位 ARM PIE，
 *        先 umount2("/system/bin", MNT_DETACH)，摘不掉就 reboot。）
 *
 *   2) 反射写 sys.powerctl，init 会接管并重启设备。
 *
 * 两条路都能把机器拉回可用状态。这不是常规功能，是安全网。
 */
public class Rescue {

    public static void run(Context c) {
        StringBuilder log = new StringBuilder();
        log.append("=== rescue @ ").append(System.currentTimeMillis()).append(" ===\n");

        // ---- 0) 先记录现场，这样即使马上重启也留得下证据 --------------------
        try {
            String[] bin = new File("/system/bin").list();
            log.append("/system/bin entries = ").append(bin == null ? -1 : bin.length).append('\n');
        } catch (Throwable t) {
            log.append("/system/bin list failed: ").append(t).append('\n');
        }
        boolean shThere = new File("/system/bin/sh").exists();
        log.append("sh exists = ").append(shThere).append('\n');
        log.append("reboot exists = ").append(new File("/system/bin/reboot").exists()).append('\n');

        // /system/bin 还是好的就什么都不用做，直接收工。
        if (shThere) {
            write(c, log.toString());
            return;
        }

        // ---- 1) 解出 rescue 小程序，直接 execve ------------------------------
        String elf = null;
        try {
            File dst = new File(c.getFilesDir(), "rescue");
            InputStream in = c.getAssets().open("rescue");
            OutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            dst.setExecutable(true, false);
            elf = dst.getAbsolutePath();
            log.append("elf = ").append(elf).append(" len=").append(dst.length())
               .append(" canExec=").append(dst.canExecute()).append('\n');
        } catch (Throwable t) {
            log.append("elf extract failed: ").append(t).append('\n');
        }
        if (elf != null) {
            exec(log, new String[]{ elf });
        }

        // ---- 2) 备选：如果系统工具还在（App 的挂载命名空间没被影响） --------
        if (new File("/system/bin/umount").exists()) {
            exec(log, new String[]{ "/system/bin/umount", "/system/bin" });
        }
        if (new File("/system/bin/reboot").exists()) {
            exec(log, new String[]{ "/system/bin/reboot" });
        }

        // ---- 3) 兜底：反射让 init 重启 --------------------------------------
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, "sys.powerctl", "reboot,rescue");
            log.append("SystemProperties sys.powerctl set ok\n");
        } catch (Throwable t) {
            log.append("SystemProperties failed: ").append(t).append('\n');
        }

        write(c, log.toString());
    }

    private static void exec(StringBuilder log, String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) log.append("  out: ").append(line).append('\n');
            r.close();
            BufferedReader e = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = e.readLine()) != null) log.append("  err: ").append(line).append('\n');
            e.close();
            log.append("exec ").append(cmd[0]).append(" exit=").append(p.waitFor()).append('\n');
        } catch (Throwable t) {
            log.append("exec ").append(cmd[0]).append(" failed: ").append(t).append('\n');
        }
    }

    private static void write(Context c, String text) {
        try {
            FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "rescue.log"), true);
            o.write(text.getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) {
        }
    }
}
