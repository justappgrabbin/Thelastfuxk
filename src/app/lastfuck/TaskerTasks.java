package app.lastfuck;
import java.util.*;

final class TaskerTasks {
    static List<String> names(String configured) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : configured.split("[\\r\\n]+")) if (!line.trim().isEmpty()) names.add(line.trim());
        return new ArrayList<>(names);
    }
    static String select(String configured, String requested) {
        List<String> allowed = names(configured);
        if (allowed.isEmpty()) throw new IllegalStateException("Add Tasker task names in Hands & Tasker first (one per line).");
        String task = requested == null ? "" : requested.trim();
        if (task.isEmpty()) task = allowed.get(0);
        if (!allowed.contains(task)) throw new IllegalArgumentException("Task is not configured. Available Tasker tasks: " + allowed);
        return task;
    }
}
