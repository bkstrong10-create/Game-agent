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

public class MainActivity extends AppCompatActivity {

    private static final String CONTEXT_MODEL = "gpt-5.6-luna";

    private EditText etApiKey, etGameName, etGameContext, etDelay, etBudget;
    private Button btnStartStop, btnAccessibility, btnGenerate;
    private Button btnSaveProfile, btnLoadProfile;
    private Button btnFast, btnNormal, btnSlow;
    private TextView tvStatus, tvCost, tvLog, tvGames;
    private SharedPreferences prefs;
    private final ArrayDeque<String> logBuf = new ArrayDeque<>();
    private static final int MAX_LINES = 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("AI Game Agent — OpenAI");

        etApiKey = findViewById(R.id.etApiKey);
        etGameName = findViewById(R.id.etGameName);
        etGameContext = findViewById(R.id.etGameContext);
        etDelay = findViewById(R.id.etDelay);
        etBudget = findViewById(R.id.etBudget);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnGenerate = findViewById(R.id.btnGenerate);
        btnSaveProfile = findViewById(R.id.btnSaveProfile);
        btnLoadProfile = findViewById(R.id.btnLoadProfile);
        btnFast = findViewById(R.id.btnFast);
        btnNormal = findViewById(R.id.btnNormal);
        btnSlow = findViewById(R.id.btnSlow);
        tvStatus = findViewById(R.id.tvStatus);
        tvCost = findViewById(R.id.tvCost);
        tvLog = findViewById(R.id.tvLog);
        tvGames = findViewById(R.id.tvGames);

        prefs = getSharedPreferences("GameAgent", MODE_PRIVATE);

        String savedKey = prefs.getString("api_key", "");
        if (savedKey != null && savedKey.startsWith("sk-ant-")) {
            savedKey = "";
            prefs.edit().remove("api_key").apply();
        }
        etApiKey.setHint("OpenAI API key (sk-...)");
        etApiKey.setText(savedKey == null ? "" : savedKey);
        etGameName.setText(prefs.getString("game_name", ""));

        String oldContext = prefs.getString("game_context", "");
        String cleanContext = sanitizeLegacyContext(oldContext);
        etGameContext.setText(cleanContext);
        if (!cleanContext.equals(oldContext)) {
            prefs.edit().putString("game_context", cleanContext).apply();
        }

        etDelay.setText(String.valueOf(prefs.getInt("delay", 2000)));
        etBudget.setText(prefs.getString("budget", "1.00"));

        btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btnStartStop.setOnClickListener(v -> toggleAgent());
        btnGenerate.setOnClickListener(v -> generateContext());
        btnSaveProfile.setOnClickListener(v -> saveProfile());
        btnLoadProfile.setOnClickListener(v -> loadProfile());
        btnFast.setOnClickListener(v -> etDelay.setText("700"));
        btnNormal.setOnClickListener(v -> etDelay.setText("2000"));
        btnSlow.setOnClickListener(v -> etDelay.setText("4000"));

        GameAgentService.logListener = (status, line, cost, steps, games) ->
            runOnUiThread(() -> {
                logBuf.addLast(line);
                if (logBuf.size() > MAX_LINES) logBuf.removeFirst();
                tvLog.setText(String.join("\n", logBuf));
                tvCost.setText(String.format(Locale.US, "$%.4f est.", cost));
                tvGames.setText(games + " games");
                applyStatus(status);
            });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
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

    private void handleStopIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("stop_agent", false)
                && GameAgentService.instance != null) {
            GameAgentService.instance.stopAgent();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessBtn();
        if (GameAgentService.instance != null) {
            applyStatus(GameAgentService.instance.isRunning() ? "RUNNING" : "READY");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GameAgentService.logListener = null;
    }

    private void toggleAgent() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this,
                "Enable AI Game Agent in Accessibility Settings first",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (GameAgentService.instance == null) {
            Toast.makeText(this,
                "Service not connected — toggle accessibility off, then on",
                Toast.LENGTH_SHORT).show();
            return;
        }

        saveSettings();
        if (GameAgentService.instance.isRunning()) {
            GameAgentService.instance.stopAgent();
            return;
        }

        String key = etApiKey.getText().toString().trim();
        if (key.startsWith("sk-ant-")) {
            Toast.makeText(this,
                "That is an Anthropic key. Enter an OpenAI API key.",
                Toast.LENGTH_LONG).show();
            return;
        }

        GameAgentService.apiKey = key;
        GameAgentService.gameContext =
            sanitizeLegacyContext(etGameContext.getText().toString()).trim();
        try {
            GameAgentService.loopDelayMs =
                Integer.parseInt(etDelay.getText().toString().trim());
        } catch (Exception ignored) {
            GameAgentService.loopDelayMs = 2000;
        }
        try {
            GameAgentService.budgetLimitUsd =
                Double.parseDouble(etBudget.getText().toString().trim());
        } catch (Exception ignored) {
            GameAgentService.budgetLimitUsd = 1.0;
        }
        GameAgentService.instance.startAgent();
    }

    private void generateContext() {
        String name = etGameName.getText().toString().trim();
        String key = etApiKey.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a game name first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.isEmpty()) {
            Toast.makeText(this, "Enter your OpenAI API key first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.startsWith("sk-ant-")) {
            Toast.makeText(this, "Enter an OpenAI key, not an Anthropic key",
                Toast.LENGTH_LONG).show();
            return;
        }

        btnGenerate.setText("...");
        btnGenerate.setEnabled(false);
        etGameContext.setText("Generating OpenAI instructions for \"" + name + "\"...");

        String prompt = "Write concise gameplay instructions for an AI computer-use agent "
            + "playing the Android game \"" + name + "\". Include: the objective, "
            + "important visual elements, priority move order, whether moves are clicks "
            + "or drags, how to recognize win/loss, and mistakes to avoid. Keep it under "
            + "220 words. Return plain text instructions only. Do not request JSON and do "
            + "not invent exact screen coordinates.";

        final String requestBody;
        try {
            requestBody = new JSONObject()
                .put("model", CONTEXT_MODEL)
                .put("input", prompt)
                .put("max_output_tokens", 450)
                .toString();
        } catch (Exception e) {
            etGameContext.setText("Error building request: " + e.getMessage());
            resetGenerateButton();
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(requestBody, MediaType.get("application/json")))
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    etGameContext.setText("Network error: " + e.getMessage());
                    resetGenerateButton();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                ResponseBody body = response.body();
                if (body == null) {
                    runOnUiThread(() -> {
                        etGameContext.setText("OpenAI returned an empty response.");
                        resetGenerateButton();
                    });
                    return;
                }

                try {
                    JSONObject json = new JSONObject(body.string());
                    if (json.has("error")) {
                        String message = json.getJSONObject("error")
                            .optString("message", "Unknown OpenAI error");
                        runOnUiThread(() -> {
                            etGameContext.setText("OpenAI error: " + message);
                            resetGenerateButton();
                        });
                        return;
                    }

                    String context = extractOutputText(json).trim();
                    if (context.isEmpty()) {
                        throw new IllegalStateException("No instruction text returned");
                    }
                    String clean = sanitizeLegacyContext(context);
                    runOnUiThread(() -> {
                        etGameContext.setText(clean);
                        prefs.edit()
                            .putString("game_name", name)
                            .putString("game_context", clean)
                            .apply();
                        resetGenerateButton();
                        Toast.makeText(MainActivity.this,
                            "✓ OpenAI instructions ready for " + name,
                            Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        etGameContext.setText("OpenAI response error: " + e.getMessage());
                        resetGenerateButton();
                    });
                }
            }
        });
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
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) {
                    if (result.length() > 0) result.append('\n');
                    result.append(part.optString("text"));
                }
            }
        }
        return result.toString();
    }

    private void resetGenerateButton() {
        btnGenerate.setText("Generate");
        btnGenerate.setEnabled(true);
    }

    private String sanitizeLegacyContext(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        StringBuilder clean = new StringBuilder();
        String[] lines = input.replace("Claude", "the model").split("\\r?\\n");
        for (String line : lines) {
            String lower = line.trim().toLowerCase(Locale.US);
            if (lower.contains("output only json")
                    || lower.contains("return only json")
                    || lower.contains("return only one json")
                    || lower.contains("normalized 0-1000")
                    || lower.startsWith("coordinate rule:")) {
                continue;
            }
            if (clean.length() > 0) clean.append('\n');
            clean.append(line);
        }
        return clean.toString().trim();
    }

    private void saveProfile() {
        EditText input = new EditText(this);
        input.setHint("Profile name");
        input.setText(etGameName.getText());
        new AlertDialog.Builder(this)
            .setTitle("Save Profile")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                try {
                    JSONArray profiles = new JSONArray(prefs.getString("profiles", "[]"));
                    for (int i = 0; i < profiles.length(); i++) {
                        if (name.equals(profiles.getJSONObject(i).optString("name"))) {
                            profiles.remove(i);
                            break;
                        }
                    }
                    profiles.put(new JSONObject()
                        .put("name", name)
                        .put("game_name", etGameName.getText().toString())
                        .put("context", sanitizeLegacyContext(
                            etGameContext.getText().toString()))
                        .put("delay", etDelay.getText().toString())
                        .put("budget", etBudget.getText().toString()));
                    prefs.edit().putString("profiles", profiles.toString()).apply();
                    Toast.makeText(this, "Saved: " + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadProfile() {
        try {
            JSONArray profiles = new JSONArray(prefs.getString("profiles", "[]"));
            if (profiles.length() == 0) {
                Toast.makeText(this, "No saved profiles", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[profiles.length()];
            for (int i = 0; i < profiles.length(); i++) {
                names[i] = profiles.getJSONObject(i).optString("name");
            }
            new AlertDialog.Builder(this)
                .setTitle("Load Profile")
                .setItems(names, (dialog, which) -> {
                    try {
                        JSONObject profile = profiles.getJSONObject(which);
                        etGameName.setText(profile.optString("game_name"));
                        etGameContext.setText(sanitizeLegacyContext(
                            profile.optString("context")));
                        etDelay.setText(profile.optString("delay", "2000"));
                        etBudget.setText(profile.optString("budget", "1.00"));
                        Toast.makeText(this, "Loaded: " + names[which],
                            Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Error loading profile",
                            Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(this, "Error loading profiles", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveSettings() {
        int delay = 2000;
        try {
            delay = Integer.parseInt(etDelay.getText().toString().trim());
        } catch (Exception ignored) {
        }
        String cleanContext = sanitizeLegacyContext(etGameContext.getText().toString());
        etGameContext.setText(cleanContext);
        prefs.edit()
            .putString("api_key", etApiKey.getText().toString().trim())
            .putString("game_name", etGameName.getText().toString().trim())
            .putString("game_context", cleanContext)
            .putString("budget", etBudget.getText().toString().trim())
            .putInt("delay", delay)
            .apply();
    }

    private void applyStatus(String status) {
        switch (status) {
            case "RUNNING":
                tvStatus.setText("● OPENAI RUNNING");
                tvStatus.setTextColor(0xFF4CAF50);
                btnStartStop.setText("STOP");
                break;
            case "STOPPED":
                tvStatus.setText("● STOPPED");
                tvStatus.setTextColor(0xFFE53935);
                btnStartStop.setText("START");
                break;
            case "ERROR":
                tvStatus.setText("● ERROR");
                tvStatus.setTextColor(0xFFF44336);
                btnStartStop.setText("START");
                break;
            default:
                tvStatus.setText("● OPENAI READY");
                tvStatus.setTextColor(0xFF2196F3);
                btnStartStop.setText("START");
                break;
        }
    }

    private void refreshAccessBtn() {
        if (isAccessibilityEnabled()) {
            btnAccessibility.setText("Accessibility: ON ✓");
            btnAccessibility.setEnabled(false);
        } else {
            btnAccessibility.setText("Enable Accessibility Service →");
            btnAccessibility.setEnabled(true);
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager =
            (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                manager.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId().contains(getPackageName())) return true;
        }
        return false;
    }
}
