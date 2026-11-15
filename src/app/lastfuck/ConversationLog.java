package app.lastfuck;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

/** Append-only conversation history, independent of a screen or model process. */
final class ConversationLog {
    private static final Object LOCK = new Object();
    private final File file;
    ConversationLog(File directory) { file = new File(directory, "conversation.jsonl"); }
    void append(String role, String text, String job, String status) throws Exception {
        synchronized (LOCK) {
            JSONObject row = new JSONObject().put("id", java.util.UUID.randomUUID().toString())
                .put("time", System.currentTimeMillis()).put("role", role).put("text", text)
                .put("job", job).put("status", status);
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), (row.toString()+"\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
    JSONArray tail(int count) throws Exception {
        synchronized (LOCK) {
            java.util.ArrayDeque<JSONObject> rows = new java.util.ArrayDeque<>();
            if (file.isFile()) try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line=reader.readLine())!=null) {
                    try { rows.add(new JSONObject(line)); } catch (JSONException damaged) { continue; }
                    while (rows.size()>count) rows.removeFirst();
                }
            }
            return new JSONArray(rows);
        }
    }
}
