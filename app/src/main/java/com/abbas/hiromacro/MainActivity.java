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
        title.setText("هيرو ماكرو");
        title.setTextSize(26);
        root.addView(title);

        selected = new TextView(this);
        String savedUri = getSharedPreferences("hiromacro", MODE_PRIVATE)
                .getString("selected_script_uri", null);
        selected.setText(savedUri == null ? "لم يتم اختيار ملف سكربت" : "تم استعادة السكربت المحفوظ وجاهز للتشغيل");
        selected.setPadding(0, 18, 0, 18);
        root.addView(selected);

        Button choose = new Button(this);
        choose.setText("اختيار ملف السكربت");
        choose.setOnClickListener(v -> chooseScript());
        root.addView(choose);

        Button check = new Button(this);
        check.setText("فحص السكربت (البنية والأوامر)");
        check.setOnClickListener(v -> {
            if (scriptText.isEmpty()) {
                note("اختر ملف السكربت أولاً.");
                return;
            }
            note(MacroInterpreter.validateCommands(scriptText));
        });
        root.addView(check);

        Button dimensions = new Button(this);
        dimensions.setText("فحص أبعاد الشاشة (آمن)");
        dimensions.setAllCaps(false);
        dimensions.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        dimensions.setOnClickListener(v -> {
            MacroAccessibilityService service = MacroAccessibilityService.getInstance();
            if (service == null) {
                note("فعّل خدمة إمكانية الوصول لهيرو ماكرو أولاً، ثم ارجع إلى التطبيق.");
                return;
            }
            note("بدأ فحص أبعاد الشاشة. لن يتم إرسال أي لمسات.");
            service.logDisplayDiagnostics();
        });
        root.addView(dimensions);

        Button accessibility = new Button(this);
        accessibility.setText("تفعيل خدمة إمكانية الوصول");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        TextView keyHelp = new TextView(this);
        keyHelp.setText("أزرار التحكم (بعد تفعيل إمكانية الوصول):\nرفع الصوت: تشغيل السكربت المحفوظ\nخفض الصوت: إيقاف السكربت");
        keyHelp.setPadding(0, 10, 0, 10);
        root.addView(keyHelp);

        Button start = new Button(this);
        start.setText("تشغيل");
        start.setOnClickListener(v -> {
            if (scriptText.isEmpty()) { note("Choose a script first."); return; }
            MacroAccessibilityService service = MacroAccessibilityService.getInstance();
            if (service == null) { note("فعّل هيرو ماكرو من إعدادات إمكانية الوصول في أندرويد، ثم ارجع."); return; }
            service.startScript(scriptText);
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("إيقاف");
        stop.setOnClickListener(v -> {
            MacroAccessibilityService service = MacroAccessibilityService.getInstance();
            if (service != null) service.stopScript();
            else note("خدمة إمكانية الوصول غير متصلة.");
        });
        root.addView(stop);

        TextView logTitle = new TextView(this);
        logTitle.setText("سجل الأحداث");
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
        note("جاهز. اختر السكربت وفعّل إمكانية الوصول، ثم استخدم زر رفع الصوت للتشغيل وخفض الصوت للإيقاف.");
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
                    note("تمت استعادة السكربت المحفوظ (" + scriptText.split("\\n").length + " سطر).");
                    note(MacroInterpreter.validateCommands(scriptText));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    selected.setText("صلاحية ملف السكربت غير متاحة — اختر الملف من جديد");
                    note("تعذرت استعادة السكربت المحفوظ: " + e.getMessage());
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
                note("لم يمنح مزود الملفات صلاحية قراءة دائمة؛ قد تحتاج إلى اختيار السكربت مجدداً بعد إعادة تشغيل التطبيق.");
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
            note("تم تحميل السكربت وحفظه (" + scriptText.split("\\n").length + " سطر).");
        } catch (Exception e) {
            note("تعذرت قراءة السكربت: " + e.getMessage());
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
