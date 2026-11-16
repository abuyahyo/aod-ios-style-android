# AOD for Android

An Android Always-On Display and landscape charging StandBy screen inspired by iOS. It is
written in Java using the Android framework, without Gradle or third-party libraries.

## Features

- Dim lock screen with the system wallpaper, clock, date, battery, alarm, and notifications.
- Briefly brighten the screen after pickup or touch.
- Landscape StandBy mode while charging, with clock and status cards.
- Proximity and battery-saver handling, plus a low-light red StandBy theme.
- Optional sleep schedule that suppresses AOD during selected hours.
- A system-managed `DreamService` StandBy clock for devices that expose Android's Screensaver settings.
- Notification text is hidden by default. Enable it explicitly in the app if you want full
  notification previews on the screen.

## Permissions and privacy

The overlay needs "Display over other apps" permission and works more reliably when exempted
from battery optimization. Notification cards require Android's Notification access. The app
reads active notification titles and text locally; it does not upload them. With the default
privacy setting, only the app name is shown. You can disable notification cards or opt in to
show their contents in the settings screen.

## Build

Requirements: JDK 17, Android SDK Platform 36, Android Build Tools 36, and Python 3. The
commands `aapt2`, `d8`, `apksigner`, and `keytool` from Build Tools must be available in `PATH`.

```sh
ANDROID_HOME=/path/to/Android/Sdk \
ANDROID_BUILD_TOOLS=/path/to/Android/Sdk/build-tools/36.0.0 \
bash ./build-apk.sh .
```

Set `SDK_JAR` to override the platform jar. The APK is written to `build/app.apk`. On the first
build the script creates a signing key in `~/.android/aod-signing`; keep this key and its
password outside the repository to sign future updates with the same identity.

See [README.ru.md](README.ru.md) for Russian documentation.

## HONOR / MagicOS setup

On HONOR devices, open **Settings → Apps → App launch**, select AOD, switch off automatic
management, and enable auto-launch, secondary launch, and background running. Lock AOD in
Recents and exempt it from battery optimization. If the device has a built-in Always-On Display,
turn that off to avoid two displays competing. Menu labels vary by MagicOS version.

After sideloading on Android 13 or later, notification access may be blocked as a restricted
setting. Open the app's system App info, use the ⋮ menu to allow restricted settings, return to
AOD, and enable Notification access.

## System-managed StandBy

The app's **Set up system StandBy screensaver** button opens Android's Screensaver/Dream settings.
On the tested Honor X9d (MagicOS, Android 16), that screen opens but offers only the photo
screensaver and does not list **AOD StandBy**. The `DreamService` is registered in the app, but this
device does not expose it through its built-in settings. On devices that list third-party
DreamService components, it can be selected there. This system screensaver is for charging and idle
time; it does not replace lock-screen AOD. The regular lock-screen simulation still cannot enter
the system's low-power display doze state.

## Compatibility notes

The project targets Android 36 and supports Android 8.0 (API 26) and newer. Always-On behavior,
background limits, sensor availability, and battery policies vary by device manufacturer; test
on the intended device before relying on it overnight. Devices without a proximity or light
sensor simply skip that sensor-dependent behavior.
