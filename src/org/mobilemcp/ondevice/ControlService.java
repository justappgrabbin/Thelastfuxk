package org.mobilemcp.ondevice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Base64;
import android.util.DisplayMetrics;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Android platform implementation. Only the currently running device is exposed. */
public final class ControlService extends AccessibilityService {
    private static volatile ControlService INSTANCE;
    private LocalMcpServer server;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Rect> lastRefs = new HashMap<>();

    static ControlService active() { return INSTANCE; }
    @Override protected void onServiceConnected() { super.onServiceConnected(); INSTANCE = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* no events stored or transmitted */ }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { stopServer(); if (INSTANCE == this) INSTANCE = null; super.onDestroy(); }

    synchronized void startServer() throws Exception {
        boolean remote = McpControl.remote(this);
        if (server != null && server.isRunning() && server.isRemote() == remote) return;
        stopServer();
        server = new LocalMcpServer(this, Auth.get(this), remote);
        server.start();
    }
    synchronized void stopServer() { if (server != null) { server.close(); server = null; } }
    synchronized boolean isServerRemote() { return server != null && server.isRemote(); }
    synchronized boolean isServerRunning() { return server != null && server.isRunning(); }

    private <T> T onUi(java.util.concurrent.Callable<T> command) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return command.call();
        FutureTask<T> future = new FutureTask<>(command);
        main.post(future);
        return future.get(8, TimeUnit.SECONDS);
    }

    JSONObject deviceInfo() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", "local-android"); obj.put("name", Build.MANUFACTURER + " " + Build.MODEL);
        obj.put("platform", "android"); obj.put("type", "real");
        obj.put("version", Build.VERSION.RELEASE); obj.put("state", "online");
        return obj;
    }
    private void checkDevice(JSONObject args) throws Exception {
        String device = args.optString("device", "local-android");
        if (!device.equals("local-android")) throw new IllegalArgumentException("Only local-android is available in this APK");
    }
    private JSONObject dimensions() throws Exception {
        DisplayMetrics d = getResources().getDisplayMetrics();
        return new JSONObject().put("width",d.widthPixels).put("height",d.heightPixels);
    }
    private boolean gesture(int x1,int y1,int x2,int y2,long duration) {
        Path path = new Path(); path.moveTo(x1,y1);
        if (x1!=x2 || y1!=y2) path.lineTo(x2,y2);
        GestureDescription desc = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
        return dispatchGesture(desc,null,null);
    }
    private boolean clickRef(String ref) throws Exception {
        Rect bounds = lastRefs.get(ref);
        if (bounds == null) throw new IllegalArgumentException("Unknown or expired ref; list screen elements again");
        return gesture(bounds.centerX(),bounds.centerY(),bounds.centerX(),bounds.centerY(),90);
    }
    private void walk(AccessibilityNodeInfo node, JSONArray out, StringBuilder lines, int depth, int[] counter) throws Exception {
        if (node == null || depth > 20 || counter[0] >= 350) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && node.isVisibleToUser()) {
            String ref = "@e" + (++counter[0]);
            lastRefs.put(ref, new Rect(bounds));
            JSONObject item = new JSONObject();
            item.put("ref",ref).put("x",bounds.centerX()).put("y",bounds.centerY());
            item.put("bounds",new JSONObject().put("left",bounds.left).put("top",bounds.top).put("right",bounds.right).put("bottom",bounds.bottom));
            if (node.getClassName() != null) item.put("class",node.getClassName().toString());
            if (node.getViewIdResourceName() != null) item.put("id",node.getViewIdResourceName());
            if (!node.isPassword() && node.getText() != null) item.put("text",node.getText().toString());
            if (node.getContentDescription() != null) item.put("label",node.getContentDescription().toString());
            item.put("clickable",node.isClickable()); item.put("editable",node.isEditable());
            out.put(item);
            lines.append(ref).append(' ').append(bounds.centerX()).append(',').append(bounds.centerY());
            if (item.has("text")) lines.append(" text=").append(item.optString("text"));
            if (item.has("label")) lines.append(" label=").append(item.optString("label"));
            lines.append('\n');
        }
        for (int i=0; i<node.getChildCount() && counter[0]<350;i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) walk(child,out,lines,depth+1,counter);
        }
    }

    /** Screen screenshot is generated on-device, never sent to a cloud provider. */
    private JSONObject screenshot(JSONObject args) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new IllegalStateException("Screenshots need Android 11 or newer");
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        main.post(() -> takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult image) {
                try {
                    HardwareBuffer buffer = image.getHardwareBuffer();
                    ColorSpace cs = image.getColorSpace() == null ? ColorSpace.get(ColorSpace.Named.SRGB) : image.getColorSpace();
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer,cs);
                    if (wrapped == null) throw new IllegalStateException("Unable to read screenshot");
                    int max = Math.min(1600, Math.max(200, args.optInt("maxSize",1024)));
                    float scale = Math.min(1f, max / (float)Math.max(wrapped.getWidth(),wrapped.getHeight()));
                    Bitmap bitmap = wrapped.copy(Bitmap.Config.ARGB_8888,false);
                    buffer.close();
                    if (scale < 1f) {
                        Bitmap resized = Bitmap.createScaledBitmap(bitmap, Math.max(1,(int)(bitmap.getWidth()*scale)), Math.max(1,(int)(bitmap.getHeight()*scale)), true);
                        bitmap.recycle(); bitmap = resized;
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, bytes);
                    bitmap.recycle();
                    result.complete(new JSONObject().put("mimeType","image/png").put("data",Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)));
                } catch (Exception e) { result.completeExceptionally(e); }
            }
            @Override public void onFailure(int code) { result.completeExceptionally(new IllegalStateException("Android screenshot failure: " + code)); }
        }));
        return result.get(12,TimeUnit.SECONDS);
    }

    /** Return a tool result; all device operations are initiated by authenticated client calls. */
    JSONObject execute(String name,JSONObject args) throws Exception {
        if (!name.equals("mobile_list_available_devices")) checkDevice(args);
        switch (name) {
            case "mobile_get_capabilities": return new JSONObject()
                    .put("device", "local-android")
                    .put("accessibilityEnabled", true)
                    .put("gestures", (getServiceInfo().getCapabilities() &
                            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0)
                    .put("screenshotApiAvailable", Build.VERSION.SDK_INT >= 30)
                    .put("workspaceSelected", WorkspaceTools.selected(this) != null)
                    .put("textFileLimitBytes", 256 * 1024)
                    .put("limitations", "Selected folder only; provider may revoke access. No root, ADB shell, other apps' private files, or GitHub credentials. Secure screens may block screenshots.");
            case "mobile_list_files":
            case "mobile_create_directory":
            case "mobile_delete_file":
            case "mobile_read_file":
            case "mobile_write_file": return WorkspaceTools.execute(this, name, args);
            case "mobile_list_sensors": return SensorTools.list(this);
            case "mobile_read_sensor": return SensorTools.read(this,args);
            case "mobile_list_available_devices": return new JSONObject().put("devices",new JSONArray().put(deviceInfo()));
            case "mobile_get_screen_size": {
                JSONObject d = dimensions();
                return new JSONObject().put("message","Screen size is " + d.getInt("width") + "x" + d.getInt("height") + " pixels");
            }
            case "mobile_get_orientation": {
                JSONObject d=dimensions(); return new JSONObject().put("message", "Current device orientation is " + (d.getInt("width")>d.getInt("height")?"landscape":"portrait"));
            }
            case "mobile_get_foreground_app": return onUi(() -> {
                AccessibilityNodeInfo root=getRootInActiveWindow();
                return new JSONObject().put("packageName",root==null?JSONObject.NULL:String.valueOf(root.getPackageName()))
                        .put("activity","not available via AccessibilityService");
            });
            case "mobile_list_elements_on_screen": return onUi(() -> {
                AccessibilityNodeInfo root=getRootInActiveWindow();
                if (root==null) throw new IllegalStateException("No accessible UI hierarchy (device might be locked)");
                lastRefs.clear(); JSONArray arr=new JSONArray(); StringBuilder lines = new StringBuilder();
                walk(root,arr,lines,0,new int[]{0});
                String format=args.optString("format","text");
                if ("json".equals(format)) return new JSONObject().put("elements",arr);
                if ("raw".equals(format)) throw new IllegalArgumentException("raw platform hierarchy is not supported by this native port; use json");
                return new JSONObject().put("message",lines.toString());
            });
            case "mobile_click_on_screen_at_coordinates": return onUi(() -> {
                boolean ok;
                if (args.has("ref")) ok=clickRef(args.getString("ref"));
                else ok=gesture(args.getInt("x"),args.getInt("y"),args.getInt("x"),args.getInt("y"),90);
                if (!ok) throw new IllegalStateException("Android rejected gesture");
                return new JSONObject().put("message","Tap gesture submitted");
            });
            case "mobile_double_tap_on_screen": return onUi(() -> {
                int x,y;
                if (args.has("ref")) { Rect r=lastRefs.get(args.getString("ref")); if(r==null) throw new IllegalArgumentException("Ref missing; relist elements"); x=r.centerX(); y=r.centerY(); }
                else { x=args.getInt("x"); y=args.getInt("y"); }
                if(!gesture(x,y,x,y,75)) throw new IllegalStateException("Android rejected first tap");
                main.postDelayed(() -> gesture(x,y,x,y,75),150);
                return new JSONObject().put("message","Double-tap gestures submitted");
            });
            case "mobile_long_press_on_screen_at_coordinates": return onUi(() -> {
                int x,y;
                if (args.has("ref")) { Rect r=lastRefs.get(args.getString("ref")); if(r==null) throw new IllegalArgumentException("Ref missing; relist elements"); x=r.centerX(); y=r.centerY(); }
                else { x=args.getInt("x"); y=args.getInt("y"); }
                long ms=Math.min(10000,Math.max(1,args.optLong("duration",500)));
                if(!gesture(x,y,x,y,ms)) throw new IllegalStateException("Android rejected long press");
                return new JSONObject().put("message","Long press submitted");
            });
            case "mobile_swipe_on_screen": return onUi(() -> {
                DisplayMetrics d=getResources().getDisplayMetrics();
                String dir=args.getString("direction");
                int x=args.has("x")?args.getInt("x"):d.widthPixels/2;
                int y=args.has("y")?args.getInt("y"):d.heightPixels/2;
                if (args.has("x")!=args.has("y")) throw new IllegalArgumentException("x and y must both be given or both omitted");
                int distance=Math.max(80,Math.min(d.widthPixels,d.heightPixels)/3), ex=x, ey=y;
                switch(dir) { case "up":ey-=distance;break;case "down":ey+=distance;break;case "left":ex-=distance;break;case "right":ex+=distance;break;default:throw new IllegalArgumentException("direction must be up/down/left/right"); }
                ex=Math.max(0,Math.min(d.widthPixels-1,ex));ey=Math.max(0,Math.min(d.heightPixels-1,ey));
                if (!gesture(x,y,ex,ey,Math.max(100,Math.min(3000,args.optLong("duration",400))))) throw new IllegalStateException("Android rejected swipe");
                return new JSONObject().put("message","Swipe submitted: " + dir);
            });
            case "mobile_type_keys": return onUi(() -> {
                AccessibilityNodeInfo root=getRootInActiveWindow();
                if (args.has("ref")) {
                    Rect bounds = lastRefs.get(args.getString("ref"));
                    if (bounds == null) throw new IllegalArgumentException("Unknown ref; list elements again");
                    AccessibilityNodeInfo target = findEditable(root, bounds, 0);
                    if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                        throw new IllegalStateException("Ref is not a focusable editable field");
                }
                AccessibilityNodeInfo focused=root==null?null:root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused==null || !focused.isEditable()) throw new IllegalStateException("No focused editable text field");
                Bundle bundle=new Bundle(); bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text"));
                if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,bundle)) throw new IllegalStateException("Text input rejected by app");
                if(args.optBoolean("submit",false)) {
                    if (Build.VERSION.SDK_INT < 30) throw new IllegalArgumentException("submit requires Android 11+ in this port");
                    if (!focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()))
                        throw new IllegalStateException("Text was set but the app rejected submit");
                }
                return new JSONObject().put("message","Text set in focused field");
            });
            case "mobile_press_button": return onUi(() -> {
                String button=args.getString("button").toUpperCase(java.util.Locale.ROOT);
                int action;
                switch(button) { case "BACK":action=GLOBAL_ACTION_BACK;break;case "HOME":action=GLOBAL_ACTION_HOME;break;case "APP_SWITCH":action=GLOBAL_ACTION_RECENTS;break;case "NOTIFICATIONS":action=GLOBAL_ACTION_NOTIFICATIONS;break;case "QUICK_SETTINGS":action=GLOBAL_ACTION_QUICK_SETTINGS;break;case "POWER_DIALOG":action=GLOBAL_ACTION_POWER_DIALOG;break;default:throw new IllegalArgumentException("Supported: BACK, HOME, APP_SWITCH, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG"); }
                if(!performGlobalAction(action)) throw new IllegalStateException("Global action rejected");
                return new JSONObject().put("message","Button pressed: "+button);
            });
            case "mobile_open_url": return onUi(() -> {
                String url=args.getString("url");
                if (!(url.startsWith("https://") || url.startsWith("http://"))) throw new IllegalArgumentException("Only http:// and https:// accepted");
                Intent intent=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return new JSONObject().put("message","Opened URL");
            });
            case "mobile_list_apps": return onUi(() -> {
                JSONArray apps=new JSONArray();
                List<ApplicationInfo> installed=getPackageManager().getInstalledApplications(0);
                for(ApplicationInfo info:installed) {
                    if(getPackageManager().getLaunchIntentForPackage(info.packageName)==null) continue;
                    apps.put(new JSONObject().put("packageName",info.packageName).put("name",getPackageManager().getApplicationLabel(info).toString()));
                    if(apps.length()>=400) break;
                }
                return new JSONObject().put("apps",apps);
            });
            case "mobile_launch_app": return onUi(() -> {
                String pkg=args.getString("packageName");
                Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);
                if(launch==null) throw new IllegalArgumentException("Package missing or has no launch activity: "+pkg);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch);
                return new JSONObject().put("message","Launched app "+pkg);
            });
            case "mobile_take_screenshot": return screenshot(args);
            default: throw new IllegalArgumentException("Tool not supported by this on-device port: "+name);
        }
    }
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, Rect bounds, int depth) {
        if (node == null || depth > 20) return null;
        Rect actual = new Rect();
        node.getBoundsInScreen(actual);
        if (node.isEditable() && actual.equals(bounds)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i), bounds, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
