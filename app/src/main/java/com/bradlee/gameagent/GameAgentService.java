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

    private static final String TAG        = "GameAgentService";
    private static final String CHANNEL_ID = "game_agent";
    private static final int    NOTIF_ID   = 1001;
    // Claude Sonnet 4.6 pricing
    private static final double COST_IN    = 3.0  / 1_000_000.0;
    private static final double COST_OUT   = 15.0 / 1_000_000.0;
    private static final int    STUCK_LIMIT  = 3;
    private static final int    MAX_HISTORY  = 5;
    // Image optimization: 35% scale, quality 65 saves ~60% vs 50%/75%
    private static final float  IMG_SCALE  = 0.35f;
    private static final int    IMG_QUALITY = 65;

    // Static fields accessed by MainActivity
    public static GameAgentService instance;
    public static String  apiKey        = "";
    public static String  gameContext   = "";
    public static int     loopDelayMs   = 2000;
    public static double  budgetLimitUsd = 1.0;
    public static int     gameCount     = 0;

    public interface LogListener {
        void onUpdate(String status, String line, double costUsd, int steps, int games);
    }
    public static LogListener logListener;

    private final Handler      mainHandler  = new Handler(Looper.getMainLooper());
    private       OkHttpClient http;
    private       NotificationManager nm;
    private       boolean  running     = false;
    private       int      stepCount   = 0;
    private       double   totalCost   = 0.0;
    private final LinkedList<String> actionHistory = new LinkedList<>();
    private       String   lastActionKey  = "";
    private       int      stuckCount     = 0;
    private       String   lastScreenHash = "";

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(
            new NotificationChannel(CHANNEL_ID, "AI Game Agent", NotificationManager.IMPORTANCE_LOW));
        log("READY", "Service connected ✓");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { stopAgent(); }
    @Override public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    // ── Agent control ──────────────────────────────────────────────────────────

    public void startAgent() {
        if (running) return;
        if (apiKey.isEmpty()) { log("ERROR", "Enter your API key first"); return; }
        if (gameContext.isEmpty()) { log("ERROR", "Enter game context first (or tap Generate)"); return; }
        running = true; stepCount = 0; totalCost = 0.0;
        lastActionKey = ""; stuckCount = 0; lastScreenHash = "";
        actionHistory.clear();
        log("RUNNING", "Agent started — budget limit $" + String.format("%.2f", budgetLimitUsd));
        notify("Starting...");
        scheduleStep(1000);
    }

    public void stopAgent() {
        if (!running && stepCount == 0) return;
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (nm != null) nm.cancel(NOTIF_ID);
        log("STOPPED", String.format("Done — %d steps, $%.4f spent", stepCount, totalCost));
    }

    public boolean isRunning() { return running; }
    public double getTotalCost() { return totalCost; }

    private void scheduleStep(long delay) {
        if (running) mainHandler.postDelayed(this::agentStep, delay);
    }

    // ── Screenshot + screen diff ───────────────────────────────────────────────

    private void agentStep() {
        if (!running) return;
        // Budget check before every API call
        if (budgetLimitUsd > 0 && totalCost >= budgetLimitUsd) {
            log("STOPPED", String.format("💰 Budget limit $%.2f reached! Stopping.", budgetLimitUsd));
            stopAgent(); return;
        }
        stepCount++;
        log("RUNNING", "Step " + stepCount + ": capturing...");

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                android.hardware.HardwareBuffer hb = result.getHardwareBuffer();
                Bitmap hw  = Bitmap.wrapHardwareBuffer(hb, null);
                Bitmap sw2 = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle(); hb.close();

                int W = (int)(sw2.getWidth()  * IMG_SCALE);
                int H = (int)(sw2.getHeight() * IMG_SCALE);
                Bitmap small = Bitmap.createScaledBitmap(sw2, W, H, true);
                sw2.recycle();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, IMG_QUALITY, baos);
                small.recycle();
                byte[] jpeg = baos.toByteArray();

                // Screen diff — skip if unchanged (saves money)
                String hash = md5(jpeg);
                if (hash.equals(lastScreenHash) && stepCount > 1) {
                    log("RUNNING", "Screen unchanged, waiting...");
                    scheduleStep(Math.min(500, loopDelayMs));
                    return;
                }
                lastScreenHash = hash;
                log("RUNNING", "Step " + stepCount + ": asking Claude... (" + jpeg.length/1024 + "KB)");
                callClaude(Base64.encodeToString(jpeg, Base64.NO_WRAP), W, H);
            }
            @Override
            public void onFailure(int errorCode) {
                log("RUNNING", "Screenshot failed (code " + errorCode + "), retrying...");
                scheduleStep(loopDelayMs);
            }
        });
    }

    // ── Claude Vision API ──────────────────────────────────────────────────────
    // Uses prompt caching on system prompt (free after 1st call within 5 min window)

    private void callClaude(String b64, int imgW, int imgH) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        try {
            // System prompt with cache_control for prompt caching
            // Reduces cost after first call — system prompt tokens become free
            JSONArray systemArr = new JSONArray();
            systemArr.put(new JSONObject()
                .put("type", "text")
                .put("text", buildSystemPrompt(screenW, screenH))
                .put("cache_control", new JSONObject().put("type", "ephemeral")));

            JSONObject msg = new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray()
                    .put(new JSONObject().put("type","image").put("source", new JSONObject()
                        .put("type","base64").put("media_type","image/jpeg").put("data",b64)))
                    .put(new JSONObject().put("type","text")
                        .put("text","Screen is " + imgW + "x" + imgH + "px (scaled). What action next?")));

            JSONObject payload = new JSONObject()
                .put("model", "claude-sonnet-4-6")
                .put("max_tokens", 60)
                .put("system", systemArr)
                .put("messages", new JSONArray().put(msg));

            Request req = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "prompt-caching-2024-07-31")
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
                        String bodyStr = body.string();
                        JSONObject json = new JSONObject(bodyStr);

                        // Track cost (cache reads are ~10x cheaper)
                        if (json.has("usage")) {
                            JSONObject u = json.getJSONObject("usage");
                            double cacheReadCost = u.optInt("cache_read_input_tokens",0) * (COST_IN * 0.1);
                            double normalCost    = u.optInt("input_tokens",0) * COST_IN
                                                 + u.optInt("output_tokens",0) * COST_OUT;
                            totalCost += cacheReadCost + normalCost;
                        }

                        if (json.has("error")) {
                            String msg2 = json.getJSONObject("error").getString("message");
                            log("RUNNING", "API error: " + msg2);
                            scheduleStep(loopDelayMs); return;
                        }

                        // Robust JSON extraction — finds first { ... last }
                        String raw = json.getJSONArray("content").getJSONObject(0)
                            .getString("text").trim()
                            .replace("```json","").replace("```","").trim();

                        int js = raw.indexOf('{'), je = raw.lastIndexOf('}');
                        if (js < 0 || je <= js) {
                            log("RUNNING", "No valid JSON returned, retrying...");
                            scheduleStep(loopDelayMs); return;
                        }
                        raw = raw.substring(js, je + 1);
                        JSONObject action = new JSONObject(raw);
                        String type = action.optString("action","tap");

                        // Stuck detection (round coords to 40px grid)
                        String aKey = type + ":" + action.optInt("x",0)/40
                                           + ":" + action.optInt("y",0)/40;
                        if (aKey.equals(lastActionKey)) stuckCount++;
                        else { stuckCount = 0; lastActionKey = aKey; }

                        // Action history
                        actionHistory.addLast(raw);
                        if (actionHistory.size() > MAX_HISTORY) actionHistory.removeFirst();

                        log("RUNNING", String.format("[%s] %s  $%.4f", type, raw, totalCost));
                        notify(String.format("Step %d • $%.4f", stepCount, totalCost));

                        mainHandler.post(() -> dispatch(action, type));

                    } catch (Exception e) {
                        log("RUNNING", "Parse error: " + e.getMessage());
                        scheduleStep(loopDelayMs);
                    }
                }
            });
        } catch (Exception e) {
            log("RUNNING", "Request build error: " + e.getMessage());
            scheduleStep(loopDelayMs);
        }
    }

    // ── Action dispatch ────────────────────────────────────────────────────────

    private void dispatch(JSONObject action, String type) {
        switch (type) {
            case "sequence":
                try { executeSequence(action.getJSONArray("steps"), 0); }
                catch (Exception e) { scheduleStep(loopDelayMs); }
                break;
            case "restart":
                handleRestart(action);
                break;
            case "wait":
                scheduleStep(action.optLong("ms", loopDelayMs));
                break;
            default:
                execute(action);
                // Adaptive delay: swipes need longer wait for animation
                long delay = "swipe".equals(type) ? (long)(loopDelayMs * 1.3) : loopDelayMs;
                scheduleStep(delay);
                break;
        }
    }

    private void executeSequence(JSONArray steps, int idx) {
        if (idx >= steps.length()) { scheduleStep(loopDelayMs); return; }
        try {
            execute(steps.getJSONObject(idx));
            mainHandler.postDelayed(() -> executeSequence(steps, idx+1), 550);
        } catch (Exception e) { scheduleStep(loopDelayMs); }
    }

    private void handleRestart(JSONObject action) {
        try {
            gameCount++;
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            JSONObject tap = new JSONObject()
                .put("action","tap")
                .put("x", action.optInt("x", sw/2))
                .put("y", action.optInt("y", sh/2));
            execute(tap);
            actionHistory.clear();
            lastActionKey = ""; stuckCount = 0; lastScreenHash = "";
            log("RUNNING", "♻️ Game " + gameCount + " started — restarted!");
            scheduleStep(loopDelayMs * 2);
        } catch (Exception e) { scheduleStep(loopDelayMs); }
    }

    private void execute(JSONObject action) {
        try {
            String t = action.getString("action");
            GestureDescription.Builder b = new GestureDescription.Builder();
            switch (t) {
                case "tap": {
                    float x=(float)action.getDouble("x"), y=(float)action.getDouble("y");
                    Path p=new Path(); p.moveTo(x,y);
                    b.addStroke(new GestureDescription.StrokeDescription(p,0,60));
                    dispatchGesture(b.build(),null,null); break;
                }
                case "swipe": {
                    float x1=(float)action.getDouble("x1"),y1=(float)action.getDouble("y1");
                    float x2=(float)action.getDouble("x2"),y2=(float)action.getDouble("y2");
                    long  dur = action.optLong("duration",350);
                    Path p=new Path(); p.moveTo(x1,y1); p.lineTo(x2,y2);
                    b.addStroke(new GestureDescription.StrokeDescription(p,0,dur));
                    dispatchGesture(b.build(),null,null); break;
                }
            }
        } catch (Exception e) { Log.e(TAG,"Execute: "+e.getMessage()); }
    }

    // ── System prompt ──────────────────────────────────────────────────────────
    // Kept concise to minimize input tokens (fewer tokens = less cost)

    private String buildSystemPrompt(int sw, int sh) {
        StringBuilder sb = new StringBuilder();
        sb.append(gameContext.trim());
        sb.append("\n\n---\nOutput ONLY a single JSON object. Start with {. No text, no explanation.\nScreen: ")
          .append(sw).append("x").append(sh);

        // Minimal action format — reduces input tokens vs verbose descriptions
        sb.append("\nActions:\n{\"action\":\"tap\",\"x\":N,\"y\":N}\n")
          .append("{\"action\":\"swipe\",\"x1\":N,\"y1\":N,\"x2\":N,\"y2\":N,\"duration\":N}\n")
          .append("{\"action\":\"sequence\",\"steps\":[...]}\n")
          .append("{\"action\":\"restart\",\"x\":N,\"y\":N}\n");

        if (!actionHistory.isEmpty()) {
            sb.append("\nLast moves: ");
            sb.append(String.join(" → ", actionHistory));
        }

        if (stuckCount >= STUCK_LIMIT) {
            sb.append("\n\n⚠️ STUCK x").append(stuckCount)
              .append(" — game state hasn't changed. Try something DIFFERENT.");
        }

        return sb.toString();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String md5(byte[] d) {
        try { return Base64.encodeToString(MessageDigest.getInstance("MD5").digest(d), Base64.NO_WRAP); }
        catch (Exception e) { return String.valueOf(d.length); }
    }

    private void notify(String text) {
        if (nm == null) return;
        try {
            Intent open = new Intent(this,MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Intent stop = new Intent(this,MainActivity.class);
            stop.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            stop.putExtra("stop_agent",true);
            nm.notify(NOTIF_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AI Game Agent" + (running ? " ▶" : " ■"))
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop",
                    PendingIntent.getActivity(this,1,stop,
                        PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .setOngoing(running).build());
        } catch (Exception e) { Log.e(TAG,"Notif: "+e.getMessage()); }
    }

    private void log(String status, String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg;
        Log.d(TAG, line);
        mainHandler.post(() -> {
            if (logListener != null)
                logListener.onUpdate(status, line, totalCost, stepCount, gameCount);
        });
    }
}
