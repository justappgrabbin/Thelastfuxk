package app.lastfuck;

import android.content.*;
import android.net.Uri;
import java.util.UUID;
import org.json.JSONObject;

final class TaskerBridge {
    static final String PERMISSION = "net.dinglisch.android.tasker.PERMISSION_RUN_TASKS";
    private static final String PACKAGE = "net.dinglisch.android.tasker";

    static String installedPackage(Context context) {
        for (String name : new String[]{PACKAGE + "m", PACKAGE}) {
            try { context.getPackageManager().getPackageInfo(name, 0); return name; }
            catch (android.content.pm.PackageManager.NameNotFoundException missing) { }
        }
        return null;
    }

    static JSONObject dispatch(Context context) throws Exception {
        return dispatch(context, new JSONObject());
    }

    static JSONObject status(Context context) throws Exception {
        String configured = context.getSharedPreferences("agent", 0).getString("tasker_task", "");
        return new JSONObject().put("ok", true).put("complete", true)
            .put("installedPackage", installedPackage(context)).put("permissionGranted", context.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("tasks", new org.json.JSONArray(TaskerTasks.names(configured)))
            .put("setup", "Hands & Tasker: enable Tasker access. In Tasker Preferences > Misc enable Allow External Access and allow this app.");
    }

    static JSONObject dispatch(Context context, JSONObject args) throws Exception {
        String task = TaskerTasks.select(context.getSharedPreferences("agent", 0).getString("tasker_task", ""), args.optString("task"));
        if (context.checkSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Tasker permission is unavailable. Use Enable Tasker access in Hands & Tasker; in Tasker enable Allow External Access.");
        String installed = installedPackage(context);
        if (installed == null) throw new IllegalStateException("Tasker is not installed. Install Tasker and enable Allow External Access.");
        Intent intent = new Intent(PACKAGE + ".ACTION_TASK")
            .setPackage(installed)
            .setData(Uri.parse("id:" + UUID.randomUUID()))
            .putExtra("version_number", "1.1")
            .putExtra("task_name", task);
        if (context.getPackageManager().queryBroadcastReceivers(intent, 0).isEmpty())
            throw new IllegalStateException("Tasker task receiver unavailable");
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (String parameter : new String[]{"par1", "par2"}) {
            if (args.has(parameter)) { names.add("%" + parameter); values.add(args.getString(parameter)); }
        }
        if (!names.isEmpty()) {
            intent.putStringArrayListExtra("varNames", names);
            intent.putStringArrayListExtra("varValues", values);
        }
        context.sendBroadcast(intent);
        return new JSONObject().put("ok", true).put("complete", false)
            .put("task", task).put("evidence", "Broadcast dispatched to Tasker task " + task + ". Completion has not been confirmed.");
    }
}
