// The Phase 0.5 command-line tool (architecture, *Modules*): runs the pipeline over the fixture
// playlist and writes the coverage report and the insights; replays and records the HTTP fixtures.
// A plain JVM application, not a pipeline module: it reads files and the environment, and opens the
// process's one `NetClient`.
plugins {
    id("sleevesight.kotlin-jvm")
    application
}

application {
    mainClass = "app.sleevesight.cli.MainKt"
    applicationName = "sleevesight-cli"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":store"))
    // The host policies and the adapters and providers, built on the CLI's client (architecture,
    // *Modules*): the same as the app's.
    implementation(project(":wiring"))
    // The CLI creates the process's one `NetClient` from the `NetConfig` `:wiring` builds, and
    // replays or records through it (architecture, *Networking*).
    implementation(project(":net"))
    // The fixture playlist (`BelowSoundtrack`, `RegressionCase`) is the CLI's input.
    implementation(testFixtures(project(":core")))
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Draws the keys' setup code in the terminal (`keys-qr`, #268).
    implementation(libs.zxing.core)
}

val fixturesDir = rootProject.layout.projectDirectory.dir("fixtures")

// Run from the repository root, so the default `fixtures/` and `.dev/` paths resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // `keys-qr --<name>-from -` reads a key from standard input.
    standardInput = System.`in`
}

tasks.withType<Test>().configureEach {
    systemProperty("sleevesight.fixtures", fixturesDir.asFile.absolutePath)
    inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}
