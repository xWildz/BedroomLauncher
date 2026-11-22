plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bedroom.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bedroom.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "3.6 Cozy Capybara"
    }

    buildTypes {
        // The APK you install is not debuggable: a computer plugged into the phone can't read the app's data.
        debug {
            isDebuggable = false
        }
        // Release = what update.ps1 builds: signed with the same key as the debug APKs, so it installs over them.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
