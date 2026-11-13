package app.lastfuck;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class LinuxWorkspace implements AutoCloseable {
    final File workspace;
    private final File inbox;
    private final File root;
    private final File tmp;
    private final File libraries;
    private final Context context;
    private volatile Process current;
    private volatile Process resident;
    private volatile boolean stopped;

    LinuxWorkspace(Context context, File inbox) {
        this.context = context;
        this.inbox = inbox;
        workspace = new File(context.getFilesDir(), "workspace");
        root = new File(context.getFilesDir(), "alpine-coder-v1");
        tmp = new File(context.getCacheDir(), "proot-tmp");
        libraries = new File(context.getFilesDir(), "proot-libs-v1");
    }

    private static final Object INSTALL_LOCK = new Object();

    void prepare() throws Exception {
        synchronized (INSTALL_LOCK) { prepareLocked(); }
    }

    private void prepareLocked() throws Exception {
        if (stopped) throw new InterruptedIOException();
        Files.createDirectories(workspace.toPath());
        Files.createDirectories(tmp.toPath());
        Files.createDirectories(libraries.toPath());
        File talloc = new File(libraries, "libtalloc.so.2");
        if (!talloc.exists()) {
            try (InputStream in = context.getAssets().open("libtalloc.so.2")) {
                File staging = new File(libraries, "libtalloc.tmp");
                Files.copy(in, staging.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!staging.renameTo(talloc)) throw new IOException("Could not install PRoot dependency");
            }
        }
        if (new File(root, "bin/busybox").isFile()) return;
        File staging = new File(root.getPath() + ".staging");
        RuntimeFiles.deleteTree(staging);
        Files.createDirectories(staging.toPath());
        File tar = File.createTempFile("alpine", ".tar.gz", context.getCacheDir());
        try {
            try (InputStream in = context.getAssets().open("alpine-coder.tar.gz")) {
                Files.copy(in, tar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Result extracted = execute(new ProcessBuilder("/system/bin/tar", "-xzf", tar.toString(), "-C", staging.toString()), 90);
            if (extracted.code != 0 || !new File(staging, "bin/busybox").isFile())
                throw new IOException("Linux unpack failed: " + extracted.output);
            if (!staging.renameTo(root)) throw new IOException("Could not activate Linux filesystem");
        } finally {
            tar.delete();
        }
    }

    Result androidShell(String command) throws Exception {
        Files.createDirectories(workspace.toPath());
        return execute(new ProcessBuilder("/system/bin/sh", "-c", command).directory(workspace), 90);
    }

    Result shell(String command, boolean manual) throws Exception {
        return execute(shellProcess(command, manual), 90);
    }

    /** Same as shell(), with a caller-chosen time limit (for long jobs such as a first-time git clone). */
    Result shell(String command, int seconds) throws Exception {
        return execute(shellProcess(command, false), seconds);
    }

    /** Starts a long-running Alpine process (e.g. the Klein server) whose output goes to a log file. Caller owns it. */
    Process background(String command, File log) throws Exception {
        return shellProcess(command, false).redirectErrorStream(true).redirectOutput(log).start();
    }

    private ProcessBuilder shellProcess(String command, boolean manual) throws Exception {
        prepare();
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        java.util.List<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(
            nativeDir + "/libproot.so", "-0", "-r", root.toString(), "-w", "/workspace",
            "-b", workspace + ":/workspace", "-b", "/dev/null:/dev/null",
            "-b", "/dev/urandom:/dev/urandom", "-b", "/dev/random:/dev/random"));
        if (manual) { args.add("-b"); args.add(inbox + ":/inbox"); }
        args.addAll(java.util.Arrays.asList("/usr/bin/env", "-i", "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8", "/bin/sh", "-c", command));
        ProcessBuilder process = new ProcessBuilder(args);
        Map<String, String> env = process.environment();
        env.put("PROOT_LOADER", nativeDir + "/libproot_loader.so");
        env.put("PROOT_TMP_DIR", tmp.toString());
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("LD_LIBRARY_PATH", libraries + ":" + nativeDir);
        return process;
    }

    synchronized void startResident() throws Exception {
        if (resident != null && resident.isAlive()) return;
        prepare();
        for (String directory : new String[]{"projects", "outputs", "notes", ".cynthia"})
            Files.createDirectories(new File(workspace, directory).toPath());
        File log = new File(context.getCacheDir(), "linux-computer.log");
        resident = shellProcess("while :; do sleep 60; done", false)
            .redirectErrorStream(true).redirectOutput(log).start();
        Thread.sleep(400);
        if (!resident.isAlive()) throw new IOException("Linux startup failed: "
            + (log.isFile() ? RuntimeFiles.read(new FileInputStream(log), 4000) : "no startup log"));
    }

    boolean isResidentAlive() { return resident != null && resident.isAlive(); }

    private Result execute(ProcessBuilder builder, int seconds) throws Exception {
        if (stopped) throw new InterruptedIOException();
        Process process = builder.redirectErrorStream(true).start();
        current = process;
        try {
            process.getOutputStream().close();
            if (stopped) throw new InterruptedIOException();
            long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            InputStream in = process.getInputStream();
            while (process.isAlive()) {
                if (stopped) throw new InterruptedIOException();
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Linux command timed out");
                if (in.available() == 0) {
                    process.waitFor(75, TimeUnit.MILLISECONDS);
                } else {
                    drain(in, output, buffer);
                }
            }
            while (in.available() > 0) drain(in, output, buffer);
            return new Result(process.exitValue(), output.toString("UTF-8"));
        } finally {
            process.destroyForcibly();
            current = null;
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream out, byte[] buffer) throws IOException {
        int count = in.read(buffer, 0, Math.min(buffer.length, in.available()));
        if (count > 0 && out.size() < 24576) out.write(buffer, 0, Math.min(count, 24576 - out.size()));
    }

    @Override public void close() {
        stopped = true;
        Process computer = resident;
        if (computer != null) computer.destroyForcibly();
        Process process = current;
        if (process != null) process.destroyForcibly();
    }

    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }
}
