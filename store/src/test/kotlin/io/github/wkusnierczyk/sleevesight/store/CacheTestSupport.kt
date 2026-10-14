package io.github.wkusnierczyk.sleevesight.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.store.db.CacheDatabase
import kotlin.time.Clock
import kotlin.time.Duration
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

/** [Caches] over a fresh in-memory SQLite database, with settings and a clock the test controls. */
class CacheFixture(
    var settings: Settings = Settings(),
    val clock: TestClock = TestClock(),
) {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CacheDatabase.Schema.create(it) }
    val caches: Caches = Caches(driver, { settings }, clock)

    fun close() = driver.close()
}
