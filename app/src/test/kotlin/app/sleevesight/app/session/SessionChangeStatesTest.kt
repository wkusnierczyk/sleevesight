package app.sleevesight.app.session

import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a track change carries besides its timing: the track it left, the time that track had left,
 * and the playback states before it (D15, #49).
 */
@RunWith(RobolectricTestRunner::class)
internal class SessionChangeStatesTest : SessionRepositoryFixture() {
    private val sundownPlaying = metadata(durationMs = 225_000)

    private fun spotify(): FakeSession {
        val spotify = FakeSession(SPOTIFY, metadata = sundownPlaying)
        system.active = listOf(spotify)
        repository.start()
        return spotify
    }

    /** Moves virtual time on to [ms], running what is due before it. */
    private fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    /** A `PLAYING` state reported at [ms], at the position Sundown would have reached by then. */
    private fun FakeSession.playingAt(ms: Long) {
        at(ms)
        send(state(positionMs = 200_000 + (ms - 6_000), updatedAt = ms))
    }

    @Test
    fun aChangeKeepsTheStatesOfTheLastFiveSecondsBeforeIt() {
        val spotify = spotify()
        spotify.playingAt(1_000)
        // A cluster of six states, as Spotify sends before a natural end, then the new metadata.
        for (ms in 6_000L..6_500L step 100) spotify.playingAt(ms)
        at(8_000)
        spotify.send(metadata(title = "Carefree Highway", durationMs = 200_000))
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals(8_000L, change.changedAt)
        assertEquals((6_000L..6_500L step 100).toList(), change.states.map { it.at })
        assertTrue(change.states.all { it.state.status == PlaybackStatus.PLAYING })
        assertEquals(change.before, change.states.last().state)
    }

    @Test
    fun theWindowIsFiveSeconds() {
        val spotify = spotify()
        spotify.playingAt(2_900)
        spotify.playingAt(3_100)
        at(8_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settle()

        // 5.1 s before the change is out, 4.9 s before it is in.
        assertEquals(listOf(3_100L), checkNotNull(repository.lastChange.value).states.map { it.at })
    }

    @Test
    fun aChangeKnowsTheTrackItLeftAndTheTimeItHadLeft() {
        val spotify = spotify()
        spotify.playingAt(6_500)
        at(8_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settle()

        val change = checkNotNull(repository.lastChange.value)
        assertEquals("Sundown", change.from?.title)
        // At 6.5 s Sundown was at 200.5 s, so 202 s at the change, 23 s before its end.
        assertEquals(23.seconds, change.timeLeft)
    }

    @Test
    fun equalReportsAreKeptEachTime() {
        val spotify = spotify()
        at(6_000)
        repeat(3) { spotify.send(state(positionMs = 200_000, updatedAt = 6_000)) }
        at(7_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settle()

        assertEquals(3, checkNotNull(repository.lastChange.value).states.size)
    }

    @Test
    fun atMostTheLatestStatesAreKept() {
        val spotify = spotify()
        for (i in 0L until 40L) spotify.playingAt(6_000 + 10 * i)
        at(7_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settle()

        val states = checkNotNull(repository.lastChange.value).states
        assertEquals(SessionRepository.MAX_STATES_BEFORE_CHANGE, states.size)
        assertEquals(6_390L, states.last().at)
    }

    @Test
    fun withoutALengthThereIsNoTimeLeft() {
        val spotify = FakeSession(SPOTIFY)
        system.active = listOf(spotify)
        repository.start()
        spotify.playingAt(6_500)
        at(8_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settle()

        assertEquals(null, checkNotNull(repository.lastChange.value).timeLeft)
    }
}
