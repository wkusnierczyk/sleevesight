plugins {
    id("sleevesight.pipeline")
}

// `:net` is the one module that may reach an HTTP client (architecture, Structural constraints), so
// its Detekt configuration lets it import Ktor; the other readiness rules stay.
detekt {
    config.from(rootProject.file("config/detekt/net.yml"))
}

dependencies {
    // `implementation`, not `api`: the modules above `:net` see its own types, never Ktor's.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
