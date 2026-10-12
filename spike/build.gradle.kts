plugins {
    id("sleevesight.android-app")
}

android {
    namespace = "io.github.wkusnierczyk.sleevesight.spike"
    defaultConfig {
        applicationId = "io.github.wkusnierczyk.sleevesight.spike"
        versionCode = 1
        versionName = "0.0.1"
    }
}

dependencies {
    implementation(libs.androidx.activity)
    testImplementation(libs.kotlin.test.junit)
}
