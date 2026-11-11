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
- A script text-file picker that remembers the selected document URI when the provider grants persistent access.
- Hardware volume-button controls while the accessibility service is enabled: Volume Up starts the saved script; Volume Down stops it.
- An event log.
- Initial command handling for `sleep`, `touchpress x y`, and `log`.
- Explicit log messages for commands that are not implemented yet.

## Important limitations
This is only a starting prototype, not a complete HiroMacro replacement. The original engine uses native input/uinput functionality. Android Accessibility gestures have different behavior and do not guarantee support for persistent or simultaneous multi-touch contacts. Variables, labels, conditions, loops, pixel/color reads, key events, screen capture, and the remaining HiroMacro commands are not implemented yet. Coordinates are currently treated as absolute screen pixels.

When the accessibility service filters the volume keys, those key presses are consumed by HiroMacro and will not change media/ring volume while the service is active. Android/device behavior can vary, so hardware-key interception must be tested on the target phone.

## Build
Open this repository in Android Studio and build the `app` module with Android Gradle Plugin 8.6.1 or newer compatible tooling. No Gradle wrapper is included yet.

## First run
1. Install the debug build.
2. Open HiroMacro and tap **Enable Accessibility service**.
3. Enable **HiroMacro Touch Service** in Android Accessibility settings.
4. Return to the app and choose the script once.
5. Leave the accessibility service enabled. Volume Up starts the saved script, and Volume Down stops it, including while another app is in the foreground.
6. Use the on-screen START/STOP controls as alternatives.

Use a harmless test script first. Gesture behavior varies by Android version and target app.
