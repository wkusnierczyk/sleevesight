// A pipeline module (`:core`, `:sources`, `:ai`, `:wiring`, `:store`, `:net`): a plain Kotlin JVM module kept
// Multiplatform-ready (architecture, decision D2). JSON goes through kotlinx.serialization, so its
// compiler plugin is applied here.
//
// The readiness checks, in layers:
// - Detekt (`config/detekt/pipeline.yml`) forbids JVM-only APIs in the portable code, `main`, and
//   in the tests and test fixtures: the fast first line, in the source.
// - Platform implementations sit in their own source set, `jvmMain` (`sleevesight.jvm-main`),
//   which `main` cannot see: portable code that uses one does not compile. Detekt checks
//   `jvmMain` with the base rules only (`config/detekt/detekt.yml`), so JVM APIs are allowed there
//   and network imports are not.
// - `checkNetworkReferences` reads the compiled classes of both and fails on any reference to a
//   network API, however the source spelled it.
// - `checkPortableCode` reads the compiled classes of `main`, the tests and the test fixtures, and
//   fails on the JVM-only constructs Detekt misses as Kotlin usually writes them: `synchronized`,
//   the access-order `LinkedHashMap` constructor, `String.format`, and an override of
//   `removeEldestEntry` in a class descended from `LinkedHashMap` through the classes it reads.
import io.gitlab.arturbosch.detekt.Detekt
import sleevesight.checks.CheckNetworkReferences
import sleevesight.checks.CheckPortableCode

plugins {
    id("sleevesight.kotlin-jvm")
    id("sleevesight.jvm-main")
    id("org.jetbrains.kotlin.plugin.serialization")
}

detekt {
    config.setFrom(
        rootProject.file("config/detekt/detekt.yml"),
        rootProject.file("config/detekt/pipeline.yml"),
    )
}

tasks.named<Detekt>("detektJvmMain") {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

val sourceSets = extensions.getByType<SourceSetContainer>()

val checkNetworkReferences =
    tasks.register<CheckNetworkReferences>("checkNetworkReferences") {
        group = "verification"
        description = "Fails when a compiled class of main or jvmMain refers to a network API."
        classes.from(sourceSets.named("main").map { it.output.classesDirs })
        classes.from(sourceSets.named("jvmMain").map { it.output.classesDirs })
        // The packages of the network `ForbiddenImport` entries in `config/detekt/detekt.yml`; a
        // change to one list is made to the other. `:net`, which wraps OkHttp and Ktor, sets a
        // shorter list (or an empty one).
        forbiddenPrefixes.convention(listOf("java/net/", "javax/net/", "okhttp3/", "io/ktor/client/"))
    }

val checkPortableCode =
    tasks.register<CheckPortableCode>("checkPortableCode") {
        group = "verification"
        description =
            "Fails when a compiled class of main, test or testFixtures uses synchronized, an access-order " +
                "LinkedHashMap, String.format or a LinkedHashMap.removeEldestEntry override."
        classes.from(sourceSets.named("main").map { it.output.classesDirs })
        classes.from(sourceSets.named("test").map { it.output.classesDirs })
    }

// The readiness rules need type resolution, which the plain `detekt` task in `check` does not do.
tasks.named("check") {
    dependsOn("detektMain", "detektJvmMain", "detektTest", checkNetworkReferences, checkPortableCode)
}

// A module with shared test fixtures (`java-test-fixtures`) checks them the same way.
pluginManager.withPlugin("java-test-fixtures") {
    tasks.named("check") {
        dependsOn("detektTestFixtures")
    }
    checkPortableCode.configure {
        classes.from(sourceSets.named("testFixtures").map { it.output.classesDirs })
    }
}
