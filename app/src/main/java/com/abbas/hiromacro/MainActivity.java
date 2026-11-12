package com.abbas.hiromacro;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int PICK_SCRIPT = 41;
    private Uri scriptUri;
    private String scriptText = "";
    private TextView selected, logView;
    private final StringBuilder logs = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 24, 20, 20);
        root.setGravity(Gravity.TOP);

        TextView title = new TextView(this);
        title.setText("HiroMacro");
        title.setTextSize(26);
        root.addView(title);

        selected = new TextView(this);
        String savedUri = getSharedPreferences("hiromacro", MODE_PRIVATE)
                .getString("selected_script_uri", null);
        selected.setText(savedUri == null ? "No script selected" : "A saved script is ready for volume-button start");
        selected.setPadding(0, 18, 0, 18);
        root.addView(selected);

        Button choose = new Button(this);
        choose.setText("Choose script");
        choose.setOnClickListener(v -> chooseScript());
        root.addView(choose);

        Button check = new Button(this);
        check.setText("CHECK SCRIPT (structure + commands)");
        check.setOnClickListener(v -> {
            if (scriptText.isEmpty()) {
                note("Choose a script first.");
                return;
            }
            note(MacroInterpreter.validateCommands(scriptText));
        });
        root.addView(check);

        Button dimensions = new Button(this);
        dimensions.setText("CHECK SCREEN DIMENSIONS (SAFE)");
        dimensions.setOnClickListener(v -> {
            MacroAccessibilityService service = MacroAccessibilityService.getInstance();
            if (service == null) {
                note("Enable HiroMacro Accessibility service first, then return.");
                return;
            }
            note("Starting display check. No touch gestures will be sent.");
            service.logDisplayDiagnostics();
        });
        root.addView(dimensions);

        Button accessibility = new Button(this);
        accessibility.setText("Enable Accessibility service");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        TextView keyHelp = new TextView(this);
        keyHelp.setText("Hardware controls (when Accessibility is enabled):\nVolume + : START saved script\nVolume - : STOP script");
        keyHelp.setPadding(0, 10, 0, 10);
        root.addView(keyHelp);

        Button start = new Button(this);
        start.setText("START");
        start.setOnClickListener(v -> {
            if (scriptText.isEmpty()) { note("Choose a script first."); return; }
            MacroAccessibilityService service = MacroAccessibilityService.getInstance();
            if (service == null) { note("Enable HiroMacro in Android Accessibility settings, then return."); return; }
            service.startScript(scriptText);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("STOP");
        stop.setOnClickListener(v -> {
            MacroAccessibilityService service = MacroAccessibilityService.getInstance();
            if (service != null) service.stopScript();
            else note("Service is not connected.");
        });
        root.addView(stop);

        TextView logTitle = new TextView(this);
        logTitle.setText("Event log");
        logTitle.setTextSize(18);
        logTitle.setPadding(0, 18, 0, 8);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        MacroAccessibilityService.setLogListener(this::note);
        setContentView(root);
        note("Ready. Select a script, enable Accessibility, then use Volume + to start and Volume - to stop.");
        loadSavedScript();
    }

    /** Restore the previously selected document after the app process is recreated. */
    private void loadSavedScript() {
        String savedUri = getSharedPreferences("hiromacro", MODE_PRIVATE)
                .getString("selected_script_uri", null);
        if (savedUri == null || savedUri.isEmpty()) return;
        Thread loader = new Thread(() -> {
            try {
                Uri uri = Uri.parse(savedUri);
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) out.append(line).append('\n');
                }
                String loaded = out.toString();
                runOnUiThread(() -> {
                    scriptUri = uri;
                    scriptText = loaded;
                    selected.setText("Restored: " + uri.getLastPathSegment());
                    note("Saved script restored (" + scriptText.split("\\n").length + " lines).");
                    note(MacroInterpreter.validateCommands(scriptText));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    selected.setText("Saved script permission unavailable — choose it again");
                    note("Could not restore saved script: " + e.getMessage());
                });
            }
        }, "HiroMacro-RestoreScript");
        loader.start();
    }

    private void chooseScript() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_SCRIPT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SCRIPT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                note("Provider did not grant a persistent read permission; reselect the script if needed after restart.");
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            scriptUri = uri;
            scriptText = out.toString();
            getSharedPreferences("hiromacro", MODE_PRIVATE).edit()
                    .putString("selected_script_uri", uri.toString()).apply();
            selected.setText("Selected: " + uri.getLastPathSegment() + " (saved)");
            note("Script loaded and saved (" + scriptText.split("\\n").length + " lines).");
        } catch (Exception e) {
            note("Could not read script: " + e.getMessage());
        }
    }

    private void note(String message) {
        runOnUiThread(() -> {
            logs.append(message).append('\n');
            if (logs.length() > 12000) logs.delete(0, logs.length() - 9000);
            logView.setText(logs.toString());
        });
    }

    @Override protected void onDestroy() {
        MacroAccessibilityService.setLogListener(null);
        super.onDestroy();
    }
}
