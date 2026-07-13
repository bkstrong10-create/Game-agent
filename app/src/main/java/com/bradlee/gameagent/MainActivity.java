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

    private EditText etApiKey, etGameName, etGameContext, etDelay, etBudget;
    private Button   btnStartStop, btnAccessibility, btnGenerate;
    private Button   btnSaveProfile, btnLoadProfile;
    private Button   btnFast, btnNormal, btnSlow;
    private TextView tvStatus, tvCost, tvLog, tvGames;
    private SharedPreferences prefs;
    private final ArrayDeque<String> logBuf = new ArrayDeque<>();
    private static final int MAX_LINES = 40;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etApiKey         = findViewById(R.id.etApiKey);
        etGameName       = findViewById(R.id.etGameName);
        etGameContext    = findViewById(R.id.etGameContext);
        etDelay          = findViewById(R.id.etDelay);
        etBudget         = findViewById(R.id.etBudget);
        btnStartStop     = findViewById(R.id.btnStartStop);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnGenerate      = findViewById(R.id.btnGenerate);
        btnSaveProfile   = findViewById(R.id.btnSaveProfile);
        btnLoadProfile   = findViewById(R.id.btnLoadProfile);
        btnFast          = findViewById(R.id.btnFast);
        btnNormal        = findViewById(R.id.btnNormal);
        btnSlow          = findViewById(R.id.btnSlow);
        tvStatus         = findViewById(R.id.tvStatus);
        tvCost           = findViewById(R.id.tvCost);
        tvLog            = findViewById(R.id.tvLog);
        tvGames          = findViewById(R.id.tvGames);

        prefs = getSharedPreferences("GameAgent", MODE_PRIVATE);
        etApiKey.setText(prefs.getString("api_key", ""));
        etGameName.setText(prefs.getString("game_name", ""));
        etGameContext.setText(prefs.getString("game_context", ""));
        etDelay.setText(String.valueOf(prefs.getInt("delay", 2000)));
        etBudget.setText(prefs.getString("budget", "1.00"));

        btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        btnStartStop.setOnClickListener(v -> toggleAgent());
        btnGenerate.setOnClickListener(v -> generateContext());
        btnSaveProfile.setOnClickListener(v -> saveProfile());
        btnLoadProfile.setOnClickListener(v -> loadProfile());
        btnFast.setOnClickListener(v -> { etDelay.setText("500"); });
        btnNormal.setOnClickListener(v -> { etDelay.setText("2000"); });
        btnSlow.setOnClickListener(v -> { etDelay.setText("4000"); });

        GameAgentService.logListener = (status, line, cost, steps, games) ->
            runOnUiThread(() -> {
                logBuf.addLast(line);
                if (logBuf.size() > MAX_LINES) logBuf.removeFirst();
                tvLog.setText(String.join("\n", logBuf));
                tvCost.setText(String.format("$%.4f", cost));
                tvGames.setText(games + " games");
                applyStatus(status);
            });

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
        handleStopIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handleStopIntent(intent);
    }
    private void handleStopIntent(Intent i) {
        if (i != null && i.getBooleanExtra("stop_agent", false))
            if (GameAgentService.instance != null) GameAgentService.instance.stopAgent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessBtn();
        if (GameAgentService.instance != null)
            applyStatus(GameAgentService.instance.isRunning() ? "RUNNING" : "READY");
    }
    @Override protected void onDestroy() { super.onDestroy(); GameAgentService.logListener = null; }

    // ── Agent control ─────────────────────────────────────────────────────────

    private void toggleAgent() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Enable AI Game Agent in Accessibility Settings first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
        }
        if (GameAgentService.instance == null) {
            Toast.makeText(this, "Service not connected — toggle accessibility off then on", Toast.LENGTH_SHORT).show(); return;
        }
        saveSettings();
        if (GameAgentService.instance.isRunning()) {
            GameAgentService.instance.stopAgent();
        } else {
            GameAgentService.apiKey      = etApiKey.getText().toString().trim();
            GameAgentService.gameContext = etGameContext.getText().toString().trim();
            try { GameAgentService.loopDelayMs = Integer.parseInt(etDelay.getText().toString().trim()); }
            catch (Exception e) { GameAgentService.loopDelayMs = 2000; }
            try { GameAgentService.budgetLimitUsd = Double.parseDouble(etBudget.getText().toString().trim()); }
            catch (Exception e) { GameAgentService.budgetLimitUsd = 1.0; }
            GameAgentService.instance.startAgent();
        }
    }

    // ── Generate context ───────────────────────────────────────────────────────

    private void generateContext() {
        String name = etGameName.getText().toString().trim();
        String key  = etApiKey.getText().toString().trim();
        if (name.isEmpty()) { Toast.makeText(this,"Enter a game name first",Toast.LENGTH_SHORT).show(); return; }
        if (key.isEmpty())  { Toast.makeText(this,"Enter your API key first",Toast.LENGTH_SHORT).show(); return; }

        btnGenerate.setText("..."); btnGenerate.setEnabled(false);
        etGameContext.setText("Generating context for \"" + name + "\"...");

        String prompt = "Write a concise system prompt for an AI agent playing the Android game \""
            + name + "\" via tap/swipe gestures. Include:\n"
            + "1. Game objective (1 sentence)\n"
            + "2. Key visual elements to identify\n"
            + "3. Priority move order (numbered)\n"
            + "4. Whether moves are taps or swipes\n"
            + "5. How to detect game over/win\n"
            + "6. Common mistakes to avoid\n\n"
            + "Keep it under 200 words. Be specific and actionable.\n"
            + "End with: Output ONLY JSON. No text before or after.";

        new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
            .newCall(new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", key).header("anthropic-version","2023-06-01")
                .header("content-type","application/json")
                .post(RequestBody.create(new JSONObject()
                    .put("model","claude-sonnet-4-6").put("max_tokens",400)
                    .put("messages", new JSONArray().put(new JSONObject()
                        .put("role","user").put("content", prompt)))
                    .toString(), MediaType.get("application/json")))
                .build())
            .enqueue(new Callback() {
                @Override public void onFailure(Call c, IOException e) {
                    runOnUiThread(() -> { etGameContext.setText("Error: "+e.getMessage()); resetGen(); });
                }
                @Override public void onResponse(Call c, Response r) throws IOException {
                    ResponseBody b = r.body(); if (b==null){runOnUiThread(MainActivity.this::resetGen);return;}
                    try {
                        String ctx = new JSONObject(b.string()).getJSONArray("content")
                            .getJSONObject(0).getString("text").trim();
                        runOnUiThread(() -> {
                            etGameContext.setText(ctx);
                            prefs.edit().putString("game_name",name).putString("game_context",ctx).apply();
                            resetGen();
                            Toast.makeText(MainActivity.this,"✓ Ready for "+name,Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> { etGameContext.setText("Error: "+e.getMessage()); resetGen(); });
                    }
                }
            });
    }
    private void resetGen() { btnGenerate.setText("Generate"); btnGenerate.setEnabled(true); }

    // ── Profiles ───────────────────────────────────────────────────────────────

    private void saveProfile() {
        EditText in = new EditText(this); in.setHint("Profile name"); in.setText(etGameName.getText());
        new AlertDialog.Builder(this).setTitle("Save Profile").setView(in)
            .setPositiveButton("Save", (d,w)->{
                String n = in.getText().toString().trim(); if (n.isEmpty()) return;
                try {
                    JSONArray arr = new JSONArray(prefs.getString("profiles","[]"));
                    for (int i=0;i<arr.length();i++) if (n.equals(arr.getJSONObject(i).optString("name"))){arr.remove(i);break;}
                    arr.put(new JSONObject().put("name",n).put("game_name",etGameName.getText().toString())
                        .put("context",etGameContext.getText().toString()).put("delay",etDelay.getText().toString())
                        .put("budget",etBudget.getText().toString()));
                    prefs.edit().putString("profiles",arr.toString()).apply();
                    Toast.makeText(this,"Saved: "+n,Toast.LENGTH_SHORT).show();
                } catch (Exception e){ Toast.makeText(this,"Error: "+e.getMessage(),Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("Cancel",null).show();
    }

    private void loadProfile() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("profiles","[]"));
            if (arr.length()==0){Toast.makeText(this,"No saved profiles",Toast.LENGTH_SHORT).show();return;}
            String[] names = new String[arr.length()];
            for (int i=0;i<arr.length();i++) names[i]=arr.getJSONObject(i).optString("name");
            new AlertDialog.Builder(this).setTitle("Load Profile").setItems(names,(d,w)->{
                try {
                    JSONObject p=arr.getJSONObject(w);
                    etGameName.setText(p.optString("game_name"));
                    etGameContext.setText(p.optString("context"));
                    etDelay.setText(p.optString("delay","2000"));
                    etBudget.setText(p.optString("budget","1.00"));
                    Toast.makeText(this,"Loaded: "+names[w],Toast.LENGTH_SHORT).show();
                } catch (Exception e){ Toast.makeText(this,"Error",Toast.LENGTH_SHORT).show(); }
            }).setNegativeButton("Cancel",null).show();
        } catch (Exception e){ Toast.makeText(this,"Error loading",Toast.LENGTH_SHORT).show(); }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private void saveSettings() {
        int delay=2000; try{delay=Integer.parseInt(etDelay.getText().toString().trim());}catch(Exception ignored){}
        prefs.edit().putString("api_key",etApiKey.getText().toString().trim())
            .putString("game_name",etGameName.getText().toString().trim())
            .putString("game_context",etGameContext.getText().toString().trim())
            .putString("budget",etBudget.getText().toString().trim())
            .putInt("delay",delay).apply();
    }

    private void applyStatus(String s) {
        switch(s) {
            case "RUNNING": tvStatus.setText("● RUNNING"); tvStatus.setTextColor(0xFF4CAF50); btnStartStop.setText("STOP");  break;
            case "STOPPED": tvStatus.setText("● STOPPED"); tvStatus.setTextColor(0xFFE53935); btnStartStop.setText("START"); break;
            case "ERROR":   tvStatus.setText("● ERROR");   tvStatus.setTextColor(0xFFF44336); btnStartStop.setText("START"); break;
            default:        tvStatus.setText("● READY");   tvStatus.setTextColor(0xFF2196F3); btnStartStop.setText("START"); break;
        }
    }

    private void refreshAccessBtn() {
        if (isAccessibilityEnabled()) {
            btnAccessibility.setText("Accessibility: ON ✓"); btnAccessibility.setEnabled(false);
        } else {
            btnAccessibility.setText("Enable Accessibility Service →"); btnAccessibility.setEnabled(true);
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        for (android.accessibilityservice.AccessibilityServiceInfo i :
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (i.getId().contains(getPackageName())) return true;
        return false;
    }
}
