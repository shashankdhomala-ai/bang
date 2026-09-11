# BANG v0.6.1 — bug-fixed Play Store foundation

This build fixes the major prototype blockers found in v0.6.0:

- Android build no longer depends on `androidx.activity` just for `ComponentActivity`.
- Backend now actually implements signup, login, sessions, invites, friend requests and friend acceptance.
- Backend serves the web client from the project root.
- CORS headers are included for the WebView/mobile client during development.
- WebSocket chat is room-aware and validates message length.
- Invite links use the configured BANG server instead of a `file://` origin.
- Friend requests now have an Accept action.
- Safer static-file path handling prevents path traversal.
- App version bumped to 0.6.1 / versionCode 7.

## Run backend

```bash
npm install
npm start
```

The development server listens on port 8080.

## Android

Open the project in Android Studio with Android SDK 36 installed. Build the debug APK first, then test the release AAB after configuring a production HTTPS backend and signing key.

## Important production work still required

This is not yet a production-ready social network. Before Play release, replace the JSON database/session prototype with a proper production database and secure authentication/session system, use HTTPS/WSS, restrict CORS, add abuse/rate limiting, moderation, reporting/blocking, privacy policy and Data Safety disclosures, and complete Android/Play testing.

## Mesh v1

Version 0.7.0 adds the first native BLE mesh transport prototype. See `MESH-V1.md` for the scope and testing notes.


## v1.0 calling
BANG now includes a native Wi-Fi Direct voice transport foundation: AudioRecord capture, AudioTrack playback, framed local socket transport, and Wi-Fi Direct discovery. See CALLING-A2C.md.


## v1.0.1 stability hardening
- Corrected microphone/Nearby permission continuation so the requested call action is resumed after permission approval.
- Starts/stops the microphone foreground service with user-initiated call actions.
- Uses non-exported runtime receiver registration on Android 13+.
- Detects devices without Wi-Fi Direct support before discovery.
- Adds safer audio-server accept cancellation and socket TCP_NODELAY.
- Validates AudioRecord/AudioTrack initialization before starting capture/playback.

### Important
This source has been statically checked in this environment, but an actual Android APK/AAB cannot be compiled here because the Android SDK/Gradle toolchain is not installed. No software can honestly be promised as “100% bug-free” before testing on the target Android phones.
