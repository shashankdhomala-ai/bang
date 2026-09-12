plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val configuredBangApiUrl = providers.environmentVariable("BANG_API_URL")
    .orElse(providers.gradleProperty("BANG_API_URL"))
    .orNull
    ?.trim()
    ?.removeSuffix("/")
    .orEmpty()

val releaseVersionName = providers.gradleProperty("versionName").orNull?.trim().orEmpty()
val releaseVersionCode = providers.gradleProperty("versionCode").orNull?.trim()?.toIntOrNull()

fun bangApiBuildConfigValue(url: String): String = "\"${url.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    buildFeatures { buildConfig = true }
    namespace = "com.bang.offlinechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bang.offlinechat"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode ?: 14
        versionName = releaseVersionName.ifBlank { "1.0.1" }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
            val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            val keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
            val keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            val debugApi = configuredBangApiUrl.ifBlank { "http://192.168.1.11:8080" }
            buildConfigField("String", "BANG_API_URL", bangApiBuildConfigValue(debugApi))
        }

        release {
            if (configuredBangApiUrl.isBlank()) {
                throw GradleException("BANG_API_URL is required for release builds.")
            }
            require(configuredBangApiUrl.startsWith("https://")) {
                "BANG_API_URL for release must use HTTPS."
            }
            val signingConfigured = !providers.environmentVariable("KEYSTORE_FILE").orNull.isNullOrBlank()
            if (!signingConfigured) {
                throw GradleException("Release signing is not configured. Provide KEYSTORE_FILE and signing credentials.")
            }
            signingConfig = signingConfigs.getByName("release")
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
