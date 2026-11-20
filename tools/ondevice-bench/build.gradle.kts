// The same Android Gradle plugin and Kotlin as the app's build (`gradle/libs.versions.toml`).
// AGP 9 compiles Kotlin itself; the Kotlin plugin on the classpath only sets its version.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
