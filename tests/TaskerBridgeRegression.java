package app.lastfuck;
import java.util.*;
import org.json.*;
import android.content.*;
import android.content.pm.PackageManager;
public final class TaskerBridgeRegression {
 static class Phone extends Context {
  String installed="net.dinglisch.android.taskerm"; boolean permitted=true, receiver=true; Intent sent;
  public SharedPreferences getSharedPreferences(String name,int mode) { return (k,f) -> "Test\nOther"; }
  public int checkSelfPermission(String p) { return permitted?0:-1; }
  public void sendBroadcast(Intent intent) { sent=intent; }
  public PackageManager getPackageManager() { return new PackageManager() {
   public Object getPackageInfo(String name,int flags) throws NameNotFoundException { if (!name.equals(installed)) throw new NameNotFoundException(); return new Object(); }
   public List<Object> queryBroadcastReceivers(Intent i,int flags) { return receiver?Arrays.asList(new Object()):Collections.emptyList(); }
  }; }
 }
 static void check(boolean pass,String name) { if(!pass)throw new AssertionError(name); System.out.println("PASS "+name); }
 public static void main(String[] args)throws Exception {
  Phone p=new Phone();
  JSONObject r=TaskerBridge.dispatch(p,new JSONObject().put("task","Other").put("par1","hello").put("par2","world"));
  check(p.sent.target.equals(p.installed),"dispatch targets installed Play Store Tasker");
  check(p.sent.action.equals("net.dinglisch.android.tasker.ACTION_TASK"),"Tasker action uses official constant");
  check(p.sent.extras.get("task_name").equals("Other"),"dispatch preserves selected task name");
  check(p.sent.extras.get("varNames").equals(Arrays.asList("%par1","%par2")) && p.sent.extras.get("varValues").equals(Arrays.asList("hello","world")),"parameters use official TaskerIntent wire format");
  check(!r.getBoolean("complete"),"broadcast dispatch is not reported as task completion");
  p.installed="net.dinglisch.android.tasker";
  TaskerBridge.dispatch(p);
  check(p.sent.target.equals(p.installed),"direct-purchase Tasker is supported");
  p.permitted=false; p.sent=null;
  boolean blocked=false;try{TaskerBridge.dispatch(p);}catch(IllegalStateException e){blocked=true;}
  check(blocked && p.sent==null,"missing Android permission prevents dispatch");
  p.permitted=true;p.receiver=false;
  blocked=false;try{TaskerBridge.dispatch(p);}catch(IllegalStateException e){blocked=true;}
  check(blocked && p.sent==null,"missing receiver prevents false success");
  System.out.println("8 Tasker bridge checks passed (host Android doubles; no phone dispatch)");
 }
}
