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

    private File model() throws Exception {
        File model = new File(context.getFilesDir(), "qwen-coder.gguf");
        if (model.length() == SIZE) return model;
        if (context.getFilesDir().getUsableSpace() < SIZE + 100_000_000L)
            throw new IOException("Free at least 1.2 GB to install Qwen.");
        File partial = new File(model.getPath() + ".part");
        HttpURLConnection request = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        request.setConnectTimeout(20_000);
        request.setReadTimeout(45_000);
        activeRequest = request;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        long nextUpdate = 0;
        try {
            status.accept("Downloading Qwen Coder. First start only · 1.12 GB");
            if (stopped) throw new InterruptedIOException();
            int response = request.getResponseCode();
            if (response != 200) throw new IOException("Qwen model download returned HTTP " + response);
            try (InputStream in = request.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[256 * 1024];
                for (int count; (count = in.read(buffer)) != -1;) {
                    if (stopped) throw new InterruptedIOException();
                    total += count;
                    if (total > SIZE) throw new IOException("Unexpected model size");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                    if (total >= nextUpdate) {
                        status.accept("Downloading Qwen Coder · " + (100 * total / SIZE) + "%");
                        nextUpdate = total + SIZE / 50;
                    }
                }
                out.getFD().sync();
            }
            verify(total, digest);
            if (!partial.renameTo(model)) throw new IOException("Could not install the verified model");
            return model;
        } finally {
            request.disconnect();
            activeRequest = null;
            partial.delete();
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
