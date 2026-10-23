# Pulse Camera (Android)

A minimal Kotlin Android app that estimates pulse rate from camera brightness changes while the fingertip covers the rear camera and flash.

## Build the APK (no coding experience needed)

1. On a computer, install **Android Studio** from https://developer.android.com/studio
2. Extract this ZIP.
3. In Android Studio choose **Open** and select the extracted `PulseCamera` folder.
4. Allow Gradle to sync and download dependencies. Internet is required the first time.
5. Connect your Android phone with a USB cable and enable Developer options → USB debugging, then press Run; or choose **Build → Build APK(s)**.
6. For the APK, Android Studio usually shows a notification with a link to `app-debug.apk`. You can find it at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- The first version is intentionally simple and is a prototype, not a validated medical device.
- Camera sensor output differs across phone models. The basic pulse detector may need tuning or testing on the target phone.
- If the camera cannot start, check that no other app is using it and that camera permission is allowed.
- Keep the finger still and use gentle pressure. Do not press hard enough to impair circulation.
- The app does not store or upload measurements.
