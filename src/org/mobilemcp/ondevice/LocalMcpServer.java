package org.mobilemcp.ondevice;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dependency-free local JSON-RPC/Streamable HTTP (stateless, JSON response) MCP endpoint.
 *  Binds 127.0.0.1 by default; the owner can opt in to remote (all interfaces) binding. Bearer token always required.
 */
final class LocalMcpServer implements AutoCloseable {
    static final int PORT = 8765;
    private static final int MAX_BODY = 1024 * 1024;
    private static final String PROTOCOL = "2025-03-26";
    private final ControlService service;
    private final byte[] bearer;
    private final ExecutorService clients = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptor;

    private final boolean remote;
    LocalMcpServer(ControlService service,String secret,boolean remote) {
        this.service=service; this.remote=remote; this.bearer=("Bearer "+secret).getBytes(StandardCharsets.UTF_8);
    }
    synchronized void start() throws Exception {
        if(running) return;
        server=new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(remote?"0.0.0.0":"127.0.0.1", PORT),16);
        running=true;
        acceptor=new Thread(() -> {
            while(running) {
                try {
                    Socket s=server.accept();
                    clients.execute(() -> handle(s));
                } catch(Exception e) { if(running) android.util.Log.w("MobileMCP","Accept error",e); }
            }
        },"mobile-mcp-http");
        acceptor.setDaemon(true); acceptor.start();
    }
    boolean isRemote() { return remote; }
    boolean isRunning() { return running && server != null && !server.isClosed(); }
    @Override public synchronized void close() {
        running=false;
        try { if(server!=null) server.close(); } catch(Exception ignored) { }
        clients.shutdownNow();
    }

    private static String readline(InputStream in, int max) throws Exception {
        ByteArrayOutputStream line=new ByteArrayOutputStream();
        while(line.size()<max) {
            int c=in.read();
            if(c==-1) return line.size()==0?null:line.toString("UTF-8");
            if(c=='\n') return line.toString("UTF-8").replaceAll("\r$", "");
            line.write(c);
        }
        throw new IllegalArgumentException("HTTP header line too large");
    }
    private static void respond(OutputStream out, int status, String type, String body) throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        String phrase=status==200?"OK":status==202?"Accepted":status==400?"Bad Request":status==401?"Unauthorized":status==404?"Not Found":status==405?"Method Not Allowed":status==411?"Length Required":status==413?"Payload Too Large":"Error";
        String header="HTTP/1.1 "+status+" "+phrase+"\r\n"+
                "Content-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\n"+
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
    private void handle(Socket socket) {
        try(Socket s=socket) {
            s.setSoTimeout(15000);
            InputStream input=new BufferedInputStream(s.getInputStream());
            OutputStream output=new BufferedOutputStream(s.getOutputStream());
            String line=readline(input,4096);
            if(line==null) return;
            String[] path=line.split(" ");
            if(path.length<2) { respond(output,400,"text/plain","Invalid request line"); return; }
            Map<String,String> headers=new HashMap<>();
            int total=0;
            while(true) {
                String h=readline(input,8192);
                if(h==null) return;
                total+=h.length();
                if(total>32768) { respond(output,413,"text/plain","Headers too large");return; }
                if(h.isEmpty()) break;
                int colon=h.indexOf(':');
                if(colon>0) headers.put(h.substring(0,colon).trim().toLowerCase(java.util.Locale.ROOT),h.substring(colon+1).trim());
            }
            if(path[0].equals("GET") && path[1].equals("/health")) {
                respond(output,200,"application/json","{\"status\":\"running\",\"binding\":\""+(remote?"0.0.0.0":"127.0.0.1")+"\"}");return;
            }
            if(!path[1].equals("/mcp")) { respond(output,404,"text/plain","Not found");return; }
            if(!path[0].equals("POST")) { respond(output,405,"text/plain","Use POST /mcp with Streamable HTTP");return; }
            String auth=headers.getOrDefault("authorization","");
            if(!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),bearer)) {
                respond(output,401,"application/json","{\"error\":\"Bearer token required\"}");return;
            }
            if(headers.containsKey("transfer-encoding")) { respond(output,411,"text/plain","Chunked transfer encoding not supported");return; }
            String size=headers.get("content-length");
            if(size==null) { respond(output,411,"text/plain","Content-Length required");return; }
            int length;
            try { length=Integer.parseInt(size); } catch(NumberFormatException e) { respond(output,400,"text/plain","Invalid length");return; }
            if(length<0 || length>MAX_BODY) { respond(output,413,"text/plain","Body limit 1 MiB");return; }
            byte[] body=new byte[length]; int count=0;
            while(count<length) { int n=input.read(body,count,length-count); if(n<0) break; count+=n; }
            if(count!=length) { respond(output,400,"text/plain","Truncated body");return; }
            JSONObject request;
            try { request=new JSONObject(new String(body,StandardCharsets.UTF_8)); }
            catch(Exception e) { respond(output,400,"application/json","{\"error\":\"Invalid JSON\"}");return; }
            Object id=request.opt("id");
            if(id==null || id==JSONObject.NULL) { respond(output,202,"application/json","");return; }
            JSONObject response=new JSONObject();
            response.put("jsonrpc","2.0").put("id",id);
            try { response.put("result",dispatch(request)); }
            catch(Exception e) { response.put("error",new JSONObject().put("code",-32603).put("message",e.getMessage()==null?"Internal error":e.getMessage())); }
            respond(output,200,"application/json",response.toString());
        } catch(Exception e) { android.util.Log.w("MobileMCP","Client disconnected or request failed: " + e.getClass().getSimpleName()); }
    }

    private JSONObject dispatch(JSONObject request) throws Exception {
        String method=request.optString("method","");
        switch(method) {
            case "initialize":
                return new JSONObject().put("protocolVersion",PROTOCOL)
                        .put("capabilities",new JSONObject().put("tools",new JSONObject().put("listChanged",false)))
                        .put("serverInfo",new JSONObject().put("name","mobile-mcp-android-native").put("version","0.1.0"))
                        .put("instructions","One local Android device with id local-android. This is a subset of upstream Mobile MCP, not an iOS or ADB bridge.");
            case "ping": return new JSONObject();
            case "tools/list": return new JSONObject().put("tools",toolList());
            case "tools/call": {
                JSONObject p=request.optJSONObject("params");
                if(p==null) throw new IllegalArgumentException("Missing params");
                String name=p.getString("name");
                JSONObject args=p.optJSONObject("arguments");
                if(args==null) args=new JSONObject();
                try {
                    JSONObject value=service.execute(name,args);
                    if(name.equals("mobile_take_screenshot")) {
                        JSONObject image=new JSONObject().put("type","image")
                                .put("mimeType",value.getString("mimeType")).put("data",value.getString("data"));
                        return new JSONObject().put("content",new JSONArray().put(image));
                    }
                    String text=value.has("message")?value.getString("message"):value.toString();
                    return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));
                } catch(Exception e) {
                    return new JSONObject().put("isError",true).put("content",new JSONArray().put(
                            new JSONObject().put("type","text").put("text",e.getMessage()==null?"Tool failed":e.getMessage())));
                }
            }
            default: throw new IllegalArgumentException("MCP method not supported: "+method);
        }
    }
    private static JSONObject property(String description, String type) throws Exception {
        return new JSONObject().put("type",type).put("description",description);
    }
    private static JSONObject schema(String[] keys,String[] types,String[] descriptions, String... required) throws Exception {
        JSONObject properties=new JSONObject();
        for(int i=0;i<keys.length;i++) properties.put(keys[i],property(descriptions[i],types[i]));
        JSONArray needed=new JSONArray(); for(String name:required) needed.put(name);
        return new JSONObject().put("type","object").put("properties",properties).put("required",needed).put("additionalProperties",false);
    }
    private static void add(JSONArray a,String name,String description,JSONObject schema,boolean readOnly) throws Exception {
        a.put(new JSONObject().put("name",name).put("description",description).put("inputSchema",schema)
                .put("annotations",new JSONObject().put("readOnlyHint",readOnly)));
    }
    private static JSONObject deviceOnly() throws Exception { return schema(new String[]{"device"},new String[]{"string"},new String[]{"Use local-android"},"device"); }
    private static JSONObject deviceAnd(String key,String type,String desc) throws Exception {
        return schema(new String[]{"device",key},new String[]{"string",type},new String[]{"Use local-android",desc},"device",key);
    }
    private static JSONArray toolList() throws Exception {
        JSONArray a=new JSONArray();
        add(a,"mobile_get_capabilities","Check device-control capabilities, workspace selection and platform limits",deviceOnly(),true);
        add(a,"mobile_create_directory","Create a directory inside the selected workspace; parent must exist",
                deviceAnd("path","string","Relative directory path"),false);
        add(a,"mobile_delete_file","Delete one file in the selected workspace; cannot delete directories; requires confirm=true",
                schema(new String[]{"device","path","confirm"},new String[]{"string","string","boolean"},new String[]{"local-android","Relative file path","Must be true to delete"},"device","path","confirm"),false);
        add(a,"mobile_list_files","List files in the owner-selected workspace folder (up to 500 entries)",
                schema(new String[]{"device","path"},new String[]{"string","string"},new String[]{"local-android","Relative folder path; empty for workspace root"},"device"),true);
        add(a,"mobile_read_file","Read a UTF-8 text file in the selected workspace (256 KiB maximum)",
                schema(new String[]{"device","path"},new String[]{"string","string"},new String[]{"local-android","Relative file path"},"device","path"),true);
        add(a,"mobile_write_file","Create a UTF-8 text file in the selected workspace; replacing an existing file requires overwrite=true (256 KiB maximum)",
                schema(new String[]{"device","path","text","overwrite"},new String[]{"string","string","string","boolean"},new String[]{"local-android","Relative file path; parent folders must exist","UTF-8 contents","Explicitly replace an existing file; default false"},"device","path","text"),false);
        add(a,"mobile_list_sensors","Discover available Android hardware sensors",deviceOnly(),true);
        add(a,"mobile_read_sensor","Read one current sample from Android hardware sensor type",
                schema(new String[]{"device","type"},new String[]{"string","integer"},new String[]{"local-android","Android Sensor.TYPE_* integer from mobile_list_sensors"},"device","type"),true);
        add(a,"mobile_list_available_devices","List the local Android device",schema(new String[]{},new String[]{},new String[]{}),true);
        add(a,"mobile_get_screen_size","Get local Android display dimensions",deviceOnly(),true);
        add(a,"mobile_get_orientation","Get portrait or landscape",deviceOnly(),true);
        add(a,"mobile_get_foreground_app","Get foreground app package name; activity class unavailable",deviceOnly(),true);
        add(a,"mobile_list_apps","List launchable apps installed on device",deviceOnly(),true);
        add(a,"mobile_launch_app","Launch installed app",deviceAnd("packageName","string","Android package name"),false);
        add(a,"mobile_list_elements_on_screen","List accessible elements with ephemeral @e refs (format=text or json)",
                schema(new String[]{"device","format"},new String[]{"string","string"},new String[]{"local-android","text or json"},"device"),true);
        add(a,"mobile_click_on_screen_at_coordinates","Tap by @e ref or x and y coordinates",
                schema(new String[]{"device","ref","x","y"},new String[]{"string","string","number","number"},new String[]{"local-android","Element ref from last list","Screen x","Screen y"},"device"),false);
        add(a,"mobile_double_tap_on_screen","Double tap using ref or screen coordinates",
                schema(new String[]{"device","ref","x","y"},new String[]{"string","string","number","number"},new String[]{"local-android","Element ref","Screen x","Screen y"},"device"),false);
        add(a,"mobile_long_press_on_screen_at_coordinates","Long press by ref or x,y",
                schema(new String[]{"device","ref","x","y","duration"},new String[]{"string","string","number","number","number"},new String[]{"local-android","Element ref","Screen x","Screen y","Milliseconds"},"device"),false);
        add(a,"mobile_swipe_on_screen","Swipe up, down, left or right",
                schema(new String[]{"device","direction","x","y","duration"},new String[]{"string","string","number","number","number"},new String[]{"local-android","up/down/left/right","Start x (optional with y)","Start y (optional with x)","Milliseconds"},"device","direction"),false);
        add(a,"mobile_type_keys","Set text in the focused editable field",
                schema(new String[]{"device","text","submit","ref"},new String[]{"string","string","boolean","string"},new String[]{"local-android","Text to enter","Submit after typing (depends on app)","Optional editable element ref to focus before typing"},"device","text"),false);
        add(a,"mobile_press_button","Android navigation, notifications, quick settings, or power dialog",
                deviceAnd("button","string","BACK/HOME/APP_SWITCH/NOTIFICATIONS/QUICK_SETTINGS/POWER_DIALOG"),false);
        add(a,"mobile_open_url","Open a safe web URL on the device",deviceAnd("url","string","http:// or https:// URL"),false);
        add(a,"mobile_take_screenshot","Capture device screen (Android 11+); returns inline image",
                schema(new String[]{"device","maxSize"},new String[]{"string","number"},new String[]{"local-android","Maximum image edge, 200-1600 pixels"},"device"),true);
        return a;
    }
}
