# Tap Deuce for Android

The full game as an Android app. Local rooms connect two phones directly over Bluetooth and Wi-Fi using Google Nearby Connections, with no computer or internet needed.

## Build the APK with Android Studio
1. Install Android Studio from developer.android.com/studio.
2. Choose Open and select this folder. Wait for it to finish syncing.
3. Choose Build > Build App Bundle(s) / APK(s) > Build APK(s).
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to both phones and open it. Android will ask you to allow installing from that source.

## Build the APK without Android Studio
Upload this folder to a GitHub repository. The included workflow builds the APK automatically. Download it from the repository's Actions tab, under the latest run's artifacts.

## Play in a local room
1. Install the app on both phones.
2. On one phone, choose Local room, then Create room, and allow the Nearby devices permission.
3. On the other phone, choose Local room, enter the code, and tap Join room.

Both phones need Google Play services. Keep Bluetooth and Wi-Fi on.
