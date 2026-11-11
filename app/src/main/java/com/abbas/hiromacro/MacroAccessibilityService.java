package com.abbas.hiromacro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class MacroAccessibilityService extends AccessibilityService {
    private static volatile MacroAccessibilityService instance;
    private static volatile Consumer<String> logListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private volatile boolean running;
    private Future<?> task;

    public static MacroAccessibilityService getInstance() { return instance; }
    public static void setLogListener(Consumer<String> listener) { logListener = listener; }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        log("Accessibility service connected. Volume + = START, Volume - = STOP.");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (key == KeyEvent.KEYCODE_VOLUME_UP) {
                log("Volume + pressed: starting selected script.");
                startSelectedScript();
            } else {
                log("Volume - pressed: stopping script.");
                stopScript();
            }
        }
        // Consume both key-down and key-up so the game does not also change volume.
        return true;
    }

    private void startSelectedScript() {
        String savedUri = getSharedPreferences("hiromacro", MODE_PRIVATE)
                .getString("selected_script_uri", null);
        if (savedUri == null || savedUri.isEmpty()) {
            log("No saved script. Open HiroMacro and choose a script first.");
            return;
        }
        new Thread(() -> {
            try {
                StringBuilder source = new StringBuilder();
                Uri uri = Uri.parse(savedUri);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) source.append(line).append('\n');
                }
                if (source.length() == 0) {
                    log("Selected script is empty.");
                    return;
                }
                startScript(source.toString());
            } catch (Exception e) {
                log("Could not load saved script: " + e.getMessage());
            }
        }, "HiroMacro-VolumeStart").start();
    }

    @Override public void onInterrupt() { stopScript(); }

    public synchronized void startScript(String source) {
        stopScript();
        if (executor == null || executor.isShutdown()) executor = Executors.newSingleThreadExecutor();
        running = true;
        task = executor.submit(() -> runLines(source));
        log("Script started.");
    }

    public synchronized void stopScript() {
        running = false;
        if (task != null) task.cancel(true);
        releaseExecutor();
        log("Stop requested.");
    }

    private synchronized void releaseExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        task = null;
    }

    private void runLines(String source) {
        String[] lines = source.split("\\r?\\n");
        for (int index = 0; index < lines.length && running && !Thread.currentThread().isInterrupted(); index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("//")
                    || line.startsWith(":") || line.startsWith("DEVICE:")
                    || line.startsWith("SCREEN_SIZE:") || line.equalsIgnoreCase(":end")) continue;
            try {
                String[] parts = line.split("\\s+");
                String command = parts[0].toLowerCase();
                if ("sleep".equals(command) && parts.length >= 2) {
                    Thread.sleep(Math.max(0L, Long.parseLong(parts[1])));
                } else if ("touchpress".equals(command) && parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    tap(x, y);
                    Thread.sleep(140L);
                } else if ("log".equals(command)) {
                    log(line.length() > 4 ? line.substring(4) : "");
                } else if ("toast".equals(command)) {
                    log("TOAST: " + (line.length() > 5 ? line.substring(5) : ""));
                } else if (command.startsWith("#") || command.equals("var") || command.equals("set")) {
                    log("Line " + (index + 1) + ": variables are not implemented yet.");
                } else {
                    log("Line " + (index + 1) + ": unsupported command: " + parts[0]);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("Line " + (index + 1) + " error: " + e.getMessage());
            }
        }
        if (running) log("Script finished.");
        running = false;
    }

    private void tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 90);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                log("Tap completed at " + x + ", " + y);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                log("Tap cancelled at " + x + ", " + y);
            }
        }, main);
        if (!accepted) log("Tap rejected at " + x + ", " + y);
    }

    private void log(String message) {
        Consumer<String> listener = logListener;
        if (listener != null) main.post(() -> {
            Consumer<String> current = logListener;
            if (current != null) current.accept(message);
        });
        android.util.Log.i("HiroMacro", message);
    }

    @Override public void onDestroy() {
        stopScript();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
