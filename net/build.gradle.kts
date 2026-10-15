import sleevesight.checks.CheckNetworkReferences

plugins {
    id("sleevesight.pipeline")
}

// `:net` is the one module that may reach an HTTP client (architecture, Structural constraints), so
// its Detekt configuration lets it import Ktor; the other readiness rules stay.
detekt {
    config.from(rootProject.file("config/detekt/net.yml"))
}

// For the same reason, the class-file check allows Ktor, OkHttp and `java.net` here: `:net` wraps
// Ktor and OkHttp, and its engine factory's `ProtocolException` handling uses `java.net`. It still
// forbids `javax.net`, which `:net` does not need (TLS is OkHttp's).
tasks.named<CheckNetworkReferences>("checkNetworkReferences") {
    forbiddenPrefixes.set(listOf("javax/net/"))
}

dependencies {
    // `implementation`, not `api`: the modules above `:net` see its own types, never Ktor's.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.client.mock)
    // A loopback server, to test what the real OkHttp engine sends; still no internet.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
