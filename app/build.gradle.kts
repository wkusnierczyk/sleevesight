import sleevesight.release.ReleaseVersion

plugins {
    id("sleevesight.android-app")
}

// A release takes its version from its tag, `v<major>.<minor>.<patch>`, which the release workflow
// passes as `-Psleevesight.version` (`design/release.md`, *Versioning*); other builds are 0.1.0.
val appVersion = ReleaseVersion.parse(providers.gradleProperty("sleevesight.version").getOrElse("0.1.0"))

android {
    namespace = "app.sleevesight.app"
    // `BuildConfig.VERSION_NAME`, for the User-Agent the app's one `NetClient` sends (`:wiring`).
    buildFeatures.buildConfig = true
    defaultConfig {
        applicationId = "app.sleevesight"
        versionCode = appVersion.code
        versionName = appVersion.name
    }
    testOptions {
        // Robolectric reads the app's resources and manifest, and sets a `FileDescriptor`'s
        // descriptor through the JDK's internal `SharedSecrets`, which needs that package exported.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    buildTypes {
        // The debug APK (the CI artifact) installs beside a release from Google Play.
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    // The domain types the session layer maps to (`NowPlaying`), and `Settings`, `Secret` and the
    // `AiProvider` interface the settings screen lists models through.
    implementation(project(":core"))
    // The session history and the record of track changes (#49), on the Android SQLite driver.
    implementation(project(":store"))
    implementation(libs.sqldelight.android.driver)
    // The pipeline's adapters, resolver, AI providers and art fetch, on the process's one
    // `NetClient`, which `:wiring` creates and keeps: `:app` gets `:core` types only, and `:net`
    // stays off its compile classpath (`compile-forbidden.app` in `config/module-graph.properties`).
    implementation(project(":wiring"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    // Navigation's typed routes compile against it; declared so compile and runtime agree with
    // the version `:core` brings at runtime.
    implementation(libs.kotlinx.serialization.core)
    // Renders the previews in the IDE; debug only.
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JUnit 4, which Robolectric needs (architecture, *Technology choices*).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    // Virtual time for the session layer's settle window.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // The history's tests run on an in-memory database through the JDBC driver, as `:store`'s do.
    testImplementation(libs.sqldelight.sqlite.driver)
}
