package app.lastfuck;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.KeyguardManager;
import com.synthia.autonomy.SynthiaAccessibilityService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import org.json.*;

final class HandsTools implements CodingAgent.Tools {
    private final Context context;
    private final LinuxWorkspace linux;
    private final SharedPreferences settings;

    HandsTools(Context context, LinuxWorkspace linux) {
        this.context = context;
        this.linux = linux;
        settings = context.getSharedPreferences("agent", 0);
    }

    public String available() {
        String tools = "list(path,offset), read(path,offset), write(path,text,append), tasker_status()";
        if (settings.getBoolean("shell", false)) tools += ", linux(command), android_shell(command)";
        if (!settings.getString("tasker_task", "").trim().isEmpty()) tools += ", tasker(task,par1,par2). Configured Tasker tasks: " + TaskerTasks.names(settings.getString("tasker_task", ""));
        if (phoneEnabled()) tools += ", observe(offset), click(ref), tap(x,y), swipe(x1,y1,x2,y2), type(text), press(button)";
        return tools;
    }

    public JSONObject call(String name, JSONObject args) throws Exception {
        switch (name) {
            case "list": case "read": case "write":
                return new WorkspaceFiles(linux.workspace).call(name, args);
            case "linux": {
                if (!settings.getBoolean("shell", false)) throw new IOException("Agent Linux execution is disabled");
                LinuxWorkspace.Result result = linux.shell(args.getString("command"), false);
                return new JSONObject().put("ok", result.code == 0).put("complete", true)
                    .put("exitCode", result.code).put("output", excerpt(result.output, 2600));
            }
            case "android_shell": {
                if (!settings.getBoolean("shell", false)) throw new IOException("Agent shell execution is disabled in Hands & Tasker");
                LinuxWorkspace.Result result = linux.androidShell(args.getString("command"));
                return new JSONObject().put("ok", result.code == 0).put("complete", true)
                    .put("exitCode", result.code).put("output", excerpt(result.output, 2600));
            }
            case "tasker_status":
                return TaskerBridge.status(context);
            case "tasker":
                return TaskerBridge.dispatch(context, args);
            case "observe":
                requirePhone();
                return compactObservation(new JSONObject(SynthiaAccessibilityService.elementsJson()), args.optInt("offset"));
            case "click":
                requirePhone();
                return gesture(new JSONObject(SynthiaAccessibilityService.clickRefJson(args.getString("ref"))));
            case "type":
                requirePhone();
                return gesture(new JSONObject(SynthiaAccessibilityService.typeJson(args.getString("text"))));
            case "press":
                requirePhone();
                return gesture(new JSONObject(SynthiaAccessibilityService.pressJson(args.getString("button"))));
            case "tap":
                requirePhone();
                return gesture(new JSONObject(SynthiaAccessibilityService.tapJson(args.getInt("x"), args.getInt("y"))));
            case "swipe":
                requirePhone();
                return gesture(new JSONObject(SynthiaAccessibilityService.swipeJson(
                    args.getInt("x1"), args.getInt("y1"), args.getInt("x2"), args.getInt("y2"), 300)));
            default:
                throw new IOException("Unknown tool " + name);
        }
    }

    private boolean phoneEnabled() {
        return settings.getBoolean("phone_hands", false) && SynthiaAccessibilityService.isConnected()
            && !context.getSystemService(KeyguardManager.class).isDeviceLocked();
    }

    private void requirePhone() throws IOException {
        if (!phoneEnabled()) throw new IOException("Phone Hands require opt-in, accessibility and an unlocked phone");
    }

    private static JSONObject compactObservation(JSONObject result, int requestedOffset) throws JSONException {
        JSONArray elements = result.optJSONArray("elements");
        if (elements == null) return result;
        JSONArray first = new JSONArray();
        int offset = Math.min(elements.length(), Math.max(0, requestedOffset));
        int end = Math.min(elements.length(), offset + 12);
        for (int i = offset; i < end; i++) first.put(elements.get(i));
        return result.put("elements", first).put("totalElements", elements.length())
            .put("nextOffset", end < elements.length() ? end : JSONObject.NULL);
    }

    private static JSONObject gesture(JSONObject result) throws JSONException {
        if (result.optBoolean("ok")) result.put("complete", false).put("evidence", "Gesture accepted. Observe the screen to verify.");
        return result;
    }

    private static JSONObject ok(Object output) throws JSONException {
        return new JSONObject().put("ok", true).put("complete", true).put("output", output);
    }

    static String excerpt(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) + "\n[truncated]" : text;
    }
}
