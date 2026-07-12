package com.bradlee.gameagent;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

    private EditText etApiKey, etGameName, etGameContext, etDelay;
    private Button   btnStartStop, btnAccessibility, btnGenerate, btnSaveProfile, btnLoadProfile;
    private TextView tvStatus, tvCost, tvLog;
    private SharedPreferences prefs;
    private final ArrayDeque<String> logBuf = new ArrayDeque<>();
    private static final int MAX_LINES = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etApiKey         = findViewById(R.id.etApiKey);
        etGameName       = findViewById(R.id.etGameName);
        etGameContext    = findViewById(R.id.etGameContext);
        etDelay          = findViewById(R.id.etDelay);
        btnStartStop     = findViewById(R.id.btnStartStop);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnGenerate      = findViewById(R.id.btnGenerate);
        btnSaveProfile   = findViewById(R.id.btnSaveProfile);
        btnLoadProfile   = findViewById(R.id.btnLoadProfile);
        tvStatus         = findViewById(R.id.tvStatus);
        tvCost           = findViewById(R.id.tvCost);
        tvLog            = findViewById(R.id.tvLog);

        prefs = getSharedPreferences("GameAgent", MODE_PRIVATE);
        etApiKey.setText(prefs.getString("api_key", ""));
        etGameName.setText(prefs.getString("game_name", ""));
        etGameContext.setText(prefs.getString("game_context", ""));
        etDelay.setText(String.valueOf(prefs.getInt("delay", 2500)));

        btnAccessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btnStartStop.setOnClickListener(v -> toggleAgent());
        btnGenerate.setOnClickListener(v -> generateContext());
        btnSaveProfile.setOnClickListener(v -> saveProfile());
        btnLoadProfile.setOnClickListener(v -> loadProfile());

        // Connect to service — receives status, log, cost, step count
        GameAgentService.logListener = (status, line, costUsd, steps) -> runOnUiThread(() -> {
            logBuf.addLast(line);
            if (logBuf.size() > MAX_LINES) logBuf.removeFirst();
            tvLog.setText(String.join("\n", logBuf));
            tvCost.setText(String.format("$%.4f • Step %d", costUsd, steps));
            applyStatus(status);
        });

        // Feature 6: Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }

        handleStopIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleStopIntent(intent);
    }

    // Feature 6: Handle "Stop" tap from notification
    private void handleStopIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("stop_agent", false)) {
            if (GameAgentService.instance != null && GameAgentService.instance.isRunning()) {
                GameAgentService.instance.stopAgent();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibilityBtn();
        if (GameAgentService.instance != null)
            applyStatus(GameAgentService.instance.isRunning() ? "RUNNING" : "READY");
    }

    @Override protected void onDestroy() { super.onDestroy(); GameAgentService.logListener = null; }

    // ── Agent toggle ───────────────────────────────────────────────────────────

    private void toggleAgent() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Enable AI Game Agent in Accessibility Settings first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (GameAgentService.instance == null) {
            Toast.makeText(this, "Service not connected — toggle accessibility off then on", Toast.LENGTH_SHORT).show();
            return;
        }
        saveSettings();
        if (GameAgentService.instance.isRunning()) {
            GameAgentService.instance.stopAgent();
        } else {
            GameAgentService.apiKey      = etApiKey.getText().toString().trim();
            GameAgentService.gameContext = etGameContext.getText().toString().trim();
            try { GameAgentService.loopDelayMs = Integer.parseInt(etDelay.getText().toString().trim()); }
            catch (NumberFormatException e) { GameAgentService.loopDelayMs = 2500; }
            GameAgentService.instance.startAgent();
        }
    }

    // ── Generate context from game name ────────────────────────────────────────

    private void generateContext() {
        String gameName = etGameName.getText().toString().trim();
        String key      = etApiKey.getText().toString().trim();
        if (gameName.isEmpty()) { Toast.makeText(this, "Enter a game name first", Toast.LENGTH_SHORT).show(); return; }
        if (key.isEmpty())      { Toast.makeText(this, "Enter your API key first", Toast.LENGTH_SHORT).show(); return; }

        btnGenerate.setText("...");
        btnGenerate.setEnabled(false);
        etGameContext.setText("Asking Claude how to play " + gameName + "...");

        String prompt = "Generate a complete system prompt for an AI agent that automatically plays "
            + "the Android game \"" + gameName + "\" by analyzing screenshots and executing touch gestures.\n\n"
            + "Include:\n1. Game objective\n2. Key visual elements to identify on screen\n"
            + "3. Optimal strategy with numbered move priorities\n"
            + "4. Whether to use tap or swipe, and when\n"
            + "5. Common mistakes to avoid\n6. How to detect game over/win screens\n\n"
            + "Available actions:\n"
            + "{\"action\":\"tap\",\"x\":INT,\"y\":INT}\n"
            + "{\"action\":\"swipe\",\"x1\":INT,\"y1\":INT,\"x2\":INT,\"y2\":INT,\"duration\":INT}\n"
            + "{\"action\":\"sequence\",\"steps\":[ACTION,...]} for multi-tap moves\n"
            + "{\"action\":\"restart\",\"x\":INT,\"y\":INT} when game ends\n\n"
            + "End with: RESPOND WITH ONLY JSON. No text before or after the JSON object.";

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
        try {
            Request req = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(RequestBody.create(new JSONObject()
                    .put("model","claude-sonnet-4-6").put("max_tokens",700)
                    .put("messages", new JSONArray().put(new JSONObject()
                        .put("role","user").put("content", prompt)))
                    .toString(), MediaType.get("application/json"))).build();

            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> { etGameContext.setText("Error: "+e.getMessage()); resetGenBtn(); });
                }
                @Override public void onResponse(Call call, Response r) throws IOException {
                    ResponseBody body = r.body();
                    if (body == null) { runOnUiThread(MainActivity.this::resetGenBtn); return; }
                    try {
                        String ctx = new JSONObject(body.string()).getJSONArray("content")
                            .getJSONObject(0).getString("text").trim();
                        runOnUiThread(() -> {
                            etGameContext.setText(ctx);
                            prefs.edit().putString("game_name",gameName).putString("game_context",ctx).apply();
                            resetGenBtn();
                            Toast.makeText(MainActivity.this,"✓ Context ready for "+gameName,Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> { etGameContext.setText("Error: "+e.getMessage()); resetGenBtn(); });
                    }
                }
            });
        } catch (Exception e) { etGameContext.setText("Error: "+e.getMessage()); resetGenBtn(); }
    }

    private void resetGenBtn() { btnGenerate.setText("Generate"); btnGenerate.setEnabled(true); }

    // ── Feature 7: Game profiles ───────────────────────────────────────────────

    private void saveProfile() {
        EditText input = new EditText(this);
        input.setHint("Profile name");
        input.setText(etGameName.getText().toString().trim());
        new AlertDialog.Builder(this).setTitle("Save Profile").setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                try {
                    JSONArray profiles = new JSONArray(prefs.getString("profiles", "[]"));
                    for (int i = 0; i < profiles.length(); i++) {
                        if (name.equals(profiles.getJSONObject(i).optString("name"))) { profiles.remove(i); break; }
                    }
                    profiles.put(new JSONObject()
                        .put("name",      name)
                        .put("game_name", etGameName.getText().toString())
                        .put("context",   etGameContext.getText().toString())
                        .put("delay",     etDelay.getText().toString()));
                    prefs.edit().putString("profiles", profiles.toString()).apply();
                    Toast.makeText(this, "Saved: " + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Toast.makeText(this, "Error: "+e.getMessage(), Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("Cancel", null).show();
    }

    private void loadProfile() {
        try {
            JSONArray profiles = new JSONArray(prefs.getString("profiles", "[]"));
            if (profiles.length() == 0) { Toast.makeText(this, "No saved profiles yet", Toast.LENGTH_SHORT).show(); return; }
            String[] names = new String[profiles.length()];
            for (int i = 0; i < profiles.length(); i++) names[i] = profiles.getJSONObject(i).optString("name");
            new AlertDialog.Builder(this).setTitle("Load Profile")
                .setItems(names, (d, which) -> {
                    try {
                        JSONObject p = profiles.getJSONObject(which);
                        etGameName.setText(p.optString("game_name"));
                        etGameContext.setText(p.optString("context"));
                        etDelay.setText(p.optString("delay","2500"));
                        Toast.makeText(this, "Loaded: "+names[which], Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show(); }
                }).setNegativeButton("Cancel", null).show();
        } catch (Exception e) { Toast.makeText(this, "Error loading profiles", Toast.LENGTH_SHORT).show(); }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private void saveSettings() {
        int delay = 2500;
        try { delay = Integer.parseInt(etDelay.getText().toString().trim()); } catch (Exception ignored) {}
        prefs.edit().putString("api_key", etApiKey.getText().toString().trim())
            .putString("game_name",    etGameName.getText().toString().trim())
            .putString("game_context", etGameContext.getText().toString().trim())
            .putInt("delay", delay).apply();
    }

    private void applyStatus(String s) {
        switch (s) {
            case "RUNNING": tvStatus.setText("● RUNNING"); tvStatus.setTextColor(0xFF4CAF50); btnStartStop.setText("STOP AGENT");  break;
            case "STOPPED": tvStatus.setText("● STOPPED"); tvStatus.setTextColor(0xFFE53935); btnStartStop.setText("START AGENT"); break;
            case "ERROR":   tvStatus.setText("● ERROR");   tvStatus.setTextColor(0xFFF44336); btnStartStop.setText("START AGENT"); break;
            default:        tvStatus.setText("● READY");   tvStatus.setTextColor(0xFF2196F3); btnStartStop.setText("START AGENT"); break;
        }
    }

    private void refreshAccessibilityBtn() {
        if (isAccessibilityEnabled()) { btnAccessibility.setText("Accessibility: Enabled ✓"); btnAccessibility.setEnabled(false); }
        else { btnAccessibility.setText("Enable Accessibility Service →"); btnAccessibility.setEnabled(true); }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (info.getId().contains(getPackageName())) return true;
        return false;
    }
}
