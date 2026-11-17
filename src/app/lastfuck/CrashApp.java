package app.lastfuck;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;
import java.io.*;

/** Saves any crash to crash.txt and shows it on the next launch so it can be shared and fixed. */
public final class CrashApp extends Application {
    static File log(android.content.Context c) { return new File(c.getFilesDir(), "crash.txt"); }

    @Override public void onCreate() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(log(this)))) {
                w.println("App crashed " + new java.util.Date());
                w.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") " + Build.MANUFACTURER + " " + Build.MODEL);
                w.println("Thread: " + t.getName());
                e.printStackTrace(w);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e); else System.exit(2);
        });
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { showIfCrashed(a); }
            public void onActivityStarted(Activity a) { } public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { } public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { } public void onActivityDestroyed(Activity a) { }
        });
    }

    static void showIfCrashed(Activity a) {
        File f = log(a);
        if (!f.isFile()) return;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) { String l; while ((l = r.readLine()) != null) sb.append(l).append('\n'); } catch (Throwable ignored) { }
        f.delete();
        final String text = sb.toString();
        TextView tv = new TextView(a); tv.setText(text); tv.setTextIsSelectable(true); tv.setTextSize(11); tv.setPadding(24, 24, 24, 24);
        ScrollView sv = new ScrollView(a); sv.addView(tv);
        new android.app.AlertDialog.Builder(a).setTitle("Last crash report").setView(sv)
            .setPositiveButton("Share", (d, w) -> a.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share crash report")))
            .setNegativeButton("Close", null).show();
    }
}
