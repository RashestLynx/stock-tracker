plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rashestlynx.tradetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rashestlynx.tradetracker"
        minSdk = 26
        targetSdk = 35
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "2.$run"
    }

    // One fixed signing key, so each new APK installs over the previous one as an update.
    signingConfigs {
        create("tracker") {
            storeFile = file("tracker.keystore")
            storePassword = "tracker123"
            keyAlias = "tracker"
            keyPassword = "tracker123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("tracker")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tracker")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
