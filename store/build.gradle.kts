// The caches (architecture, *Cache and history*): SQLDelight schema and queries over an injected
// `SqlDriver`. `:cli` supplies the SQLite JDBC driver, `:app` the Android one; the tests use the
// JDBC driver on an in-memory database.
plugins {
    id("sleevesight.sqldelight")
}

sqldelight {
    databases {
        create("CacheDatabase") {
            packageName = "io.github.wkusnierczyk.sleevesight.store.db"
        }
    }
}

dependencies {
    implementation(project(":core"))
    // `SqlDriver` is in `Caches`' constructor.
    api(libs.sqldelight.runtime)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.sqldelight.sqlite.driver)
}
