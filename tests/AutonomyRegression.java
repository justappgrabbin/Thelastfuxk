package app.lastfuck;

import java.io.*;
import java.nio.file.*;
import org.json.JSONObject;

public final class AutonomyRegression {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + label);
    }

    public static void main(String[] args) throws Exception {
        long now = 123456789L;
        check(!AutonomyPolicy.due(false, "Build", "session", 0, 0, now), "off cannot schedule");
        check(!AutonomyPolicy.due(true, "", "session", 0, 0, now), "empty goal cannot schedule");
        check(!AutonomyPolicy.due(true, "Build", "", 0, 0, now), "missing session cannot schedule");
        check(!AutonomyPolicy.due(true, "Build", "session", 0, now+1, now), "cooldown enforced");
        check(AutonomyPolicy.due(true, "Build", "session", 0, now, now), "enabled goal schedules when due");
        check(AutonomyPolicy.due(true, "Build", "session", 2, now, now), "last allowed cycle schedules");
        check(!AutonomyPolicy.due(true, "Build", "session", 3, now, now), "third cycle is terminal");
        check(AutonomyPolicy.pauses("Blocked") && AutonomyPolicy.pauses("Awaiting confirmation"),
            "blocked or unverified external action pauses autonomy");
        check(!AutonomyPolicy.pauses("Work recorded · review required"), "evidence-bearing work may advance");
        String prompt = AutonomyPolicy.prompt("abcd", "Build components", "Saved a test file");
        check(prompt.startsWith("Autonomy session ID: abcd\n"), "autonomy prompt binds session");
        check(prompt.contains("Build components") && prompt.contains("Saved a test file"),
            "goal and prior checkpoint preserved");
        Path workspace = Files.createTempDirectory("lastfuck-autonomy");
        JSONObject file = new JSONObject().put("path", "new.txt").put("text", "one");
        AutonomyPolicy.requireAllowedTool("write", file, workspace.toFile());
        check(true, "new workspace file allowed");
        Files.writeString(workspace.resolve("new.txt"), "original");
        boolean blocked = false;
        try { AutonomyPolicy.requireAllowedTool("write", file, workspace.toFile()); }
        catch (IOException expected) { blocked = true; }
        check(blocked, "overwrite denied in unattended mode");
        file.put("append", true);
        AutonomyPolicy.requireAllowedTool("write", file, workspace.toFile());
        check(true, "append allowed");
        blocked = false;
        try { AutonomyPolicy.requireAllowedTool("write",
            new JSONObject().put("path", "../outside.txt"), workspace.toFile()); }
        catch (IOException expected) { blocked = true; }
        check(blocked, "out-of-workspace path denied");
        for (String name : new String[]{"linux", "android_shell", "tasker", "tap", "type", "press"}) {
            blocked = false;
            try { AutonomyPolicy.requireAllowedTool(name, new JSONObject(), workspace.toFile()); }
            catch (IOException expected) { blocked = true; }
            check(blocked, "unattended tool blocked: " + name);
        }
        check(Files.readString(workspace.resolve("new.txt")).equals("original"),
            "testing policy does not modify user files");
        System.out.println(checks + " autonomy regression checks passed");
    }
}
