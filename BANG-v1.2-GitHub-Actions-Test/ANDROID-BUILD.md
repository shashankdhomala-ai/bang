# BANG Android build configuration

## Production backend
The Android app no longer expects ordinary users to type a server address during signup/login.
The backend URL is compiled into the app as `BuildConfig.BANG_API_URL`.

Before making a production AAB, edit `app/build.gradle.kts` and replace the empty value:

`buildConfigField("String", "BANG_API_URL", "\"\"")`

with the real HTTPS BANG API origin, for example:

`buildConfigField("String", "BANG_API_URL", "\"https://api.example.com\"")`

Do not use a made-up URL in a release build. The current project intentionally leaves it blank because no real hosted BANG backend/domain has been supplied yet.

## Development
For a local laptop backend, use a debug build and the existing Connection settings. The debug manifest permits cleartext HTTP for local testing.

## Release requirements
Use HTTPS/WSS, a production database, restricted CORS, authentication/session expiry, rate limiting, abuse controls, privacy policy and Play Console Data Safety disclosures before public release.


For v1.0.1, build with Android Studio using the included Gradle project and test on at least 3 physical Android phones for A→B→C calling.
