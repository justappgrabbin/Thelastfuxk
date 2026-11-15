package app.lastfuck;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Localhost bridge so klein-agent's Python (running in Alpine, without PyTorch) can use the Java KleinModel. */
final class KleinEngineServer implements AutoCloseable {
    private final KleinModel model;
    private final JSONObject config;
    final String token = UUID.randomUUID().toString();
    private final ServerSocket socket;
    private volatile boolean running = true;

    KleinEngineServer(KleinModel model, JSONObject config) throws IOException {
        this.model = model;
        this.config = config;
        socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::loop, "klein-engine");
        t.setDaemon(true);
        t.start();
    }

    int port() { return socket.getLocalPort(); }

    private void loop() {
        while (running) {
            try {
                Socket s = socket.accept();
                Thread t = new Thread(() -> handle(s), "klein-engine-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) { if (!running) return; }
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n';) { if (b.size() > 8192) throw new IOException("header too long"); b.write(c); }
        return b.toString("UTF-8").replaceAll("\r$", "");
    }

    private void handle(Socket s) {
        try (Socket client = s) {
            client.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            String[] request = line(in).split(" ");
            int length = 0; String auth = "";
            for (String h; !(h = line(in)).isEmpty();) {
                int c = h.indexOf(':');
                if (c < 0) continue;
                String k = h.substring(0, c).trim().toLowerCase(java.util.Locale.ROOT), v = h.substring(c + 1).trim();
                if (k.equals("content-length")) length = Integer.parseInt(v);
                if (k.equals("authorization")) auth = v;
            }
            JSONObject reply;
            if (!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
                reply = new JSONObject().put("error", "unauthorized");
            } else if (length > 16 * 1024 * 1024) {
                reply = new JSONObject().put("error", "request too large");
            } else {
                byte[] body = new byte[length];
                for (int n = 0; n < length;) { int r = in.read(body, n, length - n); if (r < 0) throw new EOFException(); n += r; }
                try { reply = dispatch(request.length > 1 ? request[1] : "", length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8))); }
                catch (Exception e) { reply = new JSONObject().put("error", String.valueOf(e.getMessage())); }
            }
            byte[] bytes = reply.toString().getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) { }
    }

    JSONObject dispatch(String path, JSONObject q) throws Exception {
        if (path.equals("/config")) return new JSONObject(config.toString());
        if (!path.equals("/forward")) throw new IllegalArgumentException("unknown path " + path);
        JSONArray ids = q.getJSONArray("ids"), feat = q.getJSONArray("feat"), links = q.optJSONArray("links");
        int L = ids.length();
        int[] id = new int[L];
        double[][] f = new double[L][];
        for (int i = 0; i < L; i++) {
            id[i] = ids.getInt(i);
            JSONArray row = feat.getJSONArray(i);
            f[i] = new double[model.nf];
            for (int j = 0; j < Math.min(row.length(), model.nf); j++) f[i][j] = row.getDouble(j);
        }
        int[][] ls = new int[links == null ? 0 : links.length()][];
        for (int i = 0; i < ls.length; i++) { JSONArray e = links.getJSONArray(i); ls[i] = new int[]{e.getInt(0), e.getInt(1), e.getInt(2)}; }
        double[] cond = null;
        if (q.has("cond") && !q.isNull("cond")) {
            JSONArray c = q.getJSONArray("cond");
            cond = new double[model.condDim];
            for (int i = 0; i < Math.min(c.length(), cond.length); i++) cond[i] = c.getDouble(i);
        }
        double[][] logits = model.forward(id, f, ls, cond);
        int start = Math.max(0, Math.min(L - 1, q.optInt("start", L - 1)));
        JSONArray rows = new JSONArray();
        for (int t = start; t < L; t++) {
            JSONArray r = new JSONArray();
            for (double v : logits[t]) r.put(v);
            rows.put(r);
        }
        return new JSONObject().put("logits", rows);
    }

    @Override public void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) { }
    }

    /** Host test entry: java KleinEngineServer weights.bin  → prints "port token". */
    public static void main(String[] args) throws Exception {
        KleinModel m;
        JSONObject header;
        try (InputStream in = new FileInputStream(args[0])) { m = new KleinModel(in); }
        header = new JSONObject().put("vocab", m.vocab).put("ctx", m.ctx).put("d", m.d).put("layers", m.layers)
            .put("heads", m.heads).put("cond_dim", m.condDim);
        KleinEngineServer s = new KleinEngineServer(m, header);
        System.out.println(s.port() + " " + s.token);
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
