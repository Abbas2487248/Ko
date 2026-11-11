package com.abbas.hiromacro;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
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
        selected.setText("No script selected");
        selected.setPadding(0, 18, 0, 18);
        root.addView(selected);

        Button choose = new Button(this);
        choose.setText("Choose script");
        choose.setOnClickListener(v -> chooseScript());
        root.addView(choose);

        Button accessibility = new Button(this);
        accessibility.setText("Enable Accessibility service");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

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
        note("Ready. Accessibility must be enabled before running scripts.");
    }

    private void chooseScript() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_SCRIPT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SCRIPT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            scriptUri = uri;
            scriptText = out.toString();
            selected.setText("Selected: " + uri.getLastPathSegment());
            note("Script loaded (" + scriptText.split("\\n").length + " lines).");
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
