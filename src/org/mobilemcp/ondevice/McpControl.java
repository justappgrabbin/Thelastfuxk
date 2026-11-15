package org.mobilemcp.ondevice;

import android.content.Context;
import android.net.Uri;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public facade so The Last Fuck's UI can drive the merged Android-mcp server. */
public final class McpControl {
    private McpControl() { }
    private static final String PREFS = "mcp_settings";

    static boolean remote(Context c) { return c.getSharedPreferences(PREFS, 0).getBoolean("remote", false); }

    public static void setRemote(Context c, boolean on) throws Exception {
        c.getSharedPreferences(PREFS, 0).edit().putBoolean("remote", on).commit();
        ControlService svc = ControlService.active();
        if (svc != null && svc.isServerRunning()) svc.startServer(); // rebinds with the new setting
    }
    public static void start() throws Exception {
        ControlService svc = ControlService.active();
        if (svc == null) throw new IllegalStateException("Turn on 'The Last Fuck · MCP controller' in Accessibility settings first");
        svc.startServer();
    }
    public static void stop() { ControlService svc = ControlService.active(); if (svc != null) svc.stopServer(); }
    public static void rotate(Context c) { stop(); Auth.rotate(c); }
    public static void selectWorkspace(Context c, Uri tree) { WorkspaceTools.select(c, tree); }
    public static void forgetWorkspace(Context c) { WorkspaceTools.forget(c); }

    public static JSONObject status(Context c) throws Exception {
        ControlService svc = ControlService.active();
        boolean running = svc != null && svc.isServerRunning();
        boolean remote = remote(c);
        JSONArray urls = new JSONArray().put("http://127.0.0.1:" + LocalMcpServer.PORT + "/mcp");
        if (remote) for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!n.isUp() || n.isLoopback()) continue;
            for (InetAddress a : Collections.list(n.getInetAddresses()))
                if (a instanceof Inet4Address) urls.put("http://" + a.getHostAddress() + ":" + LocalMcpServer.PORT + "/mcp");
        }
        return new JSONObject().put("accessibility", svc != null).put("running", running)
            .put("remote", remote).put("boundRemote", running && svc.isServerRemote())
            .put("urls", urls).put("token", Auth.get(c))
            .put("workspace", WorkspaceTools.selected(c) != null);
    }
}
