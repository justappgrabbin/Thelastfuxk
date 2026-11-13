package app.lastfuck;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

/**
 * Runs Klein entirely on the phone:
 *  - the neural math in Java (KleinModel + KleinEngineServer, weights from assets/klein/klein-weights.bin),
 *  - klein-agent's own Python chat pipeline in the app's Alpine Linux, using the stand-ins in assets/klein/phone
 *    for torch / tokenizers / swisseph, served at http://127.0.0.1:7860 like web_app.py on a PC.
 */
final class KleinRuntime implements AutoCloseable {
    static final String REPO = "https://github.com/justappgrabbin/klein-agent";
    static final int PORT = 7860;
    static volatile String status = "Klein on this phone: not started";

    private final Context context;
    private final LinuxWorkspace linux;
    private KleinEngineServer engine;
    private Process python;
    private volatile boolean stopped;

    KleinRuntime(Context context, LinuxWorkspace linux) { this.context = context; this.linux = linux; }

    /** Blocking start; call from a background thread. Safe to call again to restart a dead server. */
    synchronized void start() throws Exception {
        if (stopped) return;
        if (python != null && python.isAlive()) return;
        if (engine == null) {
            status = "Klein: loading the model into the Java engine…";
            KleinModel model;
            try (InputStream in = context.getAssets().open("klein/klein-weights.bin")) { model = new KleinModel(in); }
            JSONObject config = new JSONObject().put("vocab", model.vocab).put("ctx", model.ctx).put("d", model.d)
                .put("layers", model.layers).put("heads", model.heads).put("cond_dim", model.condDim);
            engine = new KleinEngineServer(model, config);
        }
        File phoneDir = new File(linux.workspace, ".klein-phone");
        copyAssets("klein/phone", phoneDir);
        status = "Klein: checking klein-agent code in Linux…";
        LinuxWorkspace.Result have = linux.shell("test -f /root/klein-agent/web_app.py && test -f /root/klein-agent/run/tokenizer.json && echo yes", 60);
        if (!have.output.contains("yes")) {
            status = "Klein: downloading klein-agent (first start only, about 40 MB)…";
            // Partial, sparse clone: only the Python code, tokenizer, web files and skill notes Klein's chat reads.
            LinuxWorkspace.Result clone = linux.shell("set -e; rm -rf /root/klein-agent.partial; "
                + "git clone --depth 1 --filter=blob:none --sparse " + REPO + " /root/klein-agent.partial; "
                + "cd /root/klein-agent.partial; git sparse-checkout set --no-cone '/*.py' '/klein_tools_fixed/' '/run/tokenizer.json' "
                + "'/skills/catalog.json' '/skills/**/*.md' '/web/'; "
                + "cd /root; rm -rf /root/klein-agent; mv /root/klein-agent.partial /root/klein-agent; echo cloned", 1800);
            if (clone.code != 0 || !clone.output.contains("cloned"))
                throw new IOException("Could not download klein-agent: " + HandsTools.excerpt(clone.output, 600));
        }
        status = "Klein: starting the chat server…";
        File log = new File(context.getCacheDir(), "klein-phone.log");
        python = linux.background("cd /root/klein-agent && exec python3 /workspace/.klein-phone/klein_phone.py --root /root/klein-agent"
            + " --bridge http://127.0.0.1:" + engine.port() + " --bridge-token " + engine.token + " --port " + PORT, log);
        long deadline = System.currentTimeMillis() + 180_000;
        while (!stopped && python.isAlive() && System.currentTimeMillis() < deadline) {
            String text = log.isFile() ? RuntimeFiles.read(new FileInputStream(log), 65536) : "";
            if (text.contains("Klein (phone engine) on")) { status = "Klein running on this phone · http://127.0.0.1:" + PORT; return; }
            Thread.sleep(500);
        }
        String text = log.isFile() ? RuntimeFiles.read(new FileInputStream(log), 65536) : "";
        if (python.isAlive()) python.destroyForcibly();
        throw new IOException("Klein server didn't start: " + text.substring(Math.max(0, text.length() - 1200)).trim());
    }

    boolean isRunning() { return python != null && python.isAlive(); }

    private void copyAssets(String from, File to) throws IOException {
        String[] children = context.getAssets().list(from);
        if (children == null || children.length == 0) {
            to.getParentFile().mkdirs();
            try (InputStream in = context.getAssets().open(from); OutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) != -1;) out.write(b, 0, n);
            }
            return;
        }
        to.mkdirs();
        for (String c : children) copyAssets(from + "/" + c, new File(to, c));
    }

    @Override public synchronized void close() {
        stopped = true;
        if (python != null) python.destroyForcibly();
        if (engine != null) engine.close();
        status = "Klein stopped";
    }
}
