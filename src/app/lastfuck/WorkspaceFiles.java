package app.lastfuck;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import org.json.*;

/** Workspace operations shared by Android and the host regression tests. */
final class WorkspaceFiles {
    private final File workspace;
    WorkspaceFiles(File workspace) { this.workspace = workspace; }
    JSONObject call(String name, JSONObject args) throws Exception {
        switch (name) {
            case "list": {
                String[] entries = file(args.optString("path", ".")).list();
                if (entries == null) throw new IOException("Directory unavailable");
                Arrays.sort(entries);
                int offset = Math.min(entries.length, Math.max(0, args.optInt("offset")));
                int end = Math.min(entries.length, offset + 80);
                return ok(new JSONArray(Arrays.asList(entries).subList(offset, end)))
                    .put("totalEntries", entries.length).put("nextOffset", end < entries.length ? end : JSONObject.NULL);
            }
            case "read": {
                File file = file(args.getString("path"));
                if (!file.isFile()) throw new IOException("Regular file required");
                int offset = Math.max(0, args.optInt("offset"));
                try (FileInputStream in = new FileInputStream(file)) {
                    in.getChannel().position(offset);
                    byte[] bytes = new byte[2400];
                    int read = in.read(bytes);
                    int count = Math.max(0, read);
                    int next = offset + count;
                    return ok(new String(bytes, 0, count, StandardCharsets.UTF_8))
                        .put("nextOffset", next < file.length() ? next : JSONObject.NULL);
                }
            }
            case "write": {
                File target = file(args.getString("path"));
                String text = args.getString("text");
                Files.createDirectories(target.getParentFile().toPath());
                byte[] before = args.optBoolean("append") && target.isFile() ? Files.readAllBytes(target.toPath()) : new byte[0];
                if (args.optBoolean("append")) {
                    Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else RuntimeFiles.write(target, text);
                byte[] expected = text.getBytes(StandardCharsets.UTF_8);
                // Read persisted bytes; an in-memory "write succeeded" assertion is insufficient.
                byte[] actual = Files.readAllBytes(target.toPath());
                if (args.optBoolean("append")) {
                    byte[] combined = Arrays.copyOf(before, before.length + expected.length);
                    System.arraycopy(expected, 0, combined, before.length, expected.length);
                    expected = combined;
                }
                if (!Arrays.equals(expected, actual))
                    throw new IOException("File read-back does not match the requested write");
                return ok("File saved and bytes read back. Functional correctness still needs checking.")
                    .put("path", args.getString("path")).put("bytes", actual.length)
                    .put("sha256", sha256(actual)).put("readBackVerified", true);
            }
            default: throw new IOException("Unsupported workspace operation " + name);
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hash.toString();
    }

    private File file(String path) throws IOException {
        if (path.startsWith("/") || path.indexOf('\0') >= 0) throw new IOException("Use a relative workspace path");
        File candidate = new File(workspace, path).getCanonicalFile();
        if (!candidate.toPath().startsWith(workspace.getCanonicalFile().toPath()))
            throw new IOException("Path outside workspace");
        return candidate;
    }

    private static JSONObject ok(Object output) throws JSONException {
        return new JSONObject().put("ok", true).put("complete", true).put("output", output);
    }
}
