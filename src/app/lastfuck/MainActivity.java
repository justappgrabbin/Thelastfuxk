package app.lastfuck;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView history;
    private TextView reply;
    private TextView consoleStatus;
    private EditText task;
    private TaskQueue queue;
    private SharedPreferences settings;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (web != null) {
                web.evaluateJavascript("window.refreshCynthia && window.refreshCynthia()",null);
                handler.postDelayed(this,1500);
                return;
            }
            if (status == null) { handler.postDelayed(this,1500); return; }
            status.setText(AgentService.status);
            String latestReply = AgentService.reply;
            if (!latestReply.isEmpty() && !latestReply.contentEquals(reply.getText())) reply.setText(latestReply);
            consoleStatus.setText(AgentService.manualStatus);
            try {
                String latest = queue.history();
                if (!latest.contentEquals(history.getText())) history.setText(latest);
            } catch (Exception e) {
                history.setText("Could not read task history · " + e.getMessage());
            }
            handler.postDelayed(this, 1500);
        }
    };

    private android.webkit.WebView web;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        queue = new TaskQueue(this);
        settings = getSharedPreferences("agent", 0);
        if (state != null) exportPath = state.getString("exportPath");
        getWindow().setStatusBarColor(Color.rgb(17,20,28));
        getWindow().setNavigationBarColor(Color.rgb(17,20,28));
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        web = new android.webkit.WebView(this);
        web.setBackgroundColor(Color.rgb(17,20,28));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.addJavascriptInterface(new DesktopBridge(), "Cynthia");
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) { return true; }
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                String scheme=request.getUrl().getScheme();
                if ("https".equals(scheme)||"http".equals(scheme)||"file".equals(scheme)||"content".equals(scheme))
                    return new android.webkit.WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));
                return null;
            }
        });
        setContentView(web);
        try {
            String html=RuntimeFiles.read(getAssets().open("computer.html"),512*1024);
            web.loadDataWithBaseURL("https://cynthia.local/", html, "text/html", "UTF-8", null);
        } catch(Exception e) { showClassic(state); }
        startAgent();
    }

    public final class DesktopBridge {
        private String error(Exception e) { try { return new org.json.JSONObject().put("error",String.valueOf(e.getMessage())).toString(); } catch(Exception ignored) { return "{}"; } }
        @android.webkit.JavascriptInterface public String state() {
            try {
                org.json.JSONObject value=new org.json.JSONObject().put("status",AgentService.status)
                    .put("running",AgentService.running).put("computer",AgentService.computerStatus)
                    .put("autonomy",settings.getBoolean("autonomy",false))
                    .put("autonomyGoal",settings.getString("autonomy_goal",""))
                    .put("autonomyStatus",settings.getString("autonomy_status","No goal saved"))
                    .put("autonomyCycles",settings.getInt("autonomy_cycles",0))
                    .put("autonomyLimit",AutonomyPolicy.MAX_CYCLES)
                    .put("review",ActionReview.pending)
                    .put("plan",new org.json.JSONObject(AgentService.plan))
                    .put("weights",LocalCoder.weightsStatus).put("weightsPercent",LocalCoder.weightsPercent)
                    .put("weightsReady",LocalCoder.weightsReady)
                    .put("lastOutput",AgentService.lastOutput)
                    .put("outputs",outputsJson()).put("klein",AgentService.kleinStatus)
                    .put("manual",AgentService.manualStatus).put("history",queue.history())
                    .put("messages",new ConversationLog(getFilesDir()).tail(100));
                return value.toString();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String send(String text, String mode) {
            try {
                String kind="agent".equals(mode)?"agent":"chat".equals(mode)?"chat":"assistant";
                String id=queue.enqueue(kind,text);
                runOnUiThread(()->{ if(!AgentService.running) startAgent(); });
                return new org.json.JSONObject().put("id",id).toString();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String files(String path) {
            try { return new WorkspaceBrowser(new java.io.File(getFilesDir(),"workspace")).list(path).toString(); }
            catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String preview(String path) {
            try { return new WorkspaceBrowser(new java.io.File(getFilesDir(),"workspace")).preview(path).toString(); }
            catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String terminal(String command, boolean androidShell) {
            try {
                String id=queue.enqueue(androidShell?"android-shell":"shell",command);
                runOnUiThread(()->{ if(!AgentService.running) startAgent(); });
                return new org.json.JSONObject().put("id",id).toString();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public void autonomy(boolean enabled) {
            if (enabled && settings.getString("autonomy_goal", "").trim().isEmpty()) {
                settings.edit().putBoolean("autonomy", false)
                    .putString("autonomy_status", "Set a goal before enabling autonomy").commit();
                return;
            }
            settings.edit().putBoolean("autonomy", enabled)
                .putString("autonomy_status", enabled ? "Autonomy enabled · waiting for watcher"
                    : "Autonomy paused by user").commit();
            if (enabled) {
                runOnUiThread(() -> { if (!AgentService.running) startAgent(); });
            }
        }
        @android.webkit.JavascriptInterface public String saveGoal(String goal) {
            try {
                String clean = goal == null ? "" : goal.trim();
                if (clean.isEmpty() || clean.length() > 1200)
                    throw new IllegalArgumentException("Goal must contain 1–1200 characters");
                if (!clean.equals(settings.getString("autonomy_goal", ""))) {
                    settings.edit().putString("autonomy_goal", clean)
                        .putString("autonomy_session", java.util.UUID.randomUUID().toString())
                        .putBoolean("autonomy", false).putInt("autonomy_cycles", 0)
                        .putLong("autonomy_next_at", 0L).putString("autonomy_last", "")
                        .putString("autonomy_status", "Goal saved · enable autonomy to begin").commit();
                }
                return new org.json.JSONObject().put("saved", true)
                    .put("status", settings.getString("autonomy_status", "")).toString();
            } catch (Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String resetGoal() {
            try {
                if (settings.getString("autonomy_goal", "").trim().isEmpty())
                    throw new IllegalArgumentException("Save a goal first");
                settings.edit().putString("autonomy_session", java.util.UUID.randomUUID().toString())
                    .putBoolean("autonomy", false).putInt("autonomy_cycles", 0)
                    .putLong("autonomy_next_at", 0L).putString("autonomy_last", "")
                    .putString("autonomy_status", "Goal reset · enable autonomy to restart").commit();
                return new org.json.JSONObject().put("reset", true).toString();
            } catch (Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String settings() {
            try {
                return new org.json.JSONObject()
                    .put("kleinOn",settings.getBoolean("klein_on",true))
                    .put("kleinUrl",settings.getString("klein_url",KleinClient.DEFAULT_URL))
                    .put("kleinLocal",settings.getBoolean("klein_local",true)).put("kleinRuntime",KleinRuntime.status)
                    .put("kleinToken",!settings.getString("klein_token","").isEmpty())
                    .put("proactive",settings.getBoolean("qwen_proactive",true))
                    .put("mcp",org.mobilemcp.ondevice.McpControl.status(MainActivity.this)).toString();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String saveSettings(String json) {
            try {
                org.json.JSONObject v=new org.json.JSONObject(json);
                android.content.SharedPreferences.Editor e=settings.edit();
                if(v.has("kleinOn")) e.putBoolean("klein_on",v.getBoolean("kleinOn"));
                if(v.has("kleinUrl")) e.putString("klein_url",KleinClient.normalize(v.getString("kleinUrl")));
                if(v.has("kleinToken")) e.putString("klein_token",v.getString("kleinToken").trim());
                if(v.has("kleinLocal")) e.putBoolean("klein_local",v.getBoolean("kleinLocal"));
                if(v.has("proactive")) e.putBoolean("qwen_proactive",v.getBoolean("proactive"));
                e.commit();
                return settings();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String testKlein() {
            try {
                KleinClient.Reply r=KleinClient.chat(settings.getString("klein_url",KleinClient.DEFAULT_URL),"hello",settings.getString("klein_token",""));
                AgentService.kleinStatus="Klein connected · "+settings.getString("klein_url",KleinClient.DEFAULT_URL);
                return new org.json.JSONObject().put("ok",true).put("reply",r.text).toString();
            } catch(Exception e) { AgentService.kleinStatus="Klein unreachable · "+e.getMessage(); return error(e); }
        }
        @android.webkit.JavascriptInterface public String mcp(String action) {
            try {
                switch(action) {
                    case "start": org.mobilemcp.ondevice.McpControl.start(); break;
                    case "stop": org.mobilemcp.ondevice.McpControl.stop(); break;
                    case "remoteOn": org.mobilemcp.ondevice.McpControl.setRemote(MainActivity.this,true); break;
                    case "remoteOff": org.mobilemcp.ondevice.McpControl.setRemote(MainActivity.this,false); break;
                    case "rotate": org.mobilemcp.ondevice.McpControl.rotate(MainActivity.this); break;
                    case "forget": org.mobilemcp.ondevice.McpControl.forgetWorkspace(MainActivity.this); break;
                    case "folder": runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),MCP_FOLDER)); break;
                    case "accessibility": runOnUiThread(()->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))); break;
                    default: throw new IllegalArgumentException("Unknown MCP action");
                }
                return org.mobilemcp.ondevice.McpControl.status(MainActivity.this).toString();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public String stat(String path) {
            try {
                java.io.File f=new WorkspaceBrowser(new java.io.File(getFilesDir(),"workspace")).resolve(path);
                return new org.json.JSONObject().put("exists",f.isFile()).put("modified",f.lastModified()).put("bytes",f.length()).toString();
            } catch(Exception e) { return error(e); }
        }
        @android.webkit.JavascriptInterface public void live(String path) { runOnUiThread(()->showLivePreview(path)); }
        @android.webkit.JavascriptInterface public void review(boolean allow) { ActionReview.decide(allow); }
        @android.webkit.JavascriptInterface public void control(String action) {
            runOnUiThread(()-> {
                switch(action) {
                    case "settings": configuration(); break;
                    case "import": pickFile(false); break;
                    case "export": pickFile(true); break;
                    case "classic": showClassic(null); break;
                    case "start": startAgent(); break;
                    case "weights": startAgent(); break;
                    case "klein": startAgent(); break;
                    case "stop": stopService(new Intent(MainActivity.this,AgentService.class)); break;
                    case "console": console(); break;
                }
            });
        }
    }

    private static org.json.JSONArray outputsJson() {
        org.json.JSONArray a=new org.json.JSONArray();
        synchronized (AgentService.outputs) { for (String p : AgentService.outputs) a.put(p); }
        return a;
    }

    /**
     * Full-screen interactive preview of a workspace page. A separate WebView with NO JavaScript bridge, so the page's
     * own scripts run but can't reach the app. Relative files (css/js/images) load from the workspace; the internet is blocked.
     */
    private void showLivePreview(String path) {
        try {
            final java.io.File root=new java.io.File(getFilesDir(),"workspace");
            final WorkspaceBrowser browser=new WorkspaceBrowser(root);
            java.io.File file=browser.resolve(path);
            if(!file.isFile()) throw new java.io.IOException("File not found: "+path);
            final String folder=browser.relative(file.getParentFile());
            final android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setBackgroundColor(Color.rgb(22,11,29));
            LinearLayout bar=new LinearLayout(this); bar.setPadding(dp(12),dp(8),dp(12),dp(8)); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title=new TextView(this); title.setText("Live · "+path); title.setTextColor(Color.rgb(214,255,122)); title.setSingleLine(true);
            bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            final android.webkit.WebView view=new android.webkit.WebView(this);
            Button reload=new Button(this); reload.setText("Reload"); reload.setOnClickListener(v->view.reload());
            Button close=new Button(this); close.setText("Close"); close.setOnClickListener(v->dialog.dismiss());
            bar.addView(reload); bar.addView(close);
            box.addView(bar);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setDomStorageEnabled(true);
            view.getSettings().setAllowFileAccess(false);
            view.getSettings().setAllowContentAccess(false);
            view.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                    return !"preview.local".equals(r.getUrl().getHost());
                }
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                    android.net.Uri u=r.getUrl();
                    if(!"preview.local".equals(u.getHost()))
                        return new android.webkit.WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new java.io.ByteArrayInputStream(new byte[0]));
                    try {
                        String rel=u.getPath()==null?"":u.getPath().replaceFirst("^/","");
                        java.io.File f=browser.resolve(rel);
                        if(f.isDirectory()) f=new java.io.File(f,"index.html");
                        String name=f.getName().toLowerCase(java.util.Locale.ROOT);
                        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1):"";
                        String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                        if(ext.equals("js")||ext.equals("mjs")) mime="text/javascript";
                        if(mime==null) mime="application/octet-stream";
                        return new android.webkit.WebResourceResponse(mime,"UTF-8",new java.io.FileInputStream(f));
                    } catch(Exception e) {
                        return new android.webkit.WebResourceResponse("text/plain","UTF-8",404,"Not found",null,new java.io.ByteArrayInputStream(new byte[0]));
                    }
                }
            });
            box.addView(view,new LinearLayout.LayoutParams(-1,0,1));
            dialog.setContentView(box);
            dialog.setOnDismissListener(d->view.destroy());
            dialog.show();
            view.loadUrl("https://preview.local/"+(folder.isEmpty()?"":folder+"/")+file.getName());
        } catch(Exception e) {
            android.widget.Toast.makeText(this,"Preview: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showClassic(Bundle state) {
        web = null;
        if (state != null) exportPath = state.getString("exportPath");
        queue = new TaskQueue(this);
        settings = getSharedPreferences("agent", 0);
        getWindow().setStatusBarColor(Color.rgb(17, 20, 26));
        getWindow().setNavigationBarColor(Color.rgb(17, 20, 26));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(32));
        page.setBackgroundColor(Color.rgb(17, 20, 26));
        scroll.addView(page);
        setContentView(scroll);

        button(page, "Back to Cynthia’s chat", () -> recreate());
        label(page, "LOCAL AGENT  /  ARM64", 13).setTextColor(Color.rgb(125, 220, 205));
        label(page, "The Last Fuck", 34).setTypeface(Typeface.DEFAULT_BOLD);
        label(page, "Qwen Coder · Alpine Linux · Synthia Hands", 14);
        status = label(page, AgentService.status, 16);
        status.setPadding(0, dp(12), 0, dp(6));
        LinearLayout controls = new LinearLayout(this);
        page.addView(controls);
        button(controls, "Start watcher", this::startAgent).setBackgroundTintList(ColorStateList.valueOf(Color.rgb(29, 100, 92)));
        button(controls, "Stop", () -> stopService(new Intent(this, AgentService.class)));

        label(page, "Talk to the agent", 22).setTypeface(Typeface.DEFAULT_BOLD);
        task = new EditText(this);
        task.setHint("Type a message or coding task");
        task.setMinLines(3);
        task.setGravity(android.view.Gravity.TOP);
        page.addView(task, new LinearLayout.LayoutParams(-1, -2));
        button(page, "Send request", () -> enqueue("agent", task.getText().toString(), true));
        button(page, "Chat only (no actions)", () -> enqueue("chat", task.getText().toString(), true));
        button(page, "Set autonomous goal", this::editAutonomyGoal);
        label(page, "Send request uses tools. Results include evidence and remain open for your review.", 13);
        reply = label(page, "Replies appear here.", 16);
        reply.setTextIsSelectable(true);
        label(page, "Workspace tools", 22).setTypeface(Typeface.DEFAULT_BOLD);
        consoleStatus = label(page, "", 14);
        button(page, "Linux console", this::console);
        button(page, "Import file to workspace", () -> pickFile(false));
        button(page, "Export workspace file", () -> pickFile(true));
        button(page, "Hands & Tasker", this::configuration);
        label(page, "Recent activity", 22).setTypeface(Typeface.DEFAULT_BOLD);
        history = label(page, "", 14);
        history.setTypeface(Typeface.MONOSPACE);
        history.setTextIsSelectable(true);
    }

    private void startAgent() {
        startForegroundService(new Intent(this, AgentService.class));
    }

    private void enqueue(String kind, String text, boolean clearTask) {
        try {
            String queuedId=queue.enqueue(kind, text);
            if (clearTask) task.setText("");
            if ("chat".equals(kind)) AgentService.reply = "Message queued…";
            if (!AgentService.running) startAgent();
            Toast.makeText(this, "chat".equals(kind) ? "Message queued" : "Task queued", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Could not queue task").setMessage(e.getMessage())
                .setPositiveButton("OK", null).show();
        }
    }

    private void console() {
        EditText command = new EditText(this);
        command.setHint("git status");
        command.setTypeface(Typeface.MONOSPACE);
        command.setMinLines(3);
        new AlertDialog.Builder(this).setTitle("Alpine Linux console")
            .setMessage("Runs inside this app. See output in Recent activity. First use sets up Alpine automatically.")
            .setView(command).setNegativeButton("Cancel", null)
            .setNeutralButton("Android shell", (dialog, button) -> enqueue("android-shell", command.getText().toString(), false))
            .setPositiveButton("Run in Alpine", (dialog, button) -> enqueue("shell", command.getText().toString(), false)).show();
    }

    private static final int IMPORT = 81;
    private static final int EXPORT = 82;
    private static final int IMPORT_MODEL = 83;
    private String exportPath;

    private void pickFile(boolean export) {
        if (!export) {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT);
            return;
        }
        EditText path = new EditText(this);
        path.setSingleLine(true);
        path.setHint("Relative path, e.g. result.py");
        new AlertDialog.Builder(this).setTitle("Export workspace file").setView(path)
            .setNegativeButton("Cancel", null).setPositiveButton("Choose destination", (d, b) -> {
                exportPath = path.getText().toString().trim();
                try {
                    java.io.File source = workspacePath(exportPath);
                    if (!source.isFile()) throw new java.io.IOException("Workspace file does not exist");
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, source.getName()), EXPORT);
                } catch (Exception e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("exportPath", exportPath);
        super.onSaveInstanceState(state);
    }

    private static final int MCP_FOLDER = 7731;
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == MCP_FOLDER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                org.mobilemcp.ondevice.McpControl.selectWorkspace(this, data.getData());
            }
            return;
        }
        if ((request != IMPORT && request != EXPORT && request != IMPORT_MODEL) || result != RESULT_OK || data == null || data.getData() == null)
            return;
        if (request == IMPORT_MODEL) {
            Toast.makeText(this, "Verifying Qwen model file", Toast.LENGTH_LONG).show();
        }
        final String sourcePath = exportPath;
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            String message;
            try {
                if (request == IMPORT_MODEL) {
                    LocalCoder.importModel(getApplicationContext(), uri);
                    message = "Qwen installed. Press Start agent.";
                } else if (request == IMPORT) {
                    String name = null;
                    try (android.database.Cursor cursor = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    if (name == null || name.isEmpty() || name.contains("/") || ".".equals(name) || "..".equals(name))
                        throw new java.io.IOException("Invalid document name");
                    java.io.File file = workspacePath(name);
                    java.nio.file.Files.createDirectories(file.getParentFile().toPath());
                    java.io.File stage = java.io.File.createTempFile("import", ".tmp", file.getParentFile());
                    try {
                        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                             java.io.FileOutputStream out = new java.io.FileOutputStream(stage)) {
                            copy(in, out);
                        }
                        java.nio.file.Files.move(stage.toPath(), file.toPath());
                    } finally {
                        stage.delete();
                    }
                    message = "Imported /workspace/" + name;
                } else {
                    java.io.File file = workspacePath(sourcePath);
                    try (java.io.InputStream in = new java.io.FileInputStream(file);
                         java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        copy(in, out);
                    }
                    message = "Exported " + file.getName();
                }
            } catch (Exception e) {
                message = "File transfer failed · " + e.getMessage();
            }
            String notice = message;
            runOnUiThread(() -> Toast.makeText(this, notice, Toast.LENGTH_LONG).show());
        }, "workspace-transfer").start();
    }

    private java.io.File workspacePath(String path) throws java.io.IOException {
        if (path == null || path.isEmpty() || path.startsWith("/")) throw new java.io.IOException("Use a relative file path");
        java.io.File root = new java.io.File(getFilesDir(), "workspace").getCanonicalFile();
        java.io.File file = new java.io.File(root, path).getCanonicalFile();
        if (file.equals(root) || !file.toPath().startsWith(root.toPath())) throw new java.io.IOException("Path outside workspace");
        return file;
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        if (in == null || out == null) throw new java.io.IOException("Android could not open the document");
        byte[] buffer = new byte[32768];
        for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
    }

    private void configuration() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), 0, dp(22), 0);
        CheckBox shell = new CheckBox(this);
        shell.setText("Let the agent run Linux and Android shell commands");
        shell.setChecked(settings.getBoolean("shell", false));
        content.addView(shell);
        label(content, "Enable for trusted workspaces. Linux commands run with the app’s Android identity.", 13);
        CheckBox phone = new CheckBox(this);
        phone.setText("Let the agent use phone Hands");
        phone.setChecked(settings.getBoolean("phone_hands", false));
        content.addView(phone);
        CheckBox autonomy = new CheckBox(this);
        autonomy.setText("Autonomy: work toward a saved goal (3 bounded cycles, 10-minute cooldown)");
        autonomy.setChecked(settings.getBoolean("autonomy", false));
        autonomy.setOnCheckedChangeListener((view, enabled) -> {
            if (enabled && settings.getString("autonomy_goal", "").trim().isEmpty()) {
                autonomy.setChecked(false);
                Toast.makeText(this, "Save an autonomy goal first", Toast.LENGTH_LONG).show();
                return;
            }
            settings.edit().putBoolean("autonomy", enabled).commit();
            if (enabled) {
                if (!AgentService.running) startAgent();
            }
        });
        content.addView(autonomy);
        button(content, "Set autonomous goal", this::editAutonomyGoal);
        label(content, "Autonomous cycles can inspect, create and append workspace files. "
            + "Shell commands, Tasker and phone Hands are not available to unattended goal cycles. "
            + "Pauses after 3 cycles or on blocked work.", 13);
        button(content, "Android accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        EditText tasker = new EditText(this);
        tasker.setMinLines(2);
        tasker.setHint("Allowed Tasker task names, one per line");
        tasker.setText(settings.getString("tasker_task", ""));
        content.addView(tasker);
        label(content, "Tasker: Preferences → Misc → Allow External Access. Add existing task names above; the agent can run them and pass %par1 / %par2.", 13);
        button(content, "Open Tasker", () -> {
            String installed = TaskerBridge.installedPackage(this);
            Intent launch = installed == null ? null : getPackageManager().getLaunchIntentForPackage(installed);
            if (launch != null) startActivity(launch);
            else Toast.makeText(this, "Install Tasker first.", Toast.LENGTH_LONG).show();
        });
        button(content, "Enable Tasker access", () -> requestTaskerAccess(false));
        button(content, "Show Tasker inbox setup", this::taskerSetup);
        button(content, "Import Qwen GGUF for offline setup", () -> {
            if (AgentService.running) {
                Toast.makeText(this, "Stop the agent before importing weights.", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_MODEL);
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("Hands & Tasker").setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Save & test Tasker", (dialog, button) -> {
                save(shell, phone, tasker);
                requestTaskerAccess(true);
            }).setPositiveButton("Save", (dialog, button) -> save(shell, phone, tasker)).show();
    }

    private void editAutonomyGoal() {
        EditText goal = new EditText(this);
        goal.setMinLines(4);
        goal.setMaxLines(9);
        goal.setHint("Describe what Cynthia should progressively build or investigate");
        goal.setText(settings.getString("autonomy_goal", ""));
        new AlertDialog.Builder(this).setTitle("Autonomy goal")
            .setMessage("Save the goal here, then enable autonomy in the top switch or Hands settings. "
                + "This does not give unattended control of the phone or shell.")
            .setView(goal).setNegativeButton("Cancel", null)
            .setPositiveButton("Save goal", (dialog, which) -> {
                try {
                    org.json.JSONObject result = new org.json.JSONObject(
                        new DesktopBridge().saveGoal(goal.getText().toString()));
                    if (result.has("error")) throw new IllegalArgumentException(result.getString("error"));
                    Toast.makeText(this, "Goal saved · enable autonomy to start", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Could not save goal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    private boolean pendingTaskerTest;
    private static final int TASKER_PERMISSION = 84;

    private void requestTaskerAccess(boolean test) {
        if (checkSelfPermission(TaskerBridge.PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (test) enqueue("tasker", "Tasker test", false);
            else Toast.makeText(this, "Tasker permission granted. Enable Allow External Access inside Tasker too.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingTaskerTest = test;
        requestPermissions(new String[]{TaskerBridge.PERMISSION}, TASKER_PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != TASKER_PERMISSION) return;
        boolean granted = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted && pendingTaskerTest) enqueue("tasker", "Tasker test", false);
        else Toast.makeText(this, granted ? "Tasker permission granted."
            : "Tasker access needs your permission. Open Tasker, enable Allow External Access and allow this app; then retry.", Toast.LENGTH_LONG).show();
        pendingTaskerTest = false;
    }

    private void save(CheckBox shell, CheckBox phone, EditText tasker) {
        settings.edit().putBoolean("shell", shell.isChecked()).putBoolean("phone_hands", phone.isChecked())
            .putString("tasker_task", tasker.getText().toString().trim()).apply();
    }

    private void taskerSetup() {
        String token = TaskerInbox.token(this);
        TextView details = new TextView(this);
        details.setPadding(dp(20), 0, dp(20), 0);
        details.setTextIsSelectable(true);
        details.setText("Tasker Send Intent\nAction app.lastfuck.QUEUE_TASK\nPackage " + getPackageName() + "\n"
            + "Class app.lastfuck.TaskerInbox\nTarget Broadcast Receiver\n\n"
            + "Extra 1: token:" + token + "\nExtra 2: task:<your task>\n\n"
            + "For outgoing tasks, allow external access in Tasker and enter the exact task name here.");
        new AlertDialog.Builder(this).setTitle("Tasker inbox").setView(details).setPositiveButton("Close", null).show();
    }

    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        parent.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        return view;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setOnClickListener(v -> action.run());
        parent.addView(view);
        return view;
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
