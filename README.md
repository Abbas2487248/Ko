# HiroMacro

A new Android app prototype intended to run a small, safe subset of HiroMacro scripts without root access.

## Project
- Android application module: `app`
- Application ID: `com.abbas.hiromacro`
- Minimum Android version: API 24
- Target SDK: 35
- Touch injection: Android AccessibilityService gestures (no root)

## First prototype
The app currently provides:
- A simple screen with Choose script, Accessibility settings, START and STOP.
- A script text-file picker.
- An event log.
- Initial command handling for `sleep`, `touchpress x y`, and `log`.
- Explicit log messages for commands that are not implemented yet.

## Important limitations
This is only a starting prototype, not a complete HiroMacro replacement. The original engine uses native input/uinput functionality. Android Accessibility gestures have different behavior and do not guarantee support for persistent or simultaneous multi-touch contacts. Variables, labels, conditions, loops, pixel/color reads, key events, screen capture, and the remaining HiroMacro commands are not implemented yet. Coordinates are currently treated as absolute screen pixels.

## Build
Open this repository in Android Studio and build the `app` module with Android Gradle Plugin 8.6.1 or newer compatible tooling. No Gradle wrapper is included yet.

## First run
1. Install the debug build.
2. Open HiroMacro and tap **Enable Accessibility service**.
3. Enable **HiroMacro Touch Service** in Android Accessibility settings.
4. Return to the app, choose a compatible text script, then tap START.
5. Tap STOP to cancel execution.

Use a harmless test script first. Gesture behavior varies by Android version and target app.
