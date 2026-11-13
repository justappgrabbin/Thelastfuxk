package app.lastfuck;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Talks to the user's klein-agent web_app.py (POST /api/chat). Klein is Cynthia's conversational voice. */
final class KleinClient {
    static final String DEFAULT_URL = "http://127.0.0.1:7860";

    static final class Reply {
        final String text, kind;
        Reply(String text, String kind) { this.text = text; this.kind = kind; }
    }

    static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return DEFAULT_URL;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api/chat")) u = u.substring(0, u.length() - "/api/chat".length());
        return u;
    }

    static Reply chat(String baseUrl, String message, String token) throws IOException {
        URL url = new URL(normalize(baseUrl) + "/api/chat");
        HttpURLConnection c = (HttpURLConnection) url.openConnection(
            "127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost()) ? Proxy.NO_PROXY : Proxy.NO_PROXY);
        try {
            byte[] body;
            try { body = new JSONObject().put("message", message).put("name", "user").toString().getBytes(StandardCharsets.UTF_8); }
            catch (Exception e) { throw new IOException(e); }
            c.setConnectTimeout(4000);
            c.setReadTimeout(90_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Content-Type", "application/json");
            if (token != null && !token.trim().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token.trim());
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) { out.write(body); }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String raw = in == null ? "" : RuntimeFiles.read(in, 256 * 1024);
            JSONObject json;
            try { json = new JSONObject(raw); } catch (Exception e) { throw new IOException("Klein sent non-JSON (HTTP " + code + ")"); }
            if (code >= 400) throw new IOException("Klein HTTP " + code + ": " + json.optString("error", raw));
            String reply = json.optString("reply", "").trim();
            if (reply.isEmpty()) throw new IOException("Klein returned an empty reply");
            return new Reply(reply, json.optString("kind", "klein"));
        } finally { c.disconnect(); }
    }
}
