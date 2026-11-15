package android.content;
import java.util.*;
import android.net.Uri;
public class Intent {
 public String action, target; public Uri data;
 public Map<String,Object> extras = new HashMap<>();
 public Intent(String action) { this.action=action; }
 public Intent setPackage(String name) { target=name; return this; }
 public Intent setData(Uri uri) { data=uri; return this; }
 public Intent putExtra(String key,String value) { extras.put(key,value); return this; }
 public Intent putStringArrayListExtra(String key,ArrayList<String> value) { extras.put(key,value); return this; }
}
