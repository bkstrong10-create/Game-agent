package com.bradlee.gameagent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayDeque;

public class MainActivity extends AppCompatActivity {

    private EditText etApiKey, etGameContext, etDelay;
    private Button   btnStartStop, btnAccessibility;
    private TextView tvStatus, tvLog;
    private SharedPreferences prefs;
    private final ArrayDeque<String> logBuf = new ArrayDeque<>();
    private static final int MAX_LINES = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etApiKey        = findViewById(R.id.etApiKey);
        etGameContext   = findViewById(R.id.etGameContext);
        etDelay         = findViewById(R.id.etDelay);
        btnStartStop    = findViewById(R.id.btnStartStop);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        tvStatus        = findViewById(R.id.tvStatus);
        tvLog           = findViewById(R.id.tvLog);

        prefs = getSharedPreferences("GameAgent", MODE_PRIVATE);
        etApiKey.setText(prefs.getString("api_key", ""));
        etGameContext.setText(prefs.getString("game_context", "Play this game as best you can."));
        etDelay.setText(String.valueOf(prefs.getInt("delay", 2000)));

        btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnStartStop.setOnClickListener(v -> toggleAgent());

        // Receive updates from service
        GameAgentService.logListener = (status, line) -> runOnUiThread(() -> {
            logBuf.addLast(line);
            if (logBuf.size() > MAX_LINES) logBuf.removeFirst();
            tvLog.setText(String.join("\n", logBuf));
            applyStatus(status);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibilityBtn();
        if (GameAgentService.instance != null)
            applyStatus(GameAgentService.instance.isRunning() ? "RUNNING" : "READY");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GameAgentService.logListener = null;
    }

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
            catch (NumberFormatException e) { GameAgentService.loopDelayMs = 2000; }
            GameAgentService.instance.startAgent();
        }
    }

    private void saveSettings() {
        int delay = 2000;
        try { delay = Integer.parseInt(etDelay.getText().toString().trim()); } catch (Exception ignored) {}
        prefs.edit()
            .putString("api_key",      etApiKey.getText().toString().trim())
            .putString("game_context", etGameContext.getText().toString().trim())
            .putInt("delay", delay)
            .apply();
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
        if (isAccessibilityEnabled()) {
            btnAccessibility.setText("Accessibility: Enabled ✓");
            btnAccessibility.setEnabled(false);
        } else {
            btnAccessibility.setText("Enable Accessibility Service →");
            btnAccessibility.setEnabled(true);
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId().contains(getPackageName())) return true;
        }
        return false;
    }
}
