# Carrom Aim Bot — Build Ready

Android/Kotlin visual aiming overlay for a Carrom board. It captures the screen with Android MediaProjection, detects the board/discs, and draws an approximate direct or one-cushion trajectory. **It does not auto-click or inject touch input.**

## Requirements
- Android Studio with JDK 17, or GitHub Actions
- Android SDK Platform 35
- Android 8.0 (API 26) or newer

## Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync and install any requested SDK components.
3. Select `app` and build **Build > Build APK(s)**.
4. The debug APK is generated at `app/build/outputs/apk/debug/`.

## GitHub — easiest build
1. Create a GitHub repository and upload this project.
2. Push the files to `main` (or `master`).
3. Open **Actions → Build Android APK**.
4. Run the workflow if it did not start automatically.
5. Download the **CarromAimBot-debug-apk** artifact.

The workflow installs JDK 17 and Gradle 8.7 automatically, so a Gradle wrapper is not required.

## On the phone
1. Install the debug APK.
2. Allow **Display over other apps**.
3. Tap **Start live aim** and approve Android's screen-capture dialog.
4. Open the Carrom game. The overlay draws the detected board and recommended trajectory.
5. Tap **Stop aim overlay** when finished.

## Important limitations
The computer vision and physics model are approximate. Lighting, board theme, resolution, occlusion, game animations, collision physics, and shot power can affect accuracy. This version intentionally remains a visual guide and does not automate gameplay input.
