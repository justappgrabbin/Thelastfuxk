package app.lastfuck;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

final class TaskQueue {
    private static final java.util.concurrent.atomic.AtomicLong LAST_ID = new java.util.concurrent.atomic.AtomicLong();
    final File inbox;
    private final File root;
    private final File records;

    TaskQueue(Context context) {
        root = new File(context.getFilesDir(), "jobs");
        inbox = new File(root, "inbox");
        records = new File(root, "records");
    }

    void prepare() throws IOException {
        Files.createDirectories(inbox.toPath());
        Files.createDirectories(records.toPath());
    }

    String enqueue(String kind, String text) throws Exception {
        prepare();
        text = text.trim();
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IOException("Task must contain 1–8192 UTF-8 bytes");
        String id = nextId();
        if (java.util.Arrays.asList("assistant", "chat", "agent").contains(kind))
            new ConversationLog(root.getParentFile()).append("user", text, id, "Queued");
        RuntimeFiles.write(new File(inbox, id + (("shell".equals(kind) || "android-shell".equals(kind) || "tasker".equals(kind)) ? ".manual.job" : ".job")), new JSONObject().put("kind", kind).put("text", text).toString());
        return id;
    }

    private static String nextId() {
        long timestamp = LAST_ID.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
        return timestamp + "-" + UUID.randomUUID();
    }

    File claim(boolean manual) throws IOException {
        File[] inputs = inbox.listFiles(file -> {
            String name = file.getName();
            return manual ? name.endsWith(".manual.job") : !name.endsWith(".manual.job")
                && (name.endsWith(".task") || name.endsWith(".job"));
        });
        if (inputs == null || inputs.length == 0) return null;
        Arrays.sort(inputs, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        File input = inputs[0];
        File claimed = new File(records, input.getName().replaceFirst("\\.(manual\\.)?(job|task)$", "")
            + (input.getName().endsWith(".job") ? ".running-job" : ".running-task"));
        Files.move(input.toPath(), claimed.toPath());
        return claimed;
    }

    JSONObject load(File claimed) throws Exception {
        if (!Files.isRegularFile(claimed.toPath(), LinkOption.NOFOLLOW_LINKS) || claimed.length() > 16384)
            throw new IOException("Watcher input must be a small regular file");
        String text = RuntimeFiles.read(new FileInputStream(claimed), 16384).trim();
        if (text.isEmpty()) throw new IOException("Watcher input is empty");
        if (claimed.getName().endsWith(".running-job")) return new JSONObject(text);
        return new JSONObject().put("kind", "agent").put("text", text);
    }

    void finish(File file, String status, String title, String output) throws IOException {
        String id = file.getName().split("\\.running-")[0];
        RuntimeFiles.write(new File(records, id + ".result"), status + " · " + title + "\n" + output);
        if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath());
        } else {
            Files.move(file.toPath(), new File(records, id + ".invalid").toPath());
        }
    }

    void markInterrupted() throws IOException {
        File[] running = records.listFiles(file -> file.getName().contains(".running-"));
        if (running == null) return;
        for (File file : running) {
            String id = file.getName().split("\\.running-")[0];
            if (new File(records, id + ".result").isFile()) Files.deleteIfExists(file.toPath());
            else finish(file, "Interrupted", "Previous task", "Android stopped the agent. Review workspace changes before resubmitting.");
        }
    }

    String history() throws IOException {
        prepare();
        StringBuilder live = new StringBuilder();
        File[] waiting = inbox.listFiles(file -> file.getName().endsWith(".job") || file.getName().endsWith(".task"));
        if (waiting != null) {
            Arrays.sort(waiting, Comparator.comparing(File::getName).reversed());
            for (int i = 0; i < Math.min(12, waiting.length); i++)
                live.append("Queued · ").append(waiting[i].getName()).append("\n");
        }
        File[] active = records.listFiles(file -> file.getName().contains(".running-"));
        if (active != null) {
            Arrays.sort(active, Comparator.comparing(File::getName).reversed());
            for (File file : active)
                live.append("Running · ").append(file.getName()).append("\n");
        }
        File[] results = records.listFiles(file -> file.getName().endsWith(".result"));
        if (results == null || results.length == 0) return live.length() == 0 ? "No tasks yet." : live.toString().trim();
        Arrays.sort(results, Comparator.comparing(File::getName).reversed());
        StringBuilder out = live;
        if (out.length() > 0) out.append("\n");
        for (int i = 0; i < Math.min(8, results.length); i++) {
            out.append(RuntimeFiles.read(new FileInputStream(results[i]), 12000)).append("\n\n");
        }
        return out.toString().trim();
    }
}
