package app.lastfuck;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class AgentRegression {
    static int checks;
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    static final class Tools implements CodingAgent.Tools {
        final WorkspaceFiles files;
        int calls;
        Tools(Path root) { files = new WorkspaceFiles(root.toFile()); }
        public String available() { return "list(path,offset), read(path,offset), write(path,text,append), linux(command), tasker()"; }
        public JSONObject call(String name, JSONObject args) throws Exception {
            calls++;
            if (name.equals("fail")) return new JSONObject().put("ok", false).put("error", "deliberate failure");
            if (name.equals("tasker")) return new JSONObject().put("ok", true).put("complete", false);
            if (name.equals("linux")) return new JSONObject().put("ok", false).put("exitCode", 1).put("output", "test failed");
            return files.call(name, args);
        }
    }
    static final class Script implements CodingAgent.Model {
        final String[] script; int index;
        Script(String... script) { this.script = script; }
        public String complete(JSONArray messages) {
            return script[Math.min(index++, script.length-1)];
        }
    }
    static String done = "{\"status\":\"done\",\"summary\":\"Everything is done\"}";
    static String call(String name, JSONObject args) throws Exception {
        return new JSONObject().put("tool", name).put("args", args).toString();
    }
    static CodingAgent.Result run(Tools tools, String... script) throws Exception {
        return new CodingAgent(new Script(script), tools, x -> {}).execute("Create hello.py and check it");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("lastfuck-regression");
        Tools t = new Tools(root);
        CodingAgent.Result r = run(t, done);
        check(r.status.equals("Blocked") && t.calls == 0, "immediate done without tools is blocked");
        check(r.output.contains("Rejected completion claim"), "rejected claim is retained in evidence");
        t = new Tools(root);
        r = run(t, call("fail", new JSONObject()), done);
        check(r.status.equals("Blocked"), "failed tool cannot become completed");
        String write = call("write", new JSONObject().put("path", "hello.py").put("text", "print('Hello')\n"));
        t = new Tools(root);
        r = run(t, write, call("read", new JSONObject().put("path", "hello.py")), done);
        check(Files.readString(root.resolve("hello.py")).equals("print('Hello')\n"), "agent executes actual file write and read");
        check(r.status.equals("Work recorded · review required"), "successful tool work does not auto-approve task");
        check(r.output.contains("readBackVerified") && r.output.contains("sha256"), "file evidence contains read-back and checksum");
        t = new Tools(root);
        r = run(t, write, call("linux", new JSONObject().put("command", "false")), done);
        check(r.status.equals("Blocked") && r.output.contains("exit 1"), "failed tests block a later done claim");
        t = new Tools(root);
        r = run(t, call("tasker", new JSONObject()), done);
        check(r.status.equals("Awaiting confirmation"), "external dispatch stays unconfirmed");
        t = new Tools(root);
        r = run(t, "not json", write, done);
        check(r.status.equals("Work recorded · review required") && r.output.contains("invalid model JSON"), "invalid JSON recovers without fake execution");
        t = new Tools(root);
        r = run(t, "Sure! Here you go:\n```json\n" + write + "\n```", call("read", new JSONObject().put("path", "hello.py")), done);
        check(t.calls == 2 && r.status.equals("Work recorded · review required"), "JSON wrapped in prose and fences is executed, not looped");
        t = new Tools(root);
        r = run(t, "{\"tool\":\"write\",\"args\":{\"path\":\"a.py\",\"text\":\"cut off");
        check(r.status.equals("Blocked") && r.output.contains("3 times in a row"), "truncated JSON stops after 3 tries instead of 24");
        t = new Tools(root);
        String same = call("list", new JSONObject().put("path", "."));
        r = run(t, same, same, same, same, same, same);
        check(r.status.equals("Blocked") && t.calls == 3 && r.output.contains("repeated the identical"), "identical tool call loop is broken");
        r = new CodingAgent(new Script("Hey! Good to see you. What are we building?"), new Tools(root), x -> {}).converse("hey there", "");
        check(r.status.equals("Reply") && r.output.startsWith("Hey!"), "plain prose in chat is spoken, not rejected as bad JSON");
        r = new CodingAgent(new Script("{\"response\":\"I am here\"}"), new Tools(root), x -> {}).converse("you there?", "");
        check(r.status.equals("Reply") && r.output.equals("I am here"), "reply under another key still speaks");
        t = new Tools(root);
        r = run(t, "not json");
        check(r.status.equals("Blocked"), "repeated invalid responses stop at step budget");
        t = new Tools(root);
        r = run(t, write, call("linux", new JSONObject().put("command", "false")),
            call("list", new JSONObject().put("path", ".")), done);
        check(r.status.equals("Blocked"), "unrelated successful listing cannot hide a failed test");
        WorkspaceFiles f = new WorkspaceFiles(root.toFile());
        f.call("write", new JSONObject().put("path", "hello.py").put("text", "# appended\n").put("append", true));
        check(Files.readString(root.resolve("hello.py")).equals("print('Hello')\n# appended\n"), "append preserves existing contents");
        boolean refused = false;
        try { f.call("write", new JSONObject().put("path", "../escape").put("text", "bad")); }
        catch (IOException e) { refused = true; }
        check(refused, "workspace traversal is rejected");
        Path outside = Files.createTempFile("outside", ".txt");
        Files.createSymbolicLink(root.resolve("link"), outside);
        refused = false;
        try { f.call("write", new JSONObject().put("path", "link").put("text", "bad")); }
        catch (IOException e) { refused = true; }
        check(refused, "symlink escape is rejected");
        for (int i=0;i<90;i++) Files.write(root.resolve("item"+i), new byte[0]);
        JSONObject list = f.call("list", new JSONObject().put("path", "."));
        int next = list.getInt("nextOffset");
        JSONObject tail = f.call("list", new JSONObject().put("path", ".").put("offset", next));
        check(list.getJSONArray("output").length()+tail.getJSONArray("output").length()==list.getInt("totalEntries"), "directory pagination exposes every entry");
        check(TaskerTasks.names("First\nSecond\r\nFirst").size()==2, "Tasker supports multiple configured tasks without duplicates");
        check(TaskerTasks.select("First\nSecond", "Second").equals("Second"), "agent can select a configured Tasker task by name");
        check(TaskerTasks.select("First\nSecond", "").equals("First"), "legacy default Tasker task remains supported");
        boolean taskRefused = false;
        try { TaskerTasks.select("First", "Other"); } catch (IllegalArgumentException e) { taskRefused = true; }
        check(taskRefused, "unconfigured Tasker task is rejected with guidance");
        System.out.println(checks+" regression checks passed");
    }
}
