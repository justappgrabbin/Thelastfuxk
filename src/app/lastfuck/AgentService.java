package app.lastfuck;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.File;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentService extends Service {
    static final String STOP = "app.lastfuck.STOP";
    private static final String ACTIVE = "agent_active";
    private static final String EVENTS = "agent_events";
    static volatile String status = "Stopped";
    static volatile boolean running;
    private static final java.util.concurrent.locks.ReentrantLock WORKER_LOCK =
        new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean stopping;
    static volatile String manualStatus = "";
    static volatile String reply = "";
    static volatile String computerStatus = "Starting computer…";
    /** Live plan + progress for the current request, read by the WebView. */
    static volatile String plan = "{}";
    /** Last workspace file Qwen wrote, for the preview bar. */
    static volatile String lastOutput = "";
    /** Recent files Qwen wrote, newest last (for the Preview tab). */
    static final java.util.LinkedHashSet<String> outputs = new java.util.LinkedHashSet<>();
    static volatile String kleinStatus = "Klein not contacted yet";
    private Thread weightsWorker;
    private Thread kleinWorker;
    private KleinRuntime klein;
    private Thread computerWorker;
    private ConversationLog conversations;
    private LocalCoder coder;
    private LinuxWorkspace linux;
    private Thread worker;
    private Thread manualWorker;
    private LinuxWorkspace manualLinux;
    private final ArrayDeque<JSONObject> chat = new ArrayDeque<>();
    private NotificationManager notifications;
    private final AtomicInteger eventId = new AtomicInteger(10);

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(ACTIVE, "Agent status", NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(EVENTS, "Task outcomes", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) { startComputer(); startWeightsDownload(); startKlein(); return START_STICKY; }
        running = true;
        status = "Starting";
        startForeground(1, notification(ACTIVE, "The Last Fuck", status, true));
        conversations = new ConversationLog(getFilesDir());
        TaskQueue queue = new TaskQueue(this);
        linux = new LinuxWorkspace(this, queue.inbox);
        manualLinux = new LinuxWorkspace(this, queue.inbox);
        manualWorker = new Thread(() -> runManual(queue), "local-console");
        coder = new LocalCoder(this, this::show);
        worker = new Thread(() -> run(queue), "local-agent");
        worker.start();
        return START_STICKY;
    }

    private void run(TaskQueue queue) {
        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lastfuck:agent");
        boolean locked = false;
        try {
            WORKER_LOCK.lockInterruptibly();
            locked = true;
            queue.prepare();
            queue.markInterrupted();
            java.nio.file.Files.createDirectories(linux.workspace.toPath());
            manualWorker.start();
            HandsTools hands = new HandsTools(this, linux);
            startComputer();
            startWeightsDownload();
            startKlein();
            show("Watcher active · waiting for work");
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                if (computerStatus.startsWith("Linux computer running") && !linux.isResidentAlive()) {
                    computerStatus = "Reconnecting Linux computer…";
                    startComputer();
                }
                if (klein != null && !klein.isRunning() && KleinRuntime.status.startsWith("Klein running")) {
                    KleinRuntime.status = "Klein stopped unexpectedly · restarting";
                    startKlein();
                }
                File task = queue.claim(false);
                if (task == null) {
                    if (scheduleAutonomousGoal(queue)) continue;
                    Thread.sleep(1500);
                    continue;
                }
                wake.acquire(30 * 60_000L);
                String title = "Watcher task";
                String result;
                String outcome;
                boolean autonomousTask = false;
                String autonomousSession = "";
                try {
                    JSONObject job = queue.load(task);
                    String kind = job.getString("kind");
                    String text = job.getString("text");
                    autonomousTask = "autonomous".equals(kind);
                    if (autonomousTask && text.startsWith("Autonomy session ID: ")) {
                        int lineEnd = text.indexOf('\n');
                        if (lineEnd > 0) autonomousSession = text.substring("Autonomy session ID: ".length(), lineEnd);
                    }
                    title = ("tasker".equals(kind) ? "Tasker test" : text.replaceAll("\\s+", " "));
                    if (title.length() > 100) title = title.substring(0, 100) + "…";
                    if ("chat".equals(kind)) title = "Chat · " + title;
                    if (autonomousTask) title = "Autonomy · " + HandsTools.excerpt(
                        getSharedPreferences("agent", 0).getString("autonomy_goal", ""), 80);
                    show("Working · " + title);
                    if ("assistant".equals(kind)) {
                        android.content.SharedPreferences prefs = getSharedPreferences("agent", 0);
                        KleinClient.Reply klein = null;
                        if (prefs.getBoolean("klein_on", true)) {
                            show("Klein is listening…");
                            try {
                                klein = KleinClient.chat(prefs.getString("klein_url", KleinClient.DEFAULT_URL), text,
                                    prefs.getString("klein_token", ""));
                                kleinStatus = "Klein connected · " + KleinClient.normalize(prefs.getString("klein_url", KleinClient.DEFAULT_URL));
                            } catch (Exception e) {
                                kleinStatus = "Klein unreachable · " + e.getMessage();
                            }
                        }
                        if (klein != null) {
                            result = klein.text;
                            outcome = "Klein";
                            if (prefs.getBoolean("qwen_proactive", true)) {
                                String handoff = proactiveHandoff(queue, text, klein.text);
                                if (!handoff.isEmpty()) { result += "\n\n" + handoff; outcome = "Klein · Qwen started work"; }
                            }
                        } else {
                            String normalized = text.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[!?.]+$", "");
                            String note = prefs.getBoolean("klein_on", true) ? "(Klein is offline, so Qwen answered · " + kleinStatus + ")\n" : "";
                            if (java.util.Arrays.asList("hi", "hello", "hey", "hello cynthia", "hi cynthia").contains(normalized)) {
                                result = note + "Hey, I’m here. What are we making today? You can chat with me, ask me to work, or open my computer below.";
                                outcome = "Interface greeting";
                            } else {
                                coder.start();
                                show("Cynthia is thinking…");
                                CodingAgent.Tools reviewed = reviewedTools(hands);
                                CodingAgent.Result answer = new CodingAgent(coder::complete, reviewed, this::show)
                                    .onPlan(p -> plan = p.toString()).converse(text, conversationContext());
                                result = note + answer.output;
                                outcome = answer.status;
                            }
                        }
                        reply = result;
                    } else if ("chat".equals(kind)) {
                        coder.start();
                        show("Qwen is replying");
                        result = chat(text);
                        reply = result;
                        outcome = "Reply";
                    } else if ("agent".equals(kind) || autonomousTask) {
                        String session = getSharedPreferences("agent", 0).getString("autonomy_session", "");
                        if (autonomousTask && (!getSharedPreferences("agent", 0).getBoolean("autonomy", false)
                            || session.isEmpty() || !text.startsWith("Autonomy session ID: " + session + "\n"))) {
                            outcome = "Paused";
                            result = "Autonomous goal changed or was switched off before execution.";
                        } else {
                            String why = whyOf(text);
                            show("Qwen started · " + title + (why.isEmpty() ? "" : " · because " + why));
                            coder.start();
                            final String taskTitle = title;
                            CodingAgent.Result agent = new CodingAgent(coder::complete,
                                reviewedTools(hands, autonomousTask),
                                phase -> show(taskTitle + " · " + phase)).onPlan(p -> plan = withWhy(p, taskTitle, why)).execute(text);
                            outcome = agent.status;
                            result = agent.output;
                        }
                        reply = outcome + "\n" + result;
                    } else throw new IllegalArgumentException("Unsupported task type");
                } catch (Exception e) {
                    outcome = stopping ? "Interrupted" : (e instanceof InterruptedException ? "Stopped request" : "Failed");
                    result = String.valueOf(e.getMessage());
                    reply = outcome + " · " + result;
                } finally {
                    if (wake.isHeld()) wake.release();
                }
                try { JSONObject p = new JSONObject(plan); if (p.optBoolean("active")) plan = p.put("active", false).put("phase", outcome).toString(); }
                catch (Exception ignored) { }
                // Persist each outcome before sending its notification.
                boolean interrupted = Thread.interrupted();
                queue.finish(task, outcome, title, result);
                if (autonomousTask) recordAutonomousOutcome(autonomousSession, outcome, result);
                try { conversations.append("assistant", result, task.getName().split("\\.running-")[0], outcome); }
                catch (Exception logError) { reply = result + "\nConversation could not be saved: " + logError.getMessage(); }
                if (interrupted) Thread.currentThread().interrupt();
                if (stopping) break;
                event(outcome + " · " + title, result);
                show("Watcher active · waiting for work");
            }
        } catch (Exception e) {
            if (!stopping) {
                String error = "Startup or watcher failed · " + e.getMessage();
                status = error;
                event("Agent stopped", error);
            }
        } finally {
            if (wake.isHeld()) wake.release();
            coder.close();
            linux.close();
            manualLinux.close();
            manualWorker.interrupt();
            boolean interrupted = Thread.interrupted();
            while (manualWorker.isAlive()) {
                try { manualWorker.join(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (locked) WORKER_LOCK.unlock();
            if (!stopping) stopSelf();
        }
    }

    /** Starts Klein on this phone (Java model + klein-agent Python in Alpine) unless the user points chat elsewhere. */
    synchronized void startKlein() {
        android.content.SharedPreferences p = getSharedPreferences("agent", 0);
        if (stopping || linux == null || !p.getBoolean("klein_on", true) || !p.getBoolean("klein_local", true)) return;
        if ((kleinWorker != null && kleinWorker.isAlive()) || (klein != null && klein.isRunning())) return;
        if (klein == null) klein = new KleinRuntime(this, linux);
        kleinWorker = new Thread(() -> {
            try {
                for (int i = 0; i < 100 && !stopping && !linux.isResidentAlive(); i++) Thread.sleep(600);
                klein.start();
            } catch (Exception e) {
                if (!stopping) KleinRuntime.status = "Klein on this phone failed · " + e.getMessage();
            }
        }, "klein-runtime");
        kleinWorker.start();
    }

    /** Fetches Qwen weights as soon as the app starts so the first message doesn't wait on 1.12 GB. */
    synchronized void startWeightsDownload() {
        if (stopping || coder == null || LocalCoder.weightsReady || (weightsWorker != null && weightsWorker.isAlive())) return;
        weightsWorker = new Thread(() -> {
            try { coder.ensureModel(); show("Qwen weights ready · watcher waiting for work"); }
            catch (Exception e) {
                if (!stopping) {
                    LocalCoder.weightsStatus = "Weights download paused · " + e.getMessage() + " · tap Download weights to resume";
                    event("Qwen weights not ready", LocalCoder.weightsStatus);
                }
            }
        }, "qwen-weights");
        weightsWorker.start();
    }

    private synchronized void startComputer() {
        if (stopping || linux == null || linux.isResidentAlive() || (computerWorker != null && computerWorker.isAlive())) return;
        computerWorker = new Thread(() -> {
            try {
                computerStatus = "Starting Alpine computer…";
                linux.startResident();
                computerStatus = "Linux computer running · /workspace";
            } catch (Exception e) { computerStatus = "Computer needs attention · " + e.getMessage(); }
        }, "linux-computer");
        computerWorker.start();
    }

    private static String whyOf(String text) {
        for (String line : text.split("\n")) if (line.startsWith("Why: ")) return line.substring(5).trim();
        return "";
    }

    private static String withWhy(JSONObject p, String task, String why) {
        try { return p.put("task", task).put("why", why).toString(); } catch (Exception e) { return p.toString(); }
    }

    /** Qwen reads the exchange and decides whether there is real work to start on its own, and says why. */
    private String proactiveHandoff(TaskQueue queue, String said, String kleinReply) {
        if (!LocalCoder.weightsReady) return "";
        try {
            coder.start();
            show("Qwen is checking whether there’s work to do…");
            JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content",
                    "You watch a chat between the user and Klein. Decide if the user asked for concrete work on files, code, the workspace or the phone. "
                    + "Small talk, questions and opinions are NOT work. Return ONE JSON object: "
                    + "{\"work\":false} or {\"work\":true,\"task\":\"a precise task instruction\",\"why\":\"one short sentence quoting what the user asked\"}."))
                .put(new JSONObject().put("role", "user").put("content",
                    "User said: " + HandsTools.excerpt(said, 2000) + "\nKlein replied: " + HandsTools.excerpt(kleinReply, 1200)));
            JSONObject d = CodingAgent.parseAction(coder.complete(messages));
            if (d == null || !d.optBoolean("work", false)) return "";
            String task = d.optString("task").trim(), why = d.optString("why").trim();
            if (task.isEmpty()) return "";
            if (why.isEmpty()) why = "you asked: " + HandsTools.excerpt(said, 120);
            queue.enqueue("agent", task + "\nWhy: " + why + "\nOriginal message: " + HandsTools.excerpt(said, 1500));
            return "⚙ Qwen is starting on this: " + HandsTools.excerpt(task, 200) + "\nWhy: " + why
                + "\nWatch the plan and progress bar; I’ll ask before changing anything.";
        } catch (Exception e) {
            return "";
        }
    }

    private CodingAgent.Tools reviewedTools(HandsTools hands) { return reviewedTools(hands, false); }

    private CodingAgent.Tools reviewedTools(HandsTools hands, boolean unattended) {
        return new CodingAgent.Tools() {
            public String available() {
                return unattended ? "list(path,offset), read(path,offset), write(path,text,append). "
                    + "Writes may create or append, never overwrite." : hands.available();
            }
            public JSONObject call(String name, JSONObject args) throws Exception {
                if (stopping) throw new InterruptedException();
                if (unattended) {
                    if (!getSharedPreferences("agent", 0).getBoolean("autonomy", false))
                        throw new InterruptedException("Autonomy was stopped");
                    AutonomyPolicy.requireAllowedTool(name, args, linux.workspace);
                    return track(name, args, hands.call(name, args));
                }
                boolean readOnly = java.util.Arrays.asList("read", "list", "observe", "tasker_status").contains(name);
                if (!readOnly) {
                    show("Waiting for your approval · " + name);
                    ActionReview.await(new JSONObject().put("tool", name).put("args", args).toString());
                }
                if (stopping) throw new InterruptedException();
                return track(name, args, hands.call(name, args));
            }
        };
    }

    private static JSONObject track(String name, JSONObject args, JSONObject result) {
        if ("write".equals(name) && result.optBoolean("ok") && args != null && !args.optString("path").isEmpty())
        {
            lastOutput = args.optString("path");
            synchronized (outputs) {
                outputs.remove(lastOutput);
                outputs.add(lastOutput);
                while (outputs.size() > 30) outputs.remove(outputs.iterator().next());
            }
        }
        return result;
    }

    private boolean scheduleAutonomousGoal(TaskQueue queue) throws Exception {
        android.content.SharedPreferences p = getSharedPreferences("agent", 0);
        String goal = p.getString("autonomy_goal", "").trim();
        String session = p.getString("autonomy_session", "");
        int cycles = p.getInt("autonomy_cycles", 0);
        long now = System.currentTimeMillis();
        if (!AutonomyPolicy.due(p.getBoolean("autonomy", false), goal, session,
                cycles, p.getLong("autonomy_next_at", 0L), now)) return false;
        String prompt = AutonomyPolicy.prompt(session, goal, p.getString("autonomy_last", ""));
        queue.enqueue("autonomous", prompt);
        p.edit().putInt("autonomy_cycles", cycles + 1)
            .putLong("autonomy_next_at", now + AutonomyPolicy.COOLDOWN_MS)
            .putString("autonomy_status", "Autonomy cycle " + (cycles + 1)
                + " of " + AutonomyPolicy.MAX_CYCLES + " queued").commit();
        show("Autonomy · cycle " + (cycles + 1) + " queued");
        return true;
    }

    private void recordAutonomousOutcome(String taskSession, String outcome, String result) {
        android.content.SharedPreferences p = getSharedPreferences("agent", 0);
        // A changed goal creates a new session. Old queued or running work must
        // never pause, advance or overwrite the state of the new goal.
        if (taskSession.isEmpty() || !taskSession.equals(p.getString("autonomy_session", ""))) return;
        int cycle = p.getInt("autonomy_cycles", 0);
        boolean pause = AutonomyPolicy.pauses(outcome) || cycle >= AutonomyPolicy.MAX_CYCLES;
        String label = pause
            ? ("Autonomy paused after cycle " + cycle + " · " + outcome)
            : ("Autonomy cycle " + cycle + " recorded · next check in 10 minutes");
        p.edit().putString("autonomy_last", HandsTools.excerpt(result, 1600))
            .putString("autonomy_status", label)
            .putBoolean("autonomy", !pause && p.getBoolean("autonomy", false))
            .putLong("autonomy_next_at", System.currentTimeMillis() + AutonomyPolicy.COOLDOWN_MS)
            .commit();
    }

    private String conversationContext() throws Exception {
        JSONArray history = conversations.tail(8);
        StringBuilder context = new StringBuilder();
        for (int i=0;i<history.length();i++) {
            JSONObject turn=history.getJSONObject(i);
            context.append(turn.optString("role")).append(": ")
                .append(HandsTools.excerpt(turn.optString("text"), 500)).append('\n');
        }
        return context.toString();
    }

    private String chat(String text) throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system")
            .put("content", "You are Cynthia, a warm, practical companion. Be friendly and concise. This is chat-only mode with no tools. Never claim to have created, changed, tested, or completed anything. Direct action requests to the Send request button."));
        for (JSONObject message : chat) messages.put(message);
        JSONObject user = new JSONObject().put("role", "user").put("content", HandsTools.excerpt(text, 4000));
        String response = coder.complete(messages.put(user), false);
        chat.add(user);
        chat.add(new JSONObject().put("role", "assistant").put("content", HandsTools.excerpt(response, 2400)));
        while (chat.size() > 2) chat.removeFirst();
        return response;
    }

    private void runManual(TaskQueue queue) {
        try {
            while (!stopping && !Thread.currentThread().isInterrupted()) {
                File task = queue.claim(true);
                if (task == null) { Thread.sleep(300); continue; }
                String title = "Console command";
                String outcome;
                String output;
                try {
                    JSONObject job = queue.load(task);
                    String kind = job.getString("kind");
                    if ("tasker".equals(kind)) {
                        title = "Tasker test";
                        outcome = "Awaiting confirmation";
                        output = TaskerBridge.dispatch(this).getString("evidence");
                    } else {
                        boolean alpine = "shell".equals(kind);
                        title = alpine ? "Alpine Linux" : "Android shell";
                        manualStatus = alpine ? "Starting or running Alpine Linux…" : "Running Android shell…";
                        LinuxWorkspace.Result result = alpine ? manualLinux.shell(job.getString("text"), true)
                            : manualLinux.androidShell(job.getString("text"));
                        outcome = result.code == 0 ? "Finished" : "Failed";
                        output = "Exit " + result.code + "\n" + result.output;
                        if (alpine && result.code != 0 && !result.output.isEmpty())
                            output += "\nAndroid shell remains available from the console.";
                    }
                } catch (Exception e) {
                    outcome = stopping ? "Interrupted" : "Failed";
                    output = e.toString();
                }
                boolean interrupted = Thread.interrupted();
                queue.finish(task, outcome, title, output);
                if (interrupted) Thread.currentThread().interrupt();
                if (!stopping) {
                    manualStatus = outcome + " · " + title;
                    event(manualStatus, output);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            manualStatus = "Console worker failed · " + e.getMessage();
        }
    }

    private void show(String text) {
        if (stopping) return;
        status = text;
        notifications.notify(1, notification(ACTIVE, "The Last Fuck", text, true));
    }

    private void event(String title, String body) {
        notifications.notify(eventId.getAndIncrement(), notification(EVENTS, title, HandsTools.excerpt(body, 700), false));
    }

    private Notification notification(String channel, String title, String body, boolean ongoing) {
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), pendingFlags);
        Notification.Builder builder = new Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setOngoing(ongoing).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(!ongoing);
        if (ongoing) {
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, AgentService.class).setAction(STOP), pendingFlags);
            builder.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        }
        return builder.build();
    }

    @Override public void onDestroy() {
        stopping = true;
        ActionReview.decide(false);
        if (computerWorker != null) computerWorker.interrupt();
        if (weightsWorker != null) weightsWorker.interrupt();
        if (klein != null) klein.close();
        if (kleinWorker != null) kleinWorker.interrupt();
        computerStatus = "Computer stopped";
        running = false;
        if (coder != null) coder.close();
        if (linux != null) linux.close();
        if (worker != null) worker.interrupt();
        if (manualLinux != null) manualLinux.close();
        if (manualWorker != null) manualWorker.interrupt();
        if (!status.contains("failed")) status = "Stopped";
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
