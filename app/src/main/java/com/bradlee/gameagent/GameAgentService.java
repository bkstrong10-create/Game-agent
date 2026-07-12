package com.bradlee.gameagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
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
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private static final String TAG = "GameAgentService";

    // Accessed by MainActivity
    public static GameAgentService instance;
    public static String  apiKey      = "";
    public static String  gameContext = "Play this game as well as you can.";
    public static int     loopDelayMs = 2000;

    public interface LogListener {
        void onUpdate(String status, String line);
    }
    public static LogListener logListener;

    private final Handler      mainHandler = new Handler(Looper.getMainLooper());
    private       OkHttpClient http;
    private       boolean      running     = false;
    private       int          stepCount   = 0;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        log("READY", "Service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { stopAgent(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        stopAgent();
    }

    // ── Control ──────────────────────────────────────────────────────────────

    public void startAgent() {
        if (running) return;
        if (apiKey.isEmpty()) { log("ERROR", "API key not set"); return; }
        running   = true;
        stepCount = 0;
        log("RUNNING", "Agent started");
        scheduleStep(800);
    }

    public void stopAgent() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        log("STOPPED", "Agent stopped");
    }

    public boolean isRunning() { return running; }

    private void scheduleStep(long delay) {
        if (running) mainHandler.postDelayed(this::agentStep, delay);
    }

    // ── Main loop ────────────────────────────────────────────────────────────

    private void agentStep() {
        if (!running) return;
        stepCount++;
        log("RUNNING", "Step " + stepCount + ": capturing screen...");

        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                // HardwareBuffer → software Bitmap (hardware bitmaps can't be compressed directly)
                HardwareBuffer hb  = result.getHardwareBuffer();
                Bitmap         hw  = Bitmap.wrapHardwareBuffer(hb, null);
                Bitmap         sw  = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
                hb.close();

                // Scale to 50% to reduce API payload
                Bitmap small = Bitmap.createScaledBitmap(sw, sw.getWidth() / 2, sw.getHeight() / 2, true);
                sw.recycle();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                small.recycle();

                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                log("RUNNING", "Step " + stepCount + ": asking Claude...");
                callClaude(b64);
            }

            @Override
            public void onFailure(int errorCode) {
                log("RUNNING", "Screenshot failed (code=" + errorCode + "), retrying...");
                scheduleStep(loopDelayMs);
            }
        });
    }

    // ── Claude Vision API ────────────────────────────────────────────────────

    private void callClaude(String b64) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        String system = gameContext
            + "\n\nYou are controlling this Android game via touch injection."
            + " Analyze the screenshot and respond with ONLY a JSON action. No markdown. No explanation. JSON only."
            + "\n\nScreen resolution: " + sw + "x" + sh + " — all coordinates must be within bounds."
            + "\n\nValid actions:"
            + "\n{\"action\":\"tap\",\"x\":INT,\"y\":INT}"
            + "\n{\"action\":\"swipe\",\"x1\":INT,\"y1\":INT,\"x2\":INT,\"y2\":INT,\"duration\":INT}"
            + "\n{\"action\":\"wait\",\"ms\":INT}";

        try {
            JSONObject imgBlock = new JSONObject()
                .put("type", "image")
                .put("source", new JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", b64));

            JSONObject txtBlock = new JSONObject()
                .put("type", "text")
                .put("text", "What action next?");

            JSONObject msg = new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray().put(imgBlock).put(txtBlock));

            JSONObject payload = new JSONObject()
                .put("model", "claude-sonnet-4-6")
                .put("max_tokens", 128)
                .put("system", system)
                .put("messages", new JSONArray().put(msg));

            Request req = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(RequestBody.create(payload.toString(), MediaType.get("application/json")))
                .build();

            http.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log("RUNNING", "Network error: " + e.getMessage());
                    scheduleStep(loopDelayMs);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    ResponseBody body = response.body();
                    if (body == null) {
                        log("RUNNING", "Empty response");
                        scheduleStep(loopDelayMs);
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(body.string());
                        if (json.has("error")) {
                            log("RUNNING", "Claude: " + json.getJSONObject("error").getString("message"));
                            scheduleStep(loopDelayMs);
                            return;
                        }
                        String raw = json.getJSONArray("content")
                            .getJSONObject(0).getString("text")
                            .trim().replace("```json","").replace("```","").trim();

                        log("RUNNING", "Action: " + raw);
                        JSONObject action = new JSONObject(raw);

                        mainHandler.post(() -> {
                            execute(action);
                            long delay = "wait".equals(action.optString("action"))
                                ? action.optLong("ms", loopDelayMs)
                                : loopDelayMs;
                            scheduleStep(delay);
                        });
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

    // ── Gesture execution ────────────────────────────────────────────────────

    private void execute(JSONObject action) {
        try {
            String type = action.getString("action");
            GestureDescription.Builder b = new GestureDescription.Builder();
            switch (type) {
                case "tap": {
                    float x = (float) action.getDouble("x");
                    float y = (float) action.getDouble("y");
                    Path p = new Path(); p.moveTo(x, y);
                    b.addStroke(new GestureDescription.StrokeDescription(p, 0, 50));
                    dispatchGesture(b.build(), null, null);
                    break;
                }
                case "swipe": {
                    float x1=(float)action.getDouble("x1"), y1=(float)action.getDouble("y1");
                    float x2=(float)action.getDouble("x2"), y2=(float)action.getDouble("y2");
                    long  dur=action.optLong("duration", 300);
                    Path p = new Path(); p.moveTo(x1,y1); p.lineTo(x2,y2);
                    b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
                    dispatchGesture(b.build(), null, null);
                    break;
                }
                case "wait": break; // handled by scheduleStep delay
                default: Log.w(TAG, "Unknown action: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "Execute: " + e.getMessage());
        }
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private void log(String status, String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg;
        Log.d(TAG, line);
        mainHandler.post(() -> { if (logListener != null) logListener.onUpdate(status, line); });
    }
}
