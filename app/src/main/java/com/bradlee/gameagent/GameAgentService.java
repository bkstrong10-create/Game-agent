package com.bradlee.gameagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class GameAgentService extends AccessibilityService {

    private static final String TAG            = "GameAgentService";
    private static final String CHANNEL_ID     = "game_agent";
    private static final int    NOTIF_ID       = 1001;
    private static final double COST_IN        = 3.0  / 1_000_000.0;  // $3/1M input tokens
    private static final double COST_OUT       = 15.0 / 1_000_000.0;  // $15/1M output tokens
    private static final int    STUCK_LIMIT    = 3;
    private static final int    MAX_HISTORY    = 5;

    // Static access for MainActivity
    public static GameAgentService instance;
    public static String apiKey      = "";
    public static String gameContext = "Play this game as well as you can.";
    public static int    loopDelayMs = 2500;

    public interface LogListener {
        void onUpdate(String status, String line, double costUsd, int steps);
    }
    public static LogListener logListener;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private final Handler         mainHandler  = new Handler(Looper.getMainLooper());
    private       OkHttpClient    http;
    private       NotificationManager nm;
    private       boolean         running      = false;
    private       int             stepCount    = 0;
    private       double          totalCost    = 0.0;

    // Feature 1 & 2: Action history + stuck detection
    private final LinkedList<String> actionHistory = new LinkedList<>();
    private       String  lastActionKey = "";
    private       int     stuckCount    = 0;

    // Feature 5: Screen diff
    private String lastScreenHash = "";

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        setupNotificationChannel();
        log("READY", "Service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { stopAgent(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (nm != null) nm.cancel(NOTIF_ID);
        stopAgent();
    }

    // ── Control ────────────────────────────────────────────────────────────────

    public void startAgent() {
        if (running) return;
        if (apiKey.isEmpty()) { log("ERROR", "API key not set"); return; }
        running        = true;
        stepCount      = 0;
        totalCost      = 0.0;
        lastActionKey  = "";
        stuckCount     = 0;
        lastScreenHash = "";
        actionHistory.clear();
        log("RUNNING", "Agent started");
        pushNotification("Starting...");
        scheduleStep(800);
    }

    public void stopAgent() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (nm != null) nm.cancel(NOTIF_ID);
        log("STOPPED", String.format("Stopped after %d steps — total cost $%.4f", stepCount, totalCost));
    }

    public boolean isRunning() { return running; }

    private void scheduleStep(long delay) {
        if (running) mainHandler.postDelayed(this::agentStep, delay);
    }

    // ── Main loop ──────────────────────────────────────────────────────────────

    private void agentStep() {
        if (!running) return;
        stepCount++;
        log("RUNNING", "Step " + stepCount + ": capturing...");

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw  = Bitmap.wrapHardwareBuffer(hb, null);
                Bitmap sw2 = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle(); hb.close();

                int W = sw2.getWidth(), H = sw2.getHeight();
                Bitmap small = Bitmap.createScaledBitmap(sw2, W / 2, H / 2, true);
                sw2.recycle();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                small.recycle();

                byte[] jpeg = baos.toByteArray();

                // Feature 5: Screen diff — skip if unchanged
                String hash = md5(jpeg);
                if (hash.equals(lastScreenHash) && stepCount > 1) {
                    log("RUNNING", "Screen unchanged, waiting...");
                    scheduleStep(600);
                    return;
                }
                lastScreenHash = hash;

                log("RUNNING", "Step " + stepCount + ": asking Claude...");
                callClaude(Base64.encodeToString(jpeg, Base64.NO_WRAP));
            }

            @Override
            public void onFailure(int errorCode) {
                log("RUNNING", "Screenshot failed (code=" + errorCode + ")");
                scheduleStep(loopDelayMs);
            }
        });
    }

    // ── Claude Vision API ──────────────────────────────────────────────────────

    private void callClaude(String b64) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        try {
            JSONObject payload = new JSONObject()
                .put("model", "claude-sonnet-4-6")
                .put("max_tokens", 256)
                .put("system", buildSystemPrompt(sw, sh))
                .put("messages", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("content", new JSONArray()
                        .put(new JSONObject().put("type","image").put("source", new JSONObject()
                            .put("type","base64").put("media_type","image/jpeg").put("data", b64)))
                        .put(new JSONObject().put("type","text").put("text","What action next?")))));

            Request req = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                .build();

            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    log("RUNNING", "Network error: " + e.getMessage());
                    scheduleStep(loopDelayMs);
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    ResponseBody body = response.body();
                    if (body == null) { scheduleStep(loopDelayMs); return; }
                    try {
                        JSONObject json = new JSONObject(body.string());

                        // Feature 4: Cost tracking from usage field
                        if (json.has("usage")) {
                            JSONObject u = json.getJSONObject("usage");
                            totalCost += u.optInt("input_tokens",0)  * COST_IN
                                       + u.optInt("output_tokens",0) * COST_OUT;
                        }

                        if (json.has("error")) {
                            log("RUNNING", "Claude: " + json.getJSONObject("error").getString("message"));
                            scheduleStep(loopDelayMs);
                            return;
                        }

                        String raw = json.getJSONArray("content").getJSONObject(0)
                            .getString("text").trim()
                            .replace("```json","").replace("```","").trim();

                        // Extract JSON even if Claude wraps in text
                        int js = raw.indexOf('{'), je = raw.lastIndexOf('}');
                        if (js < 0 || je <= js) {
                            log("RUNNING", "No JSON in response, retrying...");
                            scheduleStep(loopDelayMs);
                            return;
                        }
                        raw = raw.substring(js, je + 1);

                        JSONObject action = new JSONObject(raw);
                        String type = action.optString("action");

                        // Feature 2: Stuck detection (rounded coords)
                        String actionKey = buildActionKey(action, type);
                        if (actionKey.equals(lastActionKey)) stuckCount++;
                        else { stuckCount = 0; lastActionKey = actionKey; }

                        // Feature 1: Action history
                        actionHistory.addLast(raw);
                        if (actionHistory.size() > MAX_HISTORY) actionHistory.removeFirst();

                        log("RUNNING", String.format("Step %d: %s  ($%.4f)", stepCount, raw, totalCost));
                        pushNotification(String.format("Step %d • $%.4f", stepCount, totalCost));

                        mainHandler.post(() -> dispatch(action, type));

                    } catch (Exception e) {
                        log("RUNNING", "Parse error: " + e.getMessage());
                        scheduleStep(loopDelayMs);
                    }
                }
            });
        } catch (Exception e) {
            log("RUNNING", "Build error: " + e.getMessage());
            scheduleStep(loopDelayMs);
        }
    }

    // ── Action dispatch ────────────────────────────────────────────────────────

    private void dispatch(JSONObject action, String type) {
        switch (type) {
            // Feature 8: Multi-step sequences
            case "sequence":
                try { executeSequence(action.getJSONArray("steps"), 0); }
                catch (Exception e) { scheduleStep(loopDelayMs); }
                break;

            // Feature 3: Game over auto-restart
            case "restart":
                handleRestart(action);
                break;

            case "wait":
                scheduleStep(action.optLong("ms", loopDelayMs));
                break;

            default:
                execute(action);
                scheduleStep(loopDelayMs);
                break;
        }
    }

    // Feature 8: Execute sequence of actions with 600ms gaps
    private void executeSequence(JSONArray steps, int index) {
        if (index >= steps.length()) { scheduleStep(loopDelayMs); return; }
        try {
            execute(steps.getJSONObject(index));
            mainHandler.postDelayed(() -> executeSequence(steps, index + 1), 600);
        } catch (Exception e) { scheduleStep(loopDelayMs); }
    }

    // Feature 3: Handle game over — tap restart button, reset state
    private void handleRestart(JSONObject action) {
        try {
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            JSONObject tap = new JSONObject()
                .put("action", "tap")
                .put("x", action.optInt("x", sw / 2))
                .put("y", action.optInt("y", sh / 2));
            execute(tap);
            // Reset state for new game
            actionHistory.clear();
            lastActionKey  = "";
            stuckCount     = 0;
            lastScreenHash = "";
            log("RUNNING", "Game over detected — restarting!");
            scheduleStep(loopDelayMs * 2);
        } catch (Exception e) { scheduleStep(loopDelayMs); }
    }

    private void execute(JSONObject action) {
        try {
            String type = action.getString("action");
            GestureDescription.Builder b = new GestureDescription.Builder();
            switch (type) {
                case "tap": {
                    float x=(float)action.getDouble("x"), y=(float)action.getDouble("y");
                    Path p = new Path(); p.moveTo(x, y);
                    b.addStroke(new GestureDescription.StrokeDescription(p, 0, 50));
                    dispatchGesture(b.build(), null, null);
                    break;
                }
                case "swipe": {
                    float x1=(float)action.getDouble("x1"),y1=(float)action.getDouble("y1");
                    float x2=(float)action.getDouble("x2"),y2=(float)action.getDouble("y2");
                    long dur=action.optLong("duration",300);
                    Path p = new Path(); p.moveTo(x1,y1); p.lineTo(x2,y2);
                    b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
                    dispatchGesture(b.build(), null, null);
                    break;
                }
            }
        } catch (Exception e) { Log.e(TAG, "Execute: " + e.getMessage()); }
    }

    // ── System prompt: assembles all context + history + stuck warning ─────────

    private String buildSystemPrompt(int sw, int sh) {
        StringBuilder sb = new StringBuilder(gameContext);
        sb.append("\n\nYou control this Android game via touch injection.");
        sb.append("\nRespond with ONLY JSON. First character must be {. No text. No markdown.");
        sb.append("\n\nScreen: ").append(sw).append("x").append(sh);
        sb.append("\n\nValid actions:");
        sb.append("\n{\"action\":\"tap\",\"x\":INT,\"y\":INT}");
        sb.append("\n{\"action\":\"swipe\",\"x1\":INT,\"y1\":INT,\"x2\":INT,\"y2\":INT,\"duration\":INT}");
        sb.append("\n{\"action\":\"wait\",\"ms\":INT}");
        sb.append("\n{\"action\":\"sequence\",\"steps\":[ACTION,...]} — for obvious multi-tap moves");
        sb.append("\n{\"action\":\"restart\",\"x\":INT,\"y\":INT} — tap play-again when game is over/won");

        // Feature 1: Action history
        if (!actionHistory.isEmpty()) {
            sb.append("\n\nRecent actions (oldest first):");
            int i = 1;
            for (String a : actionHistory) sb.append("\n").append(i++).append(". ").append(a);
        }

        // Feature 2: Stuck warning
        if (stuckCount >= STUCK_LIMIT) {
            sb.append("\n\n⚠️ STUCK: You have repeated the same action ").append(stuckCount)
              .append(" times and the screen has not changed. ")
              .append("You MUST try something completely different now.");
        }

        return sb.toString();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String buildActionKey(JSONObject action, String type) {
        switch (type) {
            case "tap":   return "tap:"   + action.optInt("x",0)/50 + ":" + action.optInt("y",0)/50;
            case "swipe": return "swipe:" + action.optInt("x1",0)/50+ ":" + action.optInt("y1",0)/50;
            default:      return type;
        }
    }

    private String md5(byte[] data) {
        try {
            return Base64.encodeToString(
                MessageDigest.getInstance("MD5").digest(data), Base64.NO_WRAP);
        } catch (Exception e) { return String.valueOf(data.length); }
    }

    // Feature 6: Notification channel + posting
    private void setupNotificationChannel() {
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "AI Game Agent", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("AI game agent status and controls");
        nm.createNotificationChannel(ch);
    }

    private void pushNotification(String text) {
        if (nm == null) return;
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPI = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

            Intent stop = new Intent(this, MainActivity.class);
            stop.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            stop.putExtra("stop_agent", true);
            PendingIntent stopPI = PendingIntent.getActivity(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AI Game Agent" + (running ? " ▶ Running" : " ■ Stopped"))
                .setContentText(text)
                .setContentIntent(openPI)
                .addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete),
                    "Stop", stopPI).build())
                .setOngoing(running)
                .build();

            nm.notify(NOTIF_ID, notif);
        } catch (Exception e) { Log.e(TAG, "Notification: " + e.getMessage()); }
    }

    private void log(String status, String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg;
        Log.d(TAG, line);
        mainHandler.post(() -> {
            if (logListener != null) logListener.onUpdate(status, line, totalCost, stepCount);
        });
    }
}
