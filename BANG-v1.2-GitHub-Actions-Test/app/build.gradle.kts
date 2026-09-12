plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val configuredBangApiUrl = providers.environmentVariable("BANG_API_URL")
    .orElse(providers.gradleProperty("BANG_API_URL"))
    .orNull
    ?.trim()
    ?.removeSuffix("/")
    .orEmpty()

fun bangApiBuildConfigValue(url: String): String = "\"${url.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    buildFeatures { buildConfig = true }
    namespace = "com.bang.offlinechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bang.offlinechat"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "1.0.1"
    }

    buildTypes {
        debug {
            // Keep local-LAN development convenient. Release builds never use this fallback.
            val debugApi = configuredBangApiUrl.ifBlank { "http://192.168.1.11:8080" }
            buildConfigField("String", "BANG_API_URL", bangApiBuildConfigValue(debugApi))
        }

        release {
            if (configuredBangApiUrl.isBlank()) {
                throw GradleException(
                    "BANG_API_URL is required for release builds. Set it to the HTTPS production API URL."
                )
            }
            require(configuredBangApiUrl.startsWith("https://")) {
                "BANG_API_URL for release must use HTTPS."
            }
            buildConfigField("String", "BANG_API_URL", bangApiBuildConfigValue(configuredBangApiUrl))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
