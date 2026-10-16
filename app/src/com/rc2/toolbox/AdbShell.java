package com.rc2.toolbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.zip.CRC32;

/**
 * Root channel for the DJI RC 2.
 *
 * <p>The remote controller ships a privileged daemon, launched as
 * {@code adbd-root --root_seclabel=u:r:su:s0}, which listens on
 * {@code 0.0.0.0:5555} while {@code ro.adb.secure=0}. That combination means
 * any client that can reach the loopback port is handed a {@code uid=0} shell
 * without authentication.
 *
 * <p>The device contains <b>no</b> {@code su} and <b>no</b> {@code adb} binary,
 * so this class speaks the ADB wire protocol itself over a local socket instead
 * of shelling out to a helper.
 *
 * <p>Everything runs off the main thread; callers must invoke {@link #run}
 * from a background thread.
 */
public final class AdbShell {

    private static final int CNXN = 0x4E584E43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4E45504F;
    private static final int OKAY = 0x59414B4F;
    private static final int CLSE = 0x45534C43;
    private static final int WRTE = 0x45545257;

    private static final int VERSION = 0x01000001;
    private static final int MAXDATA = 4096;
    private static final byte[] EMPTY = new byte[0];

    /** Loopback ports worth trying. 5555 is adbd's TCP port, 5037 the server port. */
    private static final int[] PORTS = { 5555, 5037 };

    private static final String EXIT_MARK = "__RC2EXIT__";

    /** Cached working port, or 0 when not yet probed. */
    private static volatile int cachedPort = 0;
    /** Last human-readable failure, for the UI. */
    public static volatile String lastError = "";
    /** Banner returned by the daemon, e.g. {@code device::ro.product.model=DJI RC 2;}. */
    private static volatile String banner = "";

    private AdbShell() {
    }

    /** Outcome of one shell command. */
    public static final class Result {
        public final String out;
        public final int code;

        Result(String out, int code) {
            this.out = out;
            this.code = code;
        }

        public boolean ok() {
            return code == 0;
        }

        public String text() {
            return out == null ? "" : out;
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** True when a root shell can be obtained right now. */
    public static boolean available() {
        return findPort() > 0;
    }

    /** Returns the first loopback port that accepts a connection, else 0. */
    public static int findPort() {
        int known = cachedPort;
        if (known > 0) {
            return known;
        }
        String why = null;
        for (int i = 0; i < PORTS.length; i++) {
            int port = PORTS[i];
            Socket probe = new Socket();
            try {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 800);
                cachedPort = port;
                lastError = "";
                return port;
            } catch (Throwable t) {
                // Socket ops are forbidden on the UI thread; surface that plainly
                // instead of silently reporting "no root channel".
                why = "127.0.0.1:" + port + " " + describe(t);
            } finally {
                close(probe);
            }
        }
        lastError = why == null ? "没有可尝试的端口" : why;
        return 0;
    }

    /** Runs a command with the default timeout and returns its output. */
    public static Result run(String command) {
        return run(command, 20000);
    }

    /** Runs a command through the root shell. */
    public static Result run(String command, int timeoutMs) {
        int port = findPort();
        if (port == 0) {
            lastError = "连不上本机 5555/5037 —— root 通道未开";
            return new Result("", -1);
        }
        try {
            return runOnPort(port, command, timeoutMs);
        } catch (Throwable t) {
            // The cached port may have gone stale (daemon restarted).
            cachedPort = 0;
            lastError = describe(t);
            return new Result("", -1);
        }
    }

    /** Convenience: run and return trimmed stdout, or null on failure. */
    public static String line(String command) {
        Result r = run(command, 12000);
        if (!r.ok() && r.text().trim().length() == 0) {
            return null;
        }
        String s = r.text().trim();
        return s.length() == 0 ? null : s;
    }

    /** {@code uid=0(root) ...} when the channel works. */
    public static String identity() {
        String s = line("id");
        return s == null ? "" : s;
    }

    /** True when the shell really is uid 0. */
    public static boolean isRoot() {
        String s = identity();
        return s.indexOf("uid=0") >= 0;
    }

    public static String banner() {
        return banner;
    }

    /** Starts an activity by explicit component, export status irrelevant for uid 0. */
    public static Result startActivity(String pkg, String cls) {
        return run("am start -n " + quote(pkg + "/" + cls), 15000);
    }

    /** Starts an activity with a data URI. */
    public static Result startActivityData(String pkg, String cls, String uri) {
        return run("am start -n " + quote(pkg + "/" + cls) + " -d " + quote(uri), 15000);
    }

    /** Writes one Settings row. */
    public static Result putSetting(String namespace, String key, String value) {
        return run("settings put " + namespace + " " + key + " " + quote(value), 10000);
    }

    public static Result getSetting(String namespace, String key) {
        return run("settings get " + namespace + " " + key, 10000);
    }

    /** Shell single-quote escaping for arbitrary arguments. */
    public static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Drops the cached port so the next call re-probes. */
    public static void reset() {
        cachedPort = 0;
    }

    // ------------------------------------------------------------------
    // ADB wire protocol
    // ------------------------------------------------------------------

    private static Result runOnPort(int port, String command, int timeoutMs) throws IOException {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            sock.setSoTimeout(timeoutMs);
            sock.setTcpNoDelay(true);
            OutputStream os = sock.getOutputStream();
            InputStream is = sock.getInputStream();

            // 1. CNXN handshake. ro.adb.secure=0 means the daemon answers
            //    with CNXN rather than demanding AUTH.
            sendPacket(os, CNXN, VERSION, MAXDATA, ascii("host::"));
            Packet hello = readPacket(is);
            if (hello.cmd == AUTH) {
                throw new IOException("设备要求 ADB 认证（ro.adb.secure=1），本通道不可用");
            }
            if (hello.cmd != CNXN) {
                throw new IOException("ADB 握手返回意外命令 " + hex(hello.cmd));
            }
            banner = asciiToString(hello.data);

            // 2. Open a shell stream. The daemon runs it as uid 0.
            //
            // 命令必须包在子 shell 里：`sh -c 'exit 7; echo MARK$?'` 中 exit 会
            // 立即终止整个 shell，后面那句 echo 根本没机会跑，我们只能拿到 rc=0
            // （或者干脆没有 marker）。包一层 `( ... )` 之后 exit 只结束子 shell，
            // 退出码仍能原样传给 `$?`。实测：`exit 7` → 无输出；`(exit 7)` → 7。
            String service = "shell:(" + command + ") 2>&1; echo " + EXIT_MARK + "$?";
            sendPacket(os, OPEN, 1, 0, payload(service));

            ByteArrayOutputStream sink = new ByteArrayOutputStream(4096);
            while (true) {
                Packet p = readPacket(is);
                if (p.cmd == OKAY) {
                    continue;
                }
                if (p.cmd == WRTE) {
                    sink.write(p.data);
                    sendPacket(os, OKAY, 1, p.arg0, EMPTY);
                    continue;
                }
                if (p.cmd == CLSE) {
                    sendPacket(os, CLSE, 1, p.arg0, EMPTY);
                    break;
                }
                if (p.cmd == CNXN) {
                    continue;
                }
                break;
            }

            String raw = sink.toString("UTF-8");
            return split(raw);
        } finally {
            close(sock);
        }
    }

    /** Separates the command output from the exit-code marker. */
    private static Result split(String raw) {
        int idx = raw.lastIndexOf(EXIT_MARK);
        if (idx < 0) {
            return new Result(raw, 0);
        }
        String body = raw.substring(0, idx);
        String tail = raw.substring(idx + EXIT_MARK.length()).trim();
        int code = -1;
        try {
            code = Integer.parseInt(tail.split("\\s+")[0]);
        } catch (Throwable ignored) {
            // leave -1
        }
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.endsWith("\r")) {
            body = body.substring(0, body.length() - 1);
        }
        return new Result(body, code);
    }

    private static final class Packet {
        int cmd;
        int arg0;
        int arg1;
        byte[] data;
    }

    private static Packet readPacket(InputStream is) throws IOException {
        byte[] hdr = new byte[24];
        readFully(is, hdr);
        Packet p = new Packet();
        p.cmd = le(hdr, 0);
        p.arg0 = le(hdr, 4);
        p.arg1 = le(hdr, 8);
        int len = le(hdr, 12);
        if (len < 0 || len > 1024 * 1024) {
            throw new IOException("ADB 报文长度异常: " + len);
        }
        p.data = new byte[len];
        if (len > 0) {
            readFully(is, p.data);
        }
        return p;
    }

    private static void sendPacket(OutputStream os, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(24 + data.length);
        writeLe(buf, cmd);
        writeLe(buf, arg0);
        writeLe(buf, arg1);
        writeLe(buf, data.length);
        writeLe(buf, (int) crc.getValue());
        writeLe(buf, cmd ^ 0xFFFFFFFF);
        buf.write(data, 0, data.length);
        os.write(buf.toByteArray());
        os.flush();
    }

    private static void readFully(InputStream is, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = is.read(dst, off, dst.length - off);
            if (n < 0) {
                throw new IOException("ADB 连接被提前关闭");
            }
            off += n;
        }
    }

    private static void writeLe(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static int le(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Throwable t) {
            return s.getBytes();
        }
    }

    private static byte[] payload(String s) {
        byte[] body = ascii(s);
        byte[] out = new byte[body.length + 1];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = 0;
        return out;
    }

    private static String asciiToString(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        int end = b.length;
        while (end > 0 && b[end - 1] == 0) {
            end--;
        }
        try {
            return new String(b, 0, end, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String hex(int v) {
        return "0x" + Integer.toHexString(v);
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        String n = t.getClass().getSimpleName();
        return m == null ? n : n + ": " + m;
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }
}
