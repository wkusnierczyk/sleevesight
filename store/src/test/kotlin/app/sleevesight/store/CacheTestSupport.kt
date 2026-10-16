package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.db.CacheDatabase
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A clock the test moves. */
class TestClock(
    var now: Instant = Instant.parse("2026-10-14T12:00:00Z"),
) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/**
 * A driver that runs [beforeNext] once, just before the next statement whose SQL starts with
 * [prefix]: a deterministic stand-in for another thread's call landing between two of ours.
 */
class InterleavingDriver(
    private val driver: SqlDriver,
) : SqlDriver by driver {
    private var prefix: String? = null
    private var beforeNext: (() -> Unit)? = null

    fun before(
        prefix: String,
        action: () -> Unit,
    ) {
        this.prefix = prefix
        beforeNext = action
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val action = beforeNext
        if (action != null && sql.trimStart().startsWith(checkNotNull(prefix))) {
            beforeNext = null
            action()
        }
        return driver.execute(identifier, sql, parameters, binders)
    }
}

/**
 * [Caches] over a fresh in-memory SQLite database, with settings and a clock the test controls. The
 * default settings give Discogs a lifetime of one day, so the tests have a short-lived source besides
 * web search.
 */
class CacheFixture(
    var settings: Settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.DISCOGS to 1.days)),
    val clock: TestClock = TestClock(),
) {
    private val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CacheDatabase.Schema.create(it) }
    val driver: InterleavingDriver = InterleavingDriver(jdbc)
    val normalizer: Normalizer = Normalizer(JvmAccentFolder)
    val caches: Caches = Caches(driver, { settings }, normalizer, clock)

    /** The running totals agree with the rows. */
    fun assertTotals() = assertEquals(caches.countedStats(), caches.stats())

    fun close() = jdbc.close()
}
