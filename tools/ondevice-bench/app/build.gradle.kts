plugins {
    id("com.android.application")
}

android {
    namespace = "app.sleevesight.bench"
    compileSdk = 37
    defaultConfig {
        // Its own id: installs beside Sleevesight and never touches its data.
        applicationId = "app.sleevesight.bench"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-spike272"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // The runtime's native library is loaded from the APK as is.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // LiteRT-LM's Android runtime (https://github.com/google-ai-edge/LiteRT-LM), Apache-2.0.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
}
