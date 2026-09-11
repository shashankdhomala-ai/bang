# Build BANG APK with GitHub Actions

This project can build a test APK in GitHub Actions without Android Studio.

## Steps

1. Create a GitHub repository and upload the contents of this folder.
2. Make sure the default branch is `main` (or `master`).
3. Open the repository's **Actions** tab.
4. Select **BANG Android APK**.
5. Click **Run workflow** if you want to start it manually.
6. Wait for the green check mark.
7. Open the completed workflow run and download the **BANG-debug-apk** artifact.
8. Extract the artifact and install `app-debug.apk` on a test Android phone.

## Important

This workflow creates a **debug/test APK**, not a Play Store release. Before public release, BANG needs a signed release AAB, privacy/data-safety review, production backend, and physical-device testing.
