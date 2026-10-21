package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.store.db.CacheDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A cache kept at version 1 of its schema, with rows, upgrades with them intact and gains the
 * artist-level table (#238). The database is built from the committed `databases/1.db`, by the
 * statements that made it, filled as version 1's queries wrote it, and migrated from version 1 by
 * the schema, as `AndroidSqliteDriver` does when it opens a database of an older version (and the
 * CLI's JDBC driver, given the schema). `verifyMainCacheDatabaseMigration` checks the migrated
 * schema is the current one; this checks the rows and the running totals. In memory, as
 * `HistoryMigrationTest` is.
 */
class CacheMigrationTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val clock = TestClock(Instant.parse("2026-10-21T12:00:00Z"))

    @AfterTest
    fun close() = driver.close()

    @Test
    fun `the schema is at version 2`() {
        assertEquals(2L, CacheDatabase.Schema.version)
    }

    @Test
    fun `a version 1 cache upgrades with its entries and totals, and keeps artist-level results`() {
        versionOne(driver)
        CacheDatabase.Schema.migrate(driver, 1, CacheDatabase.Schema.version)
        assertUpgraded(driver)
    }

    @Test
    fun `a version 1 cache opened by the driver with the schema is migrated, as the CLI and the app open it`() {
        // One in-memory database two connections share, kept while [kept] is open.
        val url = "jdbc:sqlite:file:cache-version-1?mode=memory&cache=shared"
        val kept = JdbcSqliteDriver(url)
        try {
            versionOne(kept)
            kept.run("PRAGMA user_version = 1")

            val opened = JdbcSqliteDriver(url, schema = CacheDatabase.Schema)
            try {
                assertEquals(2L, opened.userVersion())
                assertUpgraded(opened)
            } finally {
                opened.close()
            }
        } finally {
            kept.close()
        }
    }

    // The rows of [versionOne] are there, the totals are exact, and the artist-level table works.
    private fun assertUpgraded(driver: SqlDriver) {
        val caches = Caches(driver, { Settings() }, Normalizer(JvmAccentFolder), clock)

        assertEquals("v1 text", caches.synthesis.get(SYNTHESIS_KEY)?.text)
        assertEquals(ArtistResolution.Resolved("mbid-a"), caches.artists.get("gordon lightfoot"))
        assertNotNull(caches.retrieval.get(SourceId.MUSICBRAINZ, "k", 1))
        assertEquals(caches.countedStats(), caches.stats(), "the totals kept from version 1 are exact")
        assertEquals(TableStats(0, 0), caches.stats().artistResults)

        val part = ArtistPart("lastfm.artist", 1, 1, setOf(SourceId.LASTFM))
        caches.artistResults.put(part, "gordon lightfoot", "v")
        assertEquals("v", caches.artistResults.get(part, "gordon lightfoot")?.value)
        assertEquals(caches.countedStats(), caches.stats(), "and the new table's total is kept")
        assertEquals(4, caches.stats().entries)
    }

    /**
     * Version 1 with rows in it: `1.db`'s tables, indexes and triggers, made by its own statements,
     * its running totals' rows, and a row in each table as version 1's queries wrote it.
     */
    private fun versionOne(driver: SqlDriver) {
        // Read-only, so a missing file fails rather than being created.
        driver.run("ATTACH DATABASE 'file:$SCHEMAS/1.db?mode=ro' AS v1")
        val statements =
            driver
                .executeQuery(
                    identifier = null,
                    sql =
                        "SELECT sql FROM v1.sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                            "ORDER BY rowid",
                    mapper = { cursor ->
                        val rows = mutableListOf<String>()
                        while (cursor.next().value) rows += checkNotNull(cursor.getString(0))
                        QueryResult.Value(rows)
                    },
                    parameters = 0,
                ).value
        // Four tables, three indexes and six triggers.
        assertEquals(13, statements.size, "the tables, indexes and triggers of version 1: $statements")
        statements.forEach { driver.run(it) }
        // Version 1's running totals, as its schema made them.
        driver.run("INSERT INTO cache_size (name, entries, bytes) SELECT name, entries, bytes FROM v1.cache_size")
        driver.run("DETACH DATABASE v1")

        // As version 1's queries wrote them: a retrieval entry, an artist resolution and a card.
        val ms = clock.now.toEpochMilliseconds()
        driver.run(
            "INSERT INTO retrieval (source, lookup_key, sources, format, extraction, payload, stored_at, used_at, " +
                "size_bytes) VALUES ('MB', 'k', ' MB ', ${RetrievalCodec.FORMAT}, 1, " +
                "'${RetrievalCodec.encode(result())}', $ms, $ms, 10)",
        )
        driver.run(
            "INSERT INTO artist (name, state, mbid, stored_at, used_at, size_bytes) " +
                "VALUES ('gordon lightfoot', 'resolved', 'mbid-a', $ms, $ms, 30)",
        )
        driver.run(
            "INSERT INTO synthesis (kind, entity_key, evidence_fingerprint, prompt_version, style_hash, provider, " +
                "model, model_knowledge, effort, text, written_at, used_at, size_bytes) VALUES ('insight', 'k', " +
                "'fp', 1, 'style', 'ANTHROPIC', 'model', 0, 'default', 'v1 text', $ms, $ms, 20)",
        )
    }

    private fun result() = SourceResult(Outcome.None, emptyList(), Duration.ZERO)

    private fun SqlDriver.run(sql: String) {
        execute(null, sql, 0)
    }

    private fun SqlDriver.userVersion(): Long =
        executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).value ?: error("no user_version")

    private companion object {
        /** The committed schema versions; tests run in the module's directory. */
        const val SCHEMAS = "src/main/sqldelight/databases"

        val SYNTHESIS_KEY =
            SynthesisKey(CardKind.INSIGHT, "k", "fp", 1, "style", ProviderId.ANTHROPIC, "model", false, null)
    }
}
