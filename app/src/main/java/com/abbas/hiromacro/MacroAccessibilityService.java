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
        try {
            MacroInterpreter interpreter = new MacroInterpreter(new MacroInterpreter.Host() {
                @Override public boolean isRunning() { return running && !Thread.currentThread().isInterrupted(); }
                @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                @Override public void log(String message) { MacroAccessibilityService.this.log(message); }
                @Override public void tap(int x, int y) { MacroAccessibilityService.this.tap(x, y); }
                @Override public void touchPath(java.util.List<int[]> points, long durationMs) throws Exception {
                    if (points == null || points.isEmpty()) return;
                    Path path = new Path();
                    path.moveTo(points.get(0)[0], points.get(0)[1]);
                    for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i)[0], points.get(i)[1]);
                    long duration = Math.max(1L, Math.min(60000L, durationMs));
                    GestureDescription.StrokeDescription stroke =
                            new GestureDescription.StrokeDescription(path, 0, duration);
                    GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription description) {
                            completed.set(true);
                            done.countDown();
                        }
                        @Override public void onCancelled(GestureDescription description) {
                            done.countDown();
                        }
                    }, main);
                    if (!accepted) throw new IllegalStateException("Swipe gesture rejected by Android.");
                    done.await();
                    if (!completed.get()) throw new IllegalStateException("Swipe gesture was cancelled.");
                }
            });
            interpreter.run(source);
            if (running) log("Script finished.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log("Script stopped safely: " + e.getMessage());
        } finally {
            running = false;
        }
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
