plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

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
        buildConfigField("String", "BANG_API_URL", "\"\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
