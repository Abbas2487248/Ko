package com.abbas.hiromacro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class MacroAccessibilityService extends AccessibilityService {
    private static volatile MacroAccessibilityService instance;
    private static volatile Consumer<String> logListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private volatile boolean running;
    private volatile long runGeneration;
    private volatile long loadGeneration;
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
        final long requestGeneration;
        synchronized (this) { requestGeneration = ++loadGeneration; }
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
                synchronized (MacroAccessibilityService.this) {
                    if (loadGeneration != requestGeneration) {
                        log("Script start cancelled before loading completed.");
                        return;
                    }
                    startScript(source.toString());
                }
            } catch (Exception e) {
                log("Could not load saved script: " + e.getMessage());
            }
        }, "HiroMacro-VolumeStart").start();
    }

    @Override public void onInterrupt() { stopScript(); }

    public synchronized void startScript(String source) {
        stopScript();
        final long generation = ++runGeneration;
        if (executor == null || executor.isShutdown()) executor = Executors.newSingleThreadExecutor();
        running = true;
        task = executor.submit(() -> runLines(source, generation));
        log("Script started.");
    }

    public synchronized void stopScript() {
        running = false;
        ++runGeneration;
        ++loadGeneration;
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

    private void runLines(String source, long generation) {
        try {
            MacroInterpreter interpreter = new MacroInterpreter(new MacroInterpreter.Host() {
                @Override public boolean isRunning() { return running && runGeneration == generation && !Thread.currentThread().isInterrupted(); }
                @Override public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                @Override public void log(String message) { MacroAccessibilityService.this.log(message); }
                @Override public void tap(int x, int y) throws Exception { MacroAccessibilityService.this.tap(x, y); }
                @Override public int getColor(int x, int y) throws Exception { return MacroAccessibilityService.this.readPixelColor(x, y); }
                @Override public void touchPath(java.util.List<int[]> points, long durationMs) throws Exception {
                    if (points == null || points.isEmpty()) return;
                    Path path = new Path();
                    path.moveTo(points.get(0)[0], points.get(0)[1]);
                    for (int i = 0; i < points.size(); i++) {
                        int[] point = points.get(i);
                        MacroAccessibilityService.this.validateTouchPoint(point[0], point[1]);
                        if (i > 0) path.lineTo(point[0], point[1]);
                    }
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
                    if (!done.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for swipe gesture completion.");
                    }
                    if (!completed.get()) throw new IllegalStateException("Swipe gesture was cancelled.");
                }
            });
            interpreter.run(source);
            if (running && runGeneration == generation) log("Script finished.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log("Script stopped safely: " + e.getMessage());
        } finally {
            if (runGeneration == generation) running = false;
        }
    }


    /** Reads one pixel from the active display using the Accessibility screenshot API (Android 11+). */
    private int readPixelColor(int x, int y) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new UnsupportedOperationException("Screen color reading requires Android 11 or newer.");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> captured = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        main.post(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, command -> main.post(command), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                            if (hardware == null) {
                                failure.set("Android returned an empty screenshot.");
                            } else {
                                captured.set(hardware.copy(Bitmap.Config.ARGB_8888, false));
                            }
                        } catch (Exception e) {
                            failure.set("Could not decode screenshot: " + e.getMessage());
                        } finally {
                            if (buffer != null) buffer.close();
                            done.countDown();
                        }
                    }
                    @Override public void onFailure(int errorCode) {
                        failure.set("Screenshot failed (Android error " + errorCode + ").");
                        done.countDown();
                    }
                });
            } catch (Exception e) {
                failure.set("Screenshot request failed: " + e.getMessage());
                done.countDown();
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for screen screenshot.");
        Bitmap bitmap = captured.get();
        if (bitmap == null) throw new IllegalStateException(failure.get() == null ? "Screen screenshot unavailable." : failure.get());
        try {
            if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
                throw new IllegalArgumentException("Color coordinate outside screenshot: " + x + "," + y
                        + " (screenshot " + bitmap.getWidth() + "x" + bitmap.getHeight() + ").");
            }
            return bitmap.getPixel(x, y);
        } finally {
            bitmap.recycle();
        }
    }

    /** Reject coordinates outside the active display instead of allowing an accidental edge tap. */
    private void validateTouchPoint(int x, int y) {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IllegalArgumentException("Touch coordinate " + x + ", " + y
                    + " is outside active display " + width + "x" + height + ". No touch sent.");
        }
    }

    private void tap(int x, int y) throws InterruptedException {
        validateTouchPoint(x, y);
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 90);
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
        if (!accepted) throw new IllegalStateException("Tap rejected at " + x + ", " + y);
        if (!done.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for tap completion at " + x + ", " + y);
        }
        if (!completed.get()) throw new IllegalStateException("Tap cancelled at " + x + ", " + y);
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
