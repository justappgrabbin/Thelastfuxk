package app.lastfuck;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * One live login shell in the app's Alpine Linux, on a real pseudo-terminal (see assets/terminal/pty_bridge.py).
 * Output is kept in a rolling buffer with absolute offsets so the screen can poll for what it has not drawn yet,
 * and the session survives switching tabs. Input is framed (D = typed bytes, R = window size).
 */
final class TerminalSession {
    private static final int KEEP = 512 * 1024;
    private static TerminalSession current;

    private final LinuxWorkspace linux;
    private final Process process;
    private final OutputStream input;
    private final byte[] ring = new byte[KEEP];
    private long written; // total bytes ever received
    private volatile boolean alive = true;

    static synchronized TerminalSession open(Context context, File inbox, int cols, int rows) throws Exception {
        if (current != null && current.alive) { current.resize(cols, rows); return current; }
        current = new TerminalSession(context, inbox, cols, rows);
        return current;
    }

    static synchronized TerminalSession get() { return current; }

    private TerminalSession(Context context, File inbox, int cols, int rows) throws Exception {
        linux = new LinuxWorkspace(context, inbox);
        linux.prepare();
        File bridge = new File(linux.workspace, ".terminal/pty_bridge.py");
        bridge.getParentFile().mkdirs();
        try (InputStream in = context.getAssets().open("terminal/pty_bridge.py"); OutputStream out = new FileOutputStream(bridge)) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) != -1;) out.write(b, 0, n);
        }
        process = linux.interactive("exec python3 -u /workspace/.terminal/pty_bridge.py",
            "TERM=xterm-256color", "COLORTERM=truecolor", "TERM_COLS=" + cols, "TERM_ROWS=" + rows,
            "TERM_CWD=/workspace", "TERM_SHELL=/bin/sh", "PS1=\\u@cynthia:\\w\\$ ");
        input = process.getOutputStream();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[16384];
            try (InputStream out = process.getInputStream()) {
                for (int n; (n = out.read(buffer)) != -1;) append(buffer, n);
            } catch (IOException ignored) {
            } finally {
                alive = false;
                byte[] end = "\r\n[terminal closed · tap Open terminal to start a new one]\r\n".getBytes(StandardCharsets.UTF_8);
                append(end, end.length);
            }
        }, "terminal-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private synchronized void append(byte[] data, int count) {
        for (int i = 0; i < count; i++) ring[(int) ((written + i) % KEEP)] = data[i];
        written += count;
    }

    /** Bytes since {@code from}; if the caller fell more than the buffer behind it gets the newest 512 KB. */
    synchronized byte[] readSince(long from) {
        long start = Math.max(from, Math.max(0, written - KEEP));
        if (start >= written) return new byte[0];
        byte[] out = new byte[(int) (written - start)];
        for (int i = 0; i < out.length; i++) out[i] = ring[(int) ((start + i) % KEEP)];
        return out;
    }

    synchronized long offset() { return written; }

    boolean isAlive() { return alive && process.isAlive(); }

    void write(byte[] data) throws IOException { frame('D', data); }

    void resize(int cols, int rows) throws IOException {
        if (cols > 0 && rows > 0) frame('R', (cols + "," + rows).getBytes(StandardCharsets.US_ASCII));
    }

    private synchronized void frame(char kind, byte[] payload) throws IOException {
        if (!isAlive()) throw new IOException("Terminal is closed");
        byte[] head = { (byte) kind, (byte) (payload.length >>> 24), (byte) (payload.length >>> 16),
            (byte) (payload.length >>> 8), (byte) payload.length };
        input.write(head);
        input.write(payload);
        input.flush();
    }

    static synchronized void close() {
        if (current == null) return;
        current.alive = false;
        current.process.destroyForcibly();
        current.linux.close();
        current = null;
    }
}
