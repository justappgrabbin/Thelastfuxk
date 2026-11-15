package app.lastfuck;

import android.content.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

public final class TaskerInbox extends BroadcastReceiver {
    static final String ACTION = "app.lastfuck.QUEUE_TASK";

    static synchronized String token(Context context) {
        SharedPreferences settings = context.getSharedPreferences("agent", 0);
        String existing = settings.getString("tasker_token", "");
        if (!existing.isEmpty()) return existing;
        String token = UUID.randomUUID().toString();
        if (!settings.edit().putString("tasker_token", token).commit())
            throw new IllegalStateException("Could not save Tasker key");
        return token;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String expected = context.getSharedPreferences("agent", 0).getString("tasker_token", "");
        String supplied = intent.getStringExtra("token");
        String task = intent.getStringExtra("task");
        if (expected.isEmpty() || supplied == null || task == null || supplied.length() != expected.length()
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return;
        try {
            String id = new TaskQueue(context).enqueue("agent", task);
            if (isOrderedBroadcast()) {
                setResultCode(android.app.Activity.RESULT_OK);
                setResultData(id);
            }
        } catch (Exception e) {
            if (isOrderedBroadcast()) {
                setResultCode(android.app.Activity.RESULT_CANCELED);
                setResultData(e.getMessage());
            }
        }
    }
}
