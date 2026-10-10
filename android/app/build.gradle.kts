plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.edu.ahu.cardqr"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.edu.ahu.cardqr"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // Filled from the environment by CI, or from keystore.properties locally.
            // When absent the release build simply stays unsigned (debug builds are unaffected).
            val storeFilePath = System.getenv("AHU_KEYSTORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("AHU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AHU_KEY_ALIAS")
                keyPassword = System.getenv("AHU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("AHU_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

// No third-party runtime dependencies: the HTTP client, crypto and QR encoder are all
// written against the Android framework / JDK so the APK is self-contained.
// JUnit is test-only and is what CI runs to pin the crypto and QR ports.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
