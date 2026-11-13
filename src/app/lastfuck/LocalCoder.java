package app.lastfuck;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Consumer;

final class LocalCoder implements AutoCloseable {
    private static final long SIZE = 1117320768L;
    private static final String HASH = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046";
    private static final String MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/2ab9f8f42af02fc212effaef7c4850c885e965f4/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf";
    private final Context context;
    private final Consumer<String> status;
    private final String token = UUID.randomUUID().toString();
    private volatile HttpURLConnection activeRequest;
    private volatile Process server;
    private volatile boolean stopped;
    private int port;

    LocalCoder(Context context, Consumer<String> status) {
        this.context = context;
        this.status = status;
    }

    /** Human-readable weights state and 0-100 progress, shown in the app and notification. */
    static volatile String weightsStatus = "Qwen weights not checked yet";
    static volatile int weightsPercent = 0;
    static volatile boolean weightsReady = false;

    /** Downloads and verifies the weights if missing. Safe to call at startup and again from start(). */
    synchronized File ensureModel() throws Exception { return model(); }

    private synchronized File model() throws Exception {
        File model = new File(context.getFilesDir(), "qwen-coder.gguf");
        if (model.length() == SIZE) { markReady(); return model; }
        File partial = new File(model.getPath() + ".part");
        if (partial.length() > SIZE) partial.delete();
        if (context.getFilesDir().getUsableSpace() + partial.length() < SIZE + 100_000_000L) {
            weightsStatus = "Need about 1.2 GB free to download Qwen weights";
            throw new IOException("Free at least 1.2 GB to install Qwen.");
        }
        IOException last = null;
        for (int attempt = 1; attempt <= 5 && !stopped; attempt++) {
            try {
                downloadInto(partial, attempt);
                break;
            } catch (InterruptedIOException e) { throw e;
            } catch (IOException e) {
                last = e;
                weightsStatus = "Download interrupted (" + e.getMessage() + ") · retry " + attempt + " of 5, resuming at "
                    + (100 * partial.length() / SIZE) + "%";
                status.accept(weightsStatus);
                Thread.sleep(Math.min(30_000L, 3_000L * attempt));
            }
        }
        if (stopped) throw new InterruptedIOException();
        if (partial.length() != SIZE) throw last != null ? last : new IOException("Qwen download incomplete");
        weightsStatus = "Verifying Qwen weights checksum…";
        status.accept(weightsStatus);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(partial)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int count; (count = in.read(buffer)) != -1;) {
                if (stopped) throw new InterruptedIOException();
                digest.update(buffer, 0, count);
            }
        }
        try { verify(partial.length(), digest); }
        catch (IOException bad) {
            partial.delete(); // corrupt bytes cannot be resumed; next start re-downloads cleanly
            weightsStatus = "Qwen checksum mismatch · download will restart";
            throw bad;
        }
        if (!partial.renameTo(model)) throw new IOException("Could not install the verified model");
        markReady();
        return model;
    }

    private void markReady() {
        weightsReady = true;
        weightsPercent = 100;
        weightsStatus = "Qwen weights installed · 1.12 GB verified";
    }

    /** Appends to the partial file using an HTTP Range request so drops resume instead of restarting. */
    private void downloadInto(File partial, int attempt) throws Exception {
        long have = partial.length();
        if (have >= SIZE) return;
        HttpURLConnection request = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        request.setConnectTimeout(20_000);
        request.setReadTimeout(60_000);
        request.setInstanceFollowRedirects(true);
        if (have > 0) request.setRequestProperty("Range", "bytes=" + have + "-");
        activeRequest = request;
        try {
            if (stopped) throw new InterruptedIOException();
            weightsStatus = have > 0 ? "Resuming Qwen download · " + (100 * have / SIZE) + "%"
                : "Downloading Qwen Coder weights · first start only · 1.12 GB";
            status.accept(weightsStatus);
            int response = request.getResponseCode();
            boolean append = response == 206;
            if (response == 200 && have > 0) have = 0; // server ignored Range: start over
            else if (!append && response != 200) throw new IOException("HTTP " + response);
            long total = have;
            long nextUpdate = 0;
            try (InputStream in = request.getInputStream(); FileOutputStream out = new FileOutputStream(partial, append)) {
                byte[] buffer = new byte[256 * 1024];
                for (int count; (count = in.read(buffer)) != -1;) {
                    if (stopped) throw new InterruptedIOException();
                    total += count;
                    if (total > SIZE) throw new IOException("Unexpected model size");
                    out.write(buffer, 0, count);
                    if (total >= nextUpdate) {
                        weightsPercent = (int) (100 * total / SIZE);
                        weightsStatus = "Downloading Qwen Coder · " + weightsPercent + "% of 1.12 GB";
                        status.accept(weightsStatus);
                        nextUpdate = total + SIZE / 100;
                    }
                }
                out.getFD().sync();
            }
            if (total != SIZE) throw new IOException("connection closed at " + (100 * total / SIZE) + "%");
        } finally {
            request.disconnect();
            activeRequest = null;
        }
    }

    static void importModel(Context context, android.net.Uri uri) throws Exception {
        File model = new File(context.getFilesDir(), "qwen-coder.gguf");
        if (model.length() == SIZE) return;
        if (context.getFilesDir().getUsableSpace() < SIZE + 100_000_000L)
            throw new IOException("Free at least 1.2 GB to install Qwen.");
        File partial = File.createTempFile("qwen-import", ".part", context.getFilesDir());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(partial)) {
                if (in == null) throw new IOException("Could not open model file");
                byte[] buffer = new byte[256 * 1024];
                for (int count; (count = in.read(buffer)) != -1;) {
                    size += count;
                    if (size > SIZE) throw new IOException("Unexpected model size");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            verify(size, digest);
            if (!partial.renameTo(model)) throw new IOException("Could not install imported Qwen");
            weightsReady = true; weightsPercent = 100; weightsStatus = "Qwen weights imported · verified";
        } finally {
            partial.delete();
        }
    }

    private static void verify(long size, MessageDigest digest) throws IOException {
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (size != SIZE || !HASH.equals(hash.toString())) throw new IOException("Qwen checksum mismatch");
    }

    void start() throws Exception {
        if (server != null && server.isAlive()) return;
        File weights = model();
        if (stopped) throw new InterruptedIOException();
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libllama_server.so");
        File log = new File(context.getCacheDir(), "qwen-runtime.log");
        status.accept("Starting the local Qwen server");
        server = new ProcessBuilder(executable.toString(), "--model", weights.toString(), "--host", "127.0.0.1",
            "--port", String.valueOf(port), "--api-key", token, "--ctx-size", "8192",
            "--parallel", "1", "--threads", "4", "--n-gpu-layers", "0", "--no-webui",
            "--alias", "qwen-coder").redirectErrorStream(true).redirectOutput(log).start();
        if (stopped) { server.destroyForcibly(); throw new InterruptedIOException(); }
        long deadline = android.os.SystemClock.elapsedRealtime() + 120_000;
        while (!stopped && server.isAlive() && android.os.SystemClock.elapsedRealtime() < deadline) {
            try { call("/health", null, 1000); return; }
            catch (IOException waiting) { Thread.sleep(400); }
        }
        server.destroyForcibly();
        server = null;
        String detail = log.isFile() ? RuntimeFiles.read(new FileInputStream(log), 65536) : "";
        throw new IOException("Qwen could not start. " + detail.substring(Math.max(0, detail.length() - 1800)).trim());
    }

    String complete(JSONArray messages) throws Exception { return complete(messages, true); }

    String complete(JSONArray messages, boolean toolJson) throws Exception {
        JSONObject payload = new JSONObject().put("model", "qwen-coder").put("messages", messages)
            .put("temperature", 0.15).put("max_tokens", 1536);
        if (toolJson) payload.put("response_format", new JSONObject().put("type", "json_object"));
        return new JSONObject(call("/v1/chat/completions", payload, 240_000)).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content");
    }

    private String call(String path, JSONObject data, int timeout) throws IOException {
        HttpURLConnection request = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
            .openConnection(Proxy.NO_PROXY);
        activeRequest = request;
        try {
            if (stopped) throw new InterruptedIOException();
            request.setInstanceFollowRedirects(false);
            request.setRequestProperty("Authorization", "Bearer " + token);
            request.setConnectTimeout(2500);
            request.setReadTimeout(timeout);
            if (data != null) {
                byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
                request.setRequestMethod("POST");
                request.setDoOutput(true);
                request.setRequestProperty("Content-Type", "application/json");
                request.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = request.getOutputStream()) { out.write(body); }
            }
            int code = request.getResponseCode();
            InputStream stream = code == 200 ? request.getInputStream() : request.getErrorStream();
            String response = stream == null ? "" : RuntimeFiles.read(stream, 32768);
            if (code != 200) throw new IOException("Local Qwen HTTP " + code + " " + response);
            return response;
        } finally {
            request.disconnect();
            activeRequest = null;
        }
    }

    @Override public void close() {
        stopped = true;
        HttpURLConnection request = activeRequest;
        if (request != null) request.disconnect();
        Process current = server;
        if (current != null) current.destroyForcibly();
    }
}
