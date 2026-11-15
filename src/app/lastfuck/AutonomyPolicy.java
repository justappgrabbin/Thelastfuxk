package app.lastfuck;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** Bounded, opt-in goal continuation. No background cloud service or destructive tools. */
final class AutonomyPolicy {
    static final int MAX_CYCLES = 3;
    static final long COOLDOWN_MS = 10 * 60_000L;
    private AutonomyPolicy() {}

    static boolean due(boolean enabled, String goal, String session,
                       int cycles, long nextAt, long now) {
        return enabled && goal != null && !goal.trim().isEmpty()
            && session != null && !session.isEmpty()
            && cycles >= 0 && cycles < MAX_CYCLES && now >= nextAt;
    }

    static boolean pauses(String outcome) {
        return !"Work recorded · review required".equals(outcome);
    }

    static String prompt(String session, String goal, String previous) {
        if (goal == null || goal.trim().isEmpty()) throw new IllegalArgumentException("Autonomy needs a goal");
        String context = previous == null ? "" : previous;
        if (context.length() > 1600) context = context.substring(0, 1600);
        return "Autonomy session ID: " + session + "\n"
            + "User-approved ongoing goal:\n" + goal.trim() + "\n\n"
            + "Choose ONE concrete, useful next step toward this goal. "
            + "Inspect existing files before deciding what to do. "
            + "Only use list, read, and write in /workspace. "
            + "Create new files or append to existing ones; never overwrite, delete, "
            + "run commands, use phone Hands, invoke Tasker, or contact external services. "
            + "Avoid repeating a previous step. If you need an unavailable tool, "
            + "ask for human involvement by returning blocked with the specific reason. "
            + "Never mark the overall goal complete just because a file was saved. "
            + "Evidence and outcome of previous cycle:\n" + context;
    }

    static void requireAllowedTool(String name, JSONObject args, File workspace)
        throws IOException {
        if ("list".equals(name) || "read".equals(name)) return;
        if (!"write".equals(name)) {
            throw new IOException("Autonomous goals allow only workspace list/read/write; "
                + name + " requires a separate user-directed task and permission");
        }
        if (args == null || !args.has("path")) throw new IOException("Write needs a path");
        String path = args.optString("path", "");
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0)
            throw new IOException("Use a relative workspace path");
        File target = new File(workspace, path).getCanonicalFile();
        if (!target.toPath().startsWith(workspace.getCanonicalFile().toPath()))
            throw new IOException("Path outside workspace");
        if (target.exists() && (target.isDirectory() || !args.optBoolean("append", false)))
            throw new IOException("Autonomous work cannot overwrite existing files: " + path
                + ". Append or request a reviewed task instead.");
    }
}
