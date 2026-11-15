package android.content;
import android.content.pm.PackageManager;
public abstract class Context {
 public abstract SharedPreferences getSharedPreferences(String name, int mode);
 public abstract PackageManager getPackageManager();
 public abstract int checkSelfPermission(String permission);
 public abstract void sendBroadcast(Intent intent);
}
