package android.content.pm;
import java.util.*;
import android.content.Intent;
public abstract class PackageManager {
 public static final int PERMISSION_GRANTED=0;
 public static class NameNotFoundException extends Exception {}
 public abstract Object getPackageInfo(String name,int flags) throws NameNotFoundException;
 public abstract List<Object> queryBroadcastReceivers(Intent intent,int flags);
}
