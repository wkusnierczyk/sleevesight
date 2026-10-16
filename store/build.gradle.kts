// The caches and the session history (architecture, *Cache and history*): SQLDelight schemas and
// queries over an injected `SqlDriver`. `:cli` supplies the SQLite JDBC driver, `:app` the Android
// one; the tests use the JDBC driver on an in-memory database. Two databases, each in its own
// file: the caches, which may be evicted or rebuilt, and the history, which is the user's (#49).
plugins {
    id("sleevesight.sqldelight")
}

sqldelight {
    databases {
        create("CacheDatabase") {
            packageName = "app.sleevesight.store.db"
            srcDirs("src/main/sqldelight")
        }
        // The history cannot be rebuilt, so each schema version is kept (`databases/<version>.db`,
        // from `generateMainHistoryDatabaseSchema`) and every migration is checked against it.
        // Regenerate only after adding the migration's `.sqm`, which writes the next version;
        // never overwrite a committed `.db`.
        create("HistoryDatabase") {
            packageName = "app.sleevesight.store.history"
            srcDirs("src/main/history")
            schemaOutputDirectory = file("src/main/history/databases")
            verifyMigrations = true
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
