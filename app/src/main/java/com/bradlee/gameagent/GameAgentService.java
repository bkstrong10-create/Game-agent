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
    private static final String CHANNEL_ID = "game_agent";
    private static final int NOTIF_ID = 1001;

    private static final String PRIMARY_MODEL = "gpt-5.6";
    private static final String FALLBACK_MODEL = "gpt-5.5";

    // Estimated short-context standard pricing for the gpt-5.6 alias.
    private static final double COST_INPUT = 5.00 / 1_000_000.0;
    private static final double COST_CACHED_INPUT = 0.50 / 1_000_000.0;
    private static final double COST_OUTPUT = 30.00 / 1_000_000.0;

    private static final int MAX_ACTIONS_PER_TURN = 4;
    private static final int REPEAT_BLOCK_LIMIT = 2;

    public static GameAgentService instance;
    public static String apiKey = "";
    public static String gameContext = "";
    public static int loopDelayMs = 2000;
    public static double budgetLimitUsd = 1.0;
    public static int gameCount = 0;

    public interface LogListener {
        void onUpdate(String status, String line, double costUsd, int steps, int games);
    }

    public static LogListener logListener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient http;
    private NotificationManager notificationManager;
    private volatile boolean running = false;
    private volatile long runToken = 0L;
    private volatile Call inFlightCall;

    private int stepCount = 0;
    private double totalCost = 0.0;
    private String activeModel = PRIMARY_MODEL;
    private boolean fallbackTried = false;

    private int captureWidth = 1;
    private int captureHeight = 1;
    private String currentScreenHash = "";
    private String lastGestureKey = "";
    private String lastGestureScreenHash = "";
    private int repeatedSameGesture = 0;

    private final SimpleDateFormat sdf =
        new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        http = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build();
        notificationManager =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(
                new NotificationChannel(
                    CHANNEL_ID,
                    "AI Game Agent",
                    NotificationManager.IMPORTANCE_LOW));
        }
        log("READY", "OpenAI computer-use service connected ✓");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stopAgent();
    }

    @Override
    public void onDestroy() {
        cancelCurrentRun(false);
        super.onDestroy();
        instance = null;
        if (notificationManager != null) notificationManager.cancel(NOTIF_ID);
    }

    public void startAgent() {
        if (running) return;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log("ERROR", "Enter an OpenAI API key first");
            return;
        }
        if (apiKey.startsWith("sk-ant-")) {
            log("ERROR", "Anthropic key detected — enter an OpenAI API key");
            return;
        }
        if (gameContext == null || gameContext.trim().isEmpty()) {
            log("ERROR", "Enter game instructions first, or tap Generate");
            return;
        }

        running = true;
        long token = ++runToken;
        stepCount = 0;
        totalCost = 0.0;
        activeModel = PRIMARY_MODEL;
        fallbackTried = false;
        currentScreenHash = "";
        lastGestureKey = "";
        lastGestureScreenHash = "";
        repeatedSameGesture = 0;

        log("RUNNING", "Starting OpenAI computer-use agent — model " + activeModel);
        log("RUNNING", "Full-resolution screenshots + direct pixel coordinates enabled");
        pushNotification("Starting OpenAI agent...");
        sendInitialRequest(token);
    }

    public void stopAgent() {
        cancelCurrentRun(true);
    }

    private void cancelCurrentRun(boolean showStoppedLog) {
        boolean wasRunning = running;
        running = false;
        runToken++;
        mainHandler.removeCallbacksAndMessages(null);
        Call call = inFlightCall;
        inFlightCall = null;
        if (call != null) call.cancel();
        if (notificationManager != null) notificationManager.cancel(NOTIF_ID);
        if (showStoppedLog && (wasRunning || stepCount > 0)) {
            log("STOPPED", String.format(Locale.US,
                "Done — %d computer turns, $%.4f estimated", stepCount, totalCost));
        }
    }

    public boolean isRunning() {
        return running;
    }

    public double getTotalCost() {
        return totalCost;
    }

    private boolean isActive(long token) {
        return running && token == runToken;
    }

    private void sendInitialRequest(long token) {
        if (!isActive(token)) return;
        if (!checkBudget()) return;
        try {
            JSONObject payload = new JSONObject()
                .put("model", activeModel)
                .put("tools", new JSONArray().put(
                    new JSONObject().put("type", "computer")))
                .put("input", buildComputerTask())
                .put("store", true);

            log("RUNNING", "Opening OpenAI computer-use session...");
            sendPayload(payload, token, "initial request");
        } catch (Exception e) {
            failRun("Could not build OpenAI request: " + e.getMessage());
        }
    }

    private String buildComputerTask() {
        return "Control this Android phone to play the current game using the computer tool.\n\n"
            + "GAME INSTRUCTIONS:\n" + gameContext.trim() + "\n\n"
            + "OPERATING RULES:\n"
            + "- Use the computer tool for every UI action. Do not return raw JSON coordinates.\n"
            + "- The screenshot's exact pixel dimensions are the coordinate system.\n"
            + "- Click near the center of a visible card or button, not its edge.\n"
            + "- Perform at most one meaningful click or drag, then inspect a new screenshot.\n"
            + "- If the screen did not change, do not repeat the identical action; choose another target.\n"
            + "- Stay inside the game. Do not open notifications, settings, ads, chats, or external links.\n"
            + "- Never make a purchase, spend real money, or confirm an account action.\n"
            + "- Ask for a screenshot first, then continue playing until the user stops the agent.";
    }

    private void sendPayload(JSONObject payload, long token, String label) {
        if (!isActive(token)) return;
        if (!checkBudget()) return;

        try {
            Request request = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(
                    payload.toString(), MediaType.get("application/json")))
                .build();

            Call call = http.newCall(request);
            inFlightCall = call;
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (!isActive(token) || call.isCanceled()) return;
                    log("RUNNING", "Network error during " + label + ": " + e.getMessage());
                    mainHandler.postDelayed(
                        () -> sendPayload(payload, token, label),
                        Math.max(2000, loopDelayMs));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    ResponseBody body = response.body();
                    String bodyText = body == null ? "" : body.string();
                    if (!isActive(token)) return;

                    try {
                        JSONObject json = bodyText.isEmpty()
                            ? new JSONObject()
                            : new JSONObject(bodyText);

                        if (!response.isSuccessful() || json.has("error")) {
                            String message = extractApiError(json, response.code());
                            if (tryFallbackModel(message, token)) return;

                            if (response.code() == 401 || response.code() == 403) {
                                failRun("OpenAI key/access error: " + message);
                                return;
                            }
                            if (response.code() == 429) {
                                log("RUNNING", "OpenAI rate limit — waiting 8 seconds");
                                mainHandler.postDelayed(
                                    () -> sendPayload(payload, token, label), 8000);
                                return;
                            }

                            log("RUNNING", "OpenAI API error: " + message);
                            mainHandler.postDelayed(
                                () -> sendPayload(payload, token, label),
                                Math.max(3000, loopDelayMs));
                            return;
                        }

                        updateEstimatedCost(json);
                        handleComputerResponse(json, token);
                    } catch (Exception e) {
                        log("RUNNING", "OpenAI response parse error: " + e.getMessage());
                        mainHandler.postDelayed(
                            () -> sendPayload(payload, token, label),
                            Math.max(2500, loopDelayMs));
                    }
                }
            });
        } catch (Exception e) {
            failRun("OpenAI request error: " + e.getMessage());
        }
    }

    private boolean tryFallbackModel(String message, long token) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        boolean modelProblem = lower.contains("model")
            && (lower.contains("not found")
                || lower.contains("does not exist")
                || lower.contains("access")
                || lower.contains("unsupported"));
        if (!fallbackTried && PRIMARY_MODEL.equals(activeModel) && modelProblem) {
            fallbackTried = true;
            activeModel = FALLBACK_MODEL;
            log("RUNNING", "Trying compatible fallback model " + activeModel);
            mainHandler.postDelayed(() -> sendInitialRequest(token), 1000);
            return true;
        }
        return false;
    }

    private String extractApiError(JSONObject json, int statusCode) {
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "");
            if (!message.isEmpty()) return message;
        }
        return "HTTP " + statusCode;
    }

    private void handleComputerResponse(JSONObject json, long token) {
        if (!isActive(token)) return;

        String responseId = json.optString("id", "");
        JSONArray output = json.optJSONArray("output");
        JSONObject computerCall = null;
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item != null && "computer_call".equals(item.optString("type"))) {
                    computerCall = item;
                    break;
                }
            }
        }

        if (computerCall == null) {
            String finalText = extractOutputText(json);
            if (!finalText.isEmpty()) {
                log("RUNNING", "OpenAI: " + finalText);
            } else {
                log("RUNNING", "OpenAI ended the computer turn; starting a fresh turn");
            }
            mainHandler.postDelayed(() -> sendInitialRequest(token),
                Math.max(1000, loopDelayMs));
            return;
        }

        String callId = computerCall.optString("call_id", "");
        JSONArray actions = computerCall.optJSONArray("actions");
        if (responseId.isEmpty() || callId.isEmpty()) {
            log("RUNNING", "Computer response was missing an ID; restarting session");
            mainHandler.postDelayed(() -> sendInitialRequest(token),
                Math.max(1000, loopDelayMs));
            return;
        }

        stepCount++;
        int actionCount = actions == null ? 0 : actions.length();
        log("RUNNING", "Computer turn " + stepCount + " — " + actionCount
            + " action(s), $" + String.format(Locale.US, "%.4f", totalCost) + " est.");
        pushNotification("Turn " + stepCount + " • $"
            + String.format(Locale.US, "%.4f", totalCost) + " est.");

        JSONArray safeActions = actions == null ? new JSONArray() : actions;
        executeActions(safeActions, 0, token, () ->
            mainHandler.postDelayed(
                () -> captureAndReply(responseId, callId, token),
                Math.max(300, loopDelayMs)));
    }

    private void executeActions(
            JSONArray actions,
            int index,
            long token,
            Runnable onComplete) {
        if (!isActive(token)) return;
        if (index >= actions.length() || index >= MAX_ACTIONS_PER_TURN) {
            if (actions.length() > MAX_ACTIONS_PER_TURN
                    && index >= MAX_ACTIONS_PER_TURN) {
                log("RUNNING", "Safety cap: remaining batched actions deferred");
            }
            onComplete.run();
            return;
        }

        JSONObject action = actions.optJSONObject(index);
        if (action == null) {
            executeActions(actions, index + 1, token, onComplete);
            return;
        }

        String type = action.optString("type", "screenshot");
        Runnable next = () -> executeActions(actions, index + 1, token, onComplete);

        switch (type) {
            case "screenshot":
                next.run();
                break;
            case "click":
                executeClick(action, token, next);
                break;
            case "double_click":
                executeDoubleClick(action, token, next);
                break;
            case "drag":
                executeDrag(action, token, next);
                break;
            case "scroll":
                executeScroll(action, token, next);
                break;
            case "wait":
                long waitMs = Math.max(250,
                    Math.min(5000, action.optLong("ms", 1200)));
                log("RUNNING", "WAIT " + waitMs + "ms");
                mainHandler.postDelayed(next, waitMs);
                break;
            case "keypress":
                executeKeypress(action, next);
                break;
            case "move":
                next.run();
                break;
            case "type":
                log("RUNNING", "TYPE skipped — game agent does not enter text");
                next.run();
                break;
            default:
                log("RUNNING", "Unsupported computer action: " + type);
                next.run();
                break;
        }
    }

    private void executeClick(JSONObject action, long token, Runnable next) {
        float x = clampX(readNumber(action, "x", captureWidth / 2.0));
        float y = clampY(readNumber(action, "y", captureHeight / 2.0));
        String key = String.format(Locale.US, "click:%.0f:%.0f", x, y);
        if (shouldBlockRepeatedGesture(key)) {
            log("RUNNING", "BLOCKED repeated click on unchanged screen at px("
                + Math.round(x) + "," + Math.round(y) + ")");
            mainHandler.postDelayed(next, 250);
            return;
        }

        rememberGesture(key);
        dispatchTap(x, y, token,
            "CLICK px(" + Math.round(x) + "," + Math.round(y) + ")", next);
    }

    private void executeDoubleClick(JSONObject action, long token, Runnable next) {
        float x = clampX(readNumber(action, "x", captureWidth / 2.0));
        float y = clampY(readNumber(action, "y", captureHeight / 2.0));
        String key = String.format(Locale.US, "double:%.0f:%.0f", x, y);
        if (shouldBlockRepeatedGesture(key)) {
            log("RUNNING", "BLOCKED repeated double-click on unchanged screen");
            mainHandler.postDelayed(next, 250);
            return;
        }

        rememberGesture(key);
        dispatchTap(x, y, token,
            "DOUBLE CLICK 1/2 px(" + Math.round(x) + "," + Math.round(y) + ")",
            () -> mainHandler.postDelayed(
                () -> dispatchTap(x, y, token,
                    "DOUBLE CLICK 2/2 px(" + Math.round(x) + "," + Math.round(y) + ")",
                    next),
                140));
    }

    private void executeDrag(JSONObject action, long token, Runnable next) {
        JSONArray points = action.optJSONArray("path");
        if (points == null || points.length() < 2) {
            log("RUNNING", "DRAG skipped — path was missing");
            next.run();
            return;
        }

        Path path = new Path();
        StringBuilder keyBuilder = new StringBuilder("drag");
        int used = 0;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.optJSONObject(i);
            if (point == null) continue;
            float x = clampX(readNumber(point, "x", captureWidth / 2.0));
            float y = clampY(readNumber(point, "y", captureHeight / 2.0));
            if (used == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
            if (i == 0 || i == points.length() - 1) {
                keyBuilder.append(':').append(Math.round(x))
                    .append(':').append(Math.round(y));
            }
            used++;
        }
        if (used < 2) {
            log("RUNNING", "DRAG skipped — fewer than two valid points");
            next.run();
            return;
        }

        String key = keyBuilder.toString();
        if (shouldBlockRepeatedGesture(key)) {
            log("RUNNING", "BLOCKED repeated drag on unchanged screen");
            mainHandler.postDelayed(next, 250);
            return;
        }

        rememberGesture(key);
        long duration = Math.max(250, Math.min(1200, used * 110L));
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
            .build();
        dispatchGestureWithCallback(gesture, token,
            "DRAG " + used + " points, " + duration + "ms", next);
    }

    private void executeScroll(JSONObject action, long token, Runnable next) {
        float x = clampX(readNumber(action, "x", captureWidth / 2.0));
        float y = clampY(readNumber(action, "y", captureHeight / 2.0));
        double scrollY = readNumber(action, "scrollY", 500);
        float distance = (float) Math.max(220,
            Math.min(captureHeight * 0.55, Math.abs(scrollY)));
        float endY = scrollY >= 0 ? clampY(y - distance) : clampY(y + distance);
        String key = String.format(Locale.US,
            "scroll:%.0f:%.0f:%.0f", x, y, endY);
        if (shouldBlockRepeatedGesture(key)) {
            log("RUNNING", "BLOCKED repeated scroll on unchanged screen");
            mainHandler.postDelayed(next, 250);
            return;
        }

        rememberGesture(key);
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 450))
            .build();
        dispatchGestureWithCallback(gesture, token,
            "SCROLL px(" + Math.round(x) + "," + Math.round(y)
                + ")->(" + Math.round(x) + "," + Math.round(endY) + ")",
            next);
    }

    private void executeKeypress(JSONObject action, Runnable next) {
        JSONArray keys = action.optJSONArray("keys");
        boolean handled = false;
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i, "").toUpperCase(Locale.US);
                if ("BACK".equals(key) || "ESC".equals(key)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    log("RUNNING", "KEYPRESS BACK");
                    handled = true;
                    break;
                }
            }
        }
        if (!handled) log("RUNNING", "KEYPRESS skipped");
        mainHandler.postDelayed(next, 300);
    }

    private boolean shouldBlockRepeatedGesture(String key) {
        if (key.equals(lastGestureKey)
                && currentScreenHash.equals(lastGestureScreenHash)
                && !currentScreenHash.isEmpty()) {
            repeatedSameGesture++;
        } else {
            repeatedSameGesture = 0;
        }
        return repeatedSameGesture >= REPEAT_BLOCK_LIMIT;
    }

    private void rememberGesture(String key) {
        lastGestureKey = key;
        lastGestureScreenHash = currentScreenHash;
    }

    private void dispatchTap(
            float x,
            float y,
            long token,
            String description,
            Runnable onComplete) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 90))
            .build();
        dispatchGestureWithCallback(gesture, token, description, onComplete);
    }

    private void dispatchGestureWithCallback(
            GestureDescription gesture,
            long token,
            String description,
            Runnable onComplete) {
        if (!isActive(token)) return;
        boolean accepted = dispatchGesture(
            gesture,
            new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    if (!isActive(token)) return;
                    log("RUNNING", "✓ " + description);
                    mainHandler.postDelayed(onComplete, 220);
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    if (!isActive(token)) return;
                    log("RUNNING", "✗ Gesture cancelled: " + description);
                    mainHandler.postDelayed(onComplete, 350);
                }
            },
            mainHandler);

        if (!accepted && isActive(token)) {
            log("RUNNING", "✗ Android rejected gesture: " + description);
            mainHandler.postDelayed(onComplete, 350);
        }
    }

    private void captureAndReply(String responseId, String callId, long token) {
        if (!isActive(token)) return;
        if (!checkBudget()) return;

        log("RUNNING", "Capturing full-resolution feedback screenshot...");
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    if (!isActive(token)) {
                        result.getHardwareBuffer().close();
                        return;
                    }

                    android.hardware.HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hardwareBitmap = null;
                    Bitmap bitmap = null;
                    try {
                        hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, null);
                        if (hardwareBitmap == null) {
                            throw new IllegalStateException("Could not wrap screenshot buffer");
                        }
                        bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        if (bitmap == null) {
                            throw new IllegalStateException("Could not copy screenshot bitmap");
                        }

                        captureWidth = Math.max(1, bitmap.getWidth());
                        captureHeight = Math.max(1, bitmap.getHeight());

                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        byte[] png = stream.toByteArray();
                        String newHash = sha256(png);
                        boolean changed = !newHash.equals(currentScreenHash);
                        currentScreenHash = newHash;
                        if (changed) repeatedSameGesture = 0;

                        String base64 = Base64.encodeToString(png, Base64.NO_WRAP);
                        log("RUNNING", "Screenshot " + captureWidth + "x" + captureHeight
                            + " • " + (png.length / 1024) + "KB • screen "
                            + (changed ? "changed" : "unchanged"));

                        JSONObject screenshot = new JSONObject()
                            .put("type", "computer_screenshot")
                            .put("image_url", "data:image/png;base64," + base64)
                            .put("detail", "original");
                        JSONObject callOutput = new JSONObject()
                            .put("type", "computer_call_output")
                            .put("call_id", callId)
                            .put("output", screenshot);
                        JSONObject payload = new JSONObject()
                            .put("model", activeModel)
                            .put("tools", new JSONArray().put(
                                new JSONObject().put("type", "computer")))
                            .put("previous_response_id", responseId)
                            .put("input", new JSONArray().put(callOutput))
                            .put("store", true);

                        sendPayload(payload, token, "computer screenshot reply");
                    } catch (Exception e) {
                        log("RUNNING", "Screenshot processing error: " + e.getMessage());
                        mainHandler.postDelayed(
                            () -> captureAndReply(responseId, callId, token),
                            Math.max(1500, loopDelayMs));
                    } finally {
                        if (bitmap != null) bitmap.recycle();
                        if (hardwareBitmap != null) hardwareBitmap.recycle();
                        buffer.close();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    if (!isActive(token)) return;
                    log("RUNNING", "Screenshot failed (code " + errorCode + "), retrying");
                    mainHandler.postDelayed(
                        () -> captureAndReply(responseId, callId, token),
                        Math.max(1500, loopDelayMs));
                }
            });
    }

    private boolean checkBudget() {
        if (budgetLimitUsd > 0 && totalCost >= budgetLimitUsd) {
            log("RUNNING", String.format(Locale.US,
                "Budget limit $%.2f reached — stopping", budgetLimitUsd));
            cancelCurrentRun(true);
            return false;
        }
        return true;
    }

    private void updateEstimatedCost(JSONObject json) {
        JSONObject usage = json.optJSONObject("usage");
        if (usage == null) return;
        long inputTokens = usage.optLong("input_tokens", 0);
        long outputTokens = usage.optLong("output_tokens", 0);
        JSONObject details = usage.optJSONObject("input_tokens_details");
        long cachedTokens = details == null ? 0 : details.optLong("cached_tokens", 0);
        cachedTokens = Math.max(0, Math.min(inputTokens, cachedTokens));
        long uncachedTokens = Math.max(0, inputTokens - cachedTokens);
        totalCost += uncachedTokens * COST_INPUT
            + cachedTokens * COST_CACHED_INPUT
            + outputTokens * COST_OUTPUT;
    }

    private String extractOutputText(JSONObject json) {
        JSONArray output = json.optJSONArray("output");
        if (output == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    if (result.length() > 0) result.append(' ');
                    result.append(part.optString("text"));
                }
            }
        }
        return result.toString().trim();
    }

    private double readNumber(JSONObject object, String key, double fallback) {
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return fallback;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private float clampX(double value) {
        return (float) Math.max(0,
            Math.min(Math.max(0, captureWidth - 1), value));
    }

    private float clampY(double value) {
        return (float) Math.max(0,
            Math.min(Math.max(0, captureHeight - 1), value));
    }

    private String sha256(byte[] data) {
        try {
            return Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(data),
                Base64.NO_WRAP);
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    private void failRun(String message) {
        log("ERROR", message);
        cancelCurrentRun(false);
    }

    private void pushNotification(String text) {
        if (notificationManager == null) return;
        try {
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Intent stopIntent = new Intent(this, MainActivity.class);
            stopIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            stopIntent.putExtra("stop_agent", true);

            notificationManager.notify(
                NOTIF_ID,
                new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("AI Game Agent — OpenAI"
                        + (running ? " ▶" : " ■"))
                    .setContentText(text)
                    .setContentIntent(PendingIntent.getActivity(
                        this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE))
                    .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(
                            this, android.R.drawable.ic_delete),
                        "Stop",
                        PendingIntent.getActivity(
                            this,
                            1,
                            stopIntent,
                            PendingIntent.FLAG_IMMUTABLE
                                | PendingIntent.FLAG_UPDATE_CURRENT))
                        .build())
                    .setOngoing(running)
                    .build());
        } catch (Exception e) {
            Log.e(TAG, "Notification error: " + e.getMessage());
        }
    }

    private void log(String status, String message) {
        String line = "[" + sdf.format(new Date()) + "] " + message;
        Log.d(TAG, line);
        mainHandler.post(() -> {
            if (logListener != null) {
                logListener.onUpdate(
                    status, line, totalCost, stepCount, gameCount);
            }
        });
    }
}
