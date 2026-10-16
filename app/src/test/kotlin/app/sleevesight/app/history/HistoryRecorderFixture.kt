package app.sleevesight.app.history

import android.database.SQLException
import android.media.MediaMetadata
import android.media.session.PlaybackState
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.app.session.FakeSession
import app.sleevesight.app.session.SessionRepositoryFixture
import app.sleevesight.app.session.Sessions
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.PlayedTrack
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.history.HistoryDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The real session layer, recorder and history together, in virtual time, with no screen: as the
 * listener service runs them. The wall clock reads [base] plus the virtual time.
 */
internal abstract class HistoryRecorderFixture : SessionRepositoryFixture() {
    private val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HistoryDatabase.Schema.create(it) }
    protected val driver = FailingDriver(jdbc)
    protected val history = SessionHistory(driver)
    protected val warnings = mutableListOf<String>()

    /** The user's *Start a new session after a break of*, as each read of the settings gives it. */
    protected val sessionGap = MutableSharedFlow<Duration>(replay = 1)

    /** Whether the settings have been read when the recorder starts: then the gap is the default. */
    protected open val settingsRead = true
    val base: Instant = Instant.parse("2026-10-16T08:00:00Z")
    private val wall =
        object : Clock {
            override fun now(): Instant = base + scheduler.currentTime.milliseconds
        }

    /** Where the writes run: at once, unless a test holds them. */
    protected open val io: CoroutineContext = EmptyCoroutineContext

    protected lateinit var recorder: HistoryRecorder

    @Before
    fun record() {
        if (settingsRead) sessionGap.tryEmit(Settings.DEFAULT_SESSION_GAP)
        recorder =
            HistoryRecorder(
                repository,
                history,
                io = io,
                sessionGap = sessionGap,
                elapsed = { scheduler.currentTime },
                wall = wall,
                warn = { warnings += it },
            )
        scope.launch { recorder.run() }
    }

    @After
    fun close() = jdbc.close()

    /** Follows [session] alone and starts the session layer on it. */
    fun begin(session: FakeSession): FakeSession {
        beginAll(session)
        return session
    }

    /** Follows [sessions], in the system's order, and starts the session layer on them. */
    fun beginAll(vararg sessions: FakeSession) {
        followed.value = sessions.map { it.player }.toSet()
        system.active = sessions.toList()
        repository.start()
    }

    /** A row of the history from before this process: [track], started at [at] and never stopped. */
    fun stored(
        track: NowPlaying,
        at: Instant,
    ) {
        history.started(track, at, Settings.DEFAULT_SESSION_GAP) { false }
    }

    /** Spotify playing [metadata], or in [playbackState]. */
    fun spotify(
        metadata: MediaMetadata = metadata(durationMs = 225_000),
        playbackState: PlaybackState = state(),
    ): FakeSession = begin(FakeSession(Sessions.SPOTIFY, metadata = metadata, playbackState = playbackState))

    fun stopLayer() = repository.stop()

    fun startLayer() = repository.start()

    /** Moves virtual time on to [ms], running what is due before it. */
    fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    /** Sends [metadata] and lets it settle. */
    fun FakeSession.report(metadata: MediaMetadata) {
        send(metadata)
        settle()
    }

    /** Sends [state] and lets it settle. */
    fun FakeSession.report(state: PlaybackState) {
        send(state)
        settle()
    }

    /** Lets what was sent settle. */
    fun settleAll() = settle()

    /** Every row of the history, oldest first. */
    fun allRows(): List<PlayedTrack> =
        generateSequence(1L) {
            it + 1
        }.map(history::session).takeWhile { it.isNotEmpty() }.flatten().toList()

    /** A driver whose next statement starting with [failNext] fails, as a full disk would. */
    class FailingDriver(
        private val driver: SqlDriver,
    ) : SqlDriver by driver {
        var failNext: String? = null

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            val prefix = failNext
            if (prefix != null && sql.trimStart().startsWith(prefix)) {
                failNext = null
                throw SQLException("disk full")
            }
            return driver.execute(identifier, sql, parameters, binders)
        }
    }
}
