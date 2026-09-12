plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bang.offlinechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bang.offlinechat"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.0.2"
    }

    buildFeatures {
        buildConfig = true
    }

    val configuredBangApiUrl = providers.gradleProperty("BANG_API_URL").orNull
        ?: providers.environmentVariable("BANG_API_URL").orNull
        ?: ""

    fun bangApiBuildConfigValue(url: String): String =
        "\"${url.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    signingConfigs {
        create("release") {
            val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
            val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            val keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
            val keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                if (!keystorePassword.isNullOrBlank()) storePassword = keystorePassword
                if (!keyAlias.isNullOrBlank()) this.keyAlias = keyAlias
                if (!keyPassword.isNullOrBlank()) this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            val debugApi = configuredBangApiUrl.ifBlank { "http://192.168.1.11:8080" }
            buildConfigField("String", "BANG_API_URL", bangApiBuildConfigValue(debugApi))
        }

        release {
            val releaseRequested = gradle.startParameter.taskNames.any {
                it.contains("Release", ignoreCase = true)
            }
            if (releaseRequested) {
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
            }
            signingConfig = signingConfigs.getByName("release")
            buildConfigField(
                "String",
                "BANG_API_URL",
                bangApiBuildConfigValue(configuredBangApiUrl)
            )
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

dependencies {
    implementation("androidx.webkit:webkit:1.17.0")
}
