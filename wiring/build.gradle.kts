// The network side put together (architecture, *Modules*): the one list of host policies and the
// `NetConfig` built from it, the app's one `NetClient`, and the adapters, the resolver, the art fetch
// and the AI providers built on a client, handed to `:app` as `:core` types only. It makes no request
// of its own. A pipeline module, so the readiness rules and `checkNetworkReferences` cover it.
plugins {
    id("sleevesight.pipeline")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sources"))
    implementation(project(":ai"))
    // `implementation`, never `api`: `:net` may not be on `:app`'s compile classpath, even
    // transitively (`compile-forbidden.app` in `config/module-graph.properties`).
    implementation(project(":net"))

    testImplementation(libs.kotlinx.coroutines.test)
}
