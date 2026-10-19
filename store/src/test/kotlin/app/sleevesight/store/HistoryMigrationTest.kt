package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.history.HistoryDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A history kept at version 1 of its schema, with rows, upgrades with them intact (#257). The
 * database is built from the committed `databases/1.db`, by the statements that made it, filled as
 * version 1's queries wrote it, and migrated from version 1 by the schema, as `AndroidSqliteDriver`
 * does when it opens a database of an older version. `verifyMainHistoryDatabaseMigration` checks
 * the migrated schema is the current one; this checks the rows. In memory, since the pipeline
 * modules' tests use no JVM file API either.
 */
class HistoryMigrationTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    private val start = Instant.parse("2026-10-16T08:00:00Z")

    @AfterTest
    fun close() = driver.close()

    @Test
    fun `the schema is at version 2`() {
        assertEquals(2L, HistoryDatabase.Schema.version)
    }

    @Test
    fun `a version 1 history upgrades with its tracks and changes`() {
        versionOne()

        val history = SessionHistory(migrated())

        assertEquals(
            listOf(
                PlayedTrack(1, 1, start, sundown, stoppedAt = start + 225.seconds),
                PlayedTrack(2, 1, start + 4.minutes, carefree),
            ),
            history.session(1),
        )
        // Rows from before have no resume: heard until as version 1 counted it.
        assertEquals(listOf(start + 225.seconds, start + 4.minutes), history.session(1).map { it.heardUntil })
        val change = history.changes(limit = 10).changes.single()
        assertEquals(TrackRef("Sundown", "spotify:track:1"), change.from)
        assertEquals(TrackRef("Carefree Highway", null), change.to)
        assertEquals(1_200.milliseconds, change.timeLeft)
        assertEquals(listOf("PLAYING", "PAUSED"), change.states.map { it.status })
        assertEquals(listOf(223_800.milliseconds, null), change.states.map { it.position })
        assertEquals(12.seconds, change.learnedCrossfade)
    }

    @Test
    fun `an upgraded history goes on from where it was`() {
        versionOne()
        val history = SessionHistory(migrated())

        // Carefree Highway, playing when version 1 was left, stops and resumes in its row.
        history.stopped(start + 6.minutes)
        val resumed = history.started(carefree, start + 10.minutes, GAP) { it.title == carefree.title }
        assertEquals(PlayedTrack(2, 1, start + 4.minutes, carefree, resumedAt = start + 10.minutes), resumed)
        assertEquals(resumed, history.latestSession().last())
        // The ids go on from version 1's.
        val next = TrackRef("Sundown", null)
        history.changed(TrackChangeRecord(SPOTIFY, start + 11.minutes, null, next, null, emptyList(), null, null))
        assertEquals(2L, history.lastChangeId())
        assertEquals(3L, history.started(sundown, start + 11.minutes, GAP) { false }.id)
    }

    /**
     * Version 1 with rows in it: `1.db`'s tables and indexes, made by its own statements, and rows
     * as version 1's `playedInsert`, `playedStopped` and `changed` wrote them.
     */
    private fun versionOne() {
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
        driver.run("DETACH DATABASE v1")
        assertEquals(5, statements.size, "the tables and indexes of version 1: $statements")
        statements.forEach { driver.run(it) }

        val ms = start.toEpochMilliseconds()
        driver.run(
            "INSERT INTO played (session, played_at, player, title, artists, album, duration_ms, media_id, " +
                "queue_item, stopped_at) VALUES (1, $ms, '$SPOTIFY', 'Sundown', 'Gordon Lightfoot', 'Sundown', " +
                "225000, 'spotify:track:1', 3, ${ms + 225_000})",
        )
        driver.run(
            "INSERT INTO played (session, played_at, player, title, artists, album, duration_ms, media_id, " +
                "queue_item, stopped_at) VALUES (1, ${ms + 240_000}, '$SPOTIFY', 'Carefree Highway', " +
                "'Gordon Lightfoot', NULL, NULL, NULL, NULL, NULL)",
        )
        driver.run(
            "INSERT INTO track_change (player, changed_at, from_title, from_media_id, to_title, to_media_id, " +
                "time_left_ms, since_action_ms, learned_crossfade_ms) VALUES ('$SPOTIFY', ${ms + 224_000}, " +
                "'Sundown', 'spotify:track:1', 'Carefree Highway', NULL, 1200, NULL, 12000)",
        )
        driver.run(
            "INSERT INTO track_change_state (change_id, seq, received_before_ms, status, position_ms, " +
                "position_before_ms, speed) VALUES (1, 0, 900, 'PLAYING', 223800, 900, 1.0)",
        )
        driver.run(
            "INSERT INTO track_change_state (change_id, seq, received_before_ms, status, position_ms, " +
                "position_before_ms, speed) VALUES (1, 1, 5, 'PAUSED', NULL, NULL, 0.0)",
        )
    }

    /** The database migrated from version 1 to the current one. */
    private fun migrated(): SqlDriver {
        HistoryDatabase.Schema.migrate(driver, 1, HistoryDatabase.Schema.version)
        return driver
    }

    private fun SqlDriver.run(sql: String) {
        execute(null, sql, 0)
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        val GAP = Settings.DEFAULT_SESSION_GAP

        /** The committed schema versions; tests run in the module's directory. */
        const val SCHEMAS = "src/main/history/databases"

        val sundown =
            NowPlaying(
                title = "Sundown",
                artists = "Gordon Lightfoot",
                album = "Sundown",
                duration = 225.seconds,
                player = SPOTIFY,
                mediaId = "spotify:track:1",
                queueItemId = 3,
            )
        val carefree = NowPlaying(title = "Carefree Highway", artists = "Gordon Lightfoot", player = SPOTIFY)
    }
}
