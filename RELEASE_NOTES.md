# AOD for Android 3.1

## Changes

- Hide notification titles and message text on the AOD by default; users can opt in to full previews.
- Fix ZIP alignment metadata in the standalone APK packaging script.
- Fix signing when the same keystore password is used for the keystore and key.
- Show foreground service startup errors in the app's status screen.
- Add English documentation and retain Russian documentation in `README.ru.md`.
- Document permissions, privacy behavior, and device-specific compatibility limits.

## Build artifact

Build with `bash ./build-apk.sh .`; the APK is written to `build/app.apk`.
