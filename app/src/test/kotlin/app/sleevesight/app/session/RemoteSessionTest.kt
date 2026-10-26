package app.sleevesight.app.session

import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Spotify playing to another device (Spotify Connect, #595), replayed from a capture of the
 * system's log in a car: one session, of playback type remote, whose state is always `PAUSED`.
 * Its speed is 1 while the car plays (the position moves on in real time between reports) and 0
 * while it is paused; a new track arrives `PAUSED` at speed 1 near 0. Read by its type
 * ([SessionMapping.effectiveState]), it shows as playing; the same states from a local session
 * show as paused.
 */
@RunWith(RobolectricTestRunner::class)
internal class RemoteSessionTest : SessionRepositoryFixture() {
    /** One recorded playback state: when it was taken (`updated=`), the position, the speed, and the track. */
    private data class Report(
        val updatedAt: Long,
        val positionMs: Long,
        val speed: Float,
        val title: String,
    )

    /**
     * The states as captured, in order (`onSessionPlaybackStateChanged` of
     * `com.spotify.music/spotify-media-session`, each `state=PAUSED(2)`): played, paused, played,
     * paused, played, then the next track (a new metadata object) and its reports as it plays.
     */
    private val recorded =
        listOf(
            Report(425_629_197, 198_351, 0f, SUNDOWN),
            Report(425_629_203, 198_351, 0f, SUNDOWN),
            Report(425_629_206, 198_351, 0f, SUNDOWN),
            Report(425_640_380, 199_141, 1f, SUNDOWN),
            Report(425_640_383, 199_142, 1f, SUNDOWN),
            Report(425_640_384, 199_142, 1f, SUNDOWN),
            Report(425_648_204, 206_184, 0f, SUNDOWN),
            Report(425_648_206, 206_184, 0f, SUNDOWN),
            Report(425_648_207, 206_184, 0f, SUNDOWN),
            Report(425_650_117, 206_986, 1f, SUNDOWN),
            Report(425_650_118, 206_987, 1f, SUNDOWN),
            Report(425_650_120, 206_987, 1f, SUNDOWN),
            Report(425_656_537, 374, 1f, CAREFREE),
            Report(425_656_538, 374, 1f, CAREFREE),
            Report(425_656_538, 374, 1f, CAREFREE),
            Report(425_656_540, 374, 1f, CAREFREE),
            Report(425_656_540, 375, 1f, CAREFREE),
            Report(425_656_541, 375, 1f, CAREFREE),
            Report(425_656_542, 375, 1f, CAREFREE),
            Report(425_656_543, 376, 1f, CAREFREE),
            Report(425_656_543, 376, 1f, CAREFREE),
            Report(425_656_545, 376, 1f, CAREFREE),
            Report(425_656_569, 399, 1f, CAREFREE),
            Report(425_656_570, 401, 1f, CAREFREE),
            Report(425_656_572, 402, 1f, CAREFREE),
            Report(425_656_573, 402, 1f, CAREFREE),
            Report(425_656_575, 407, 1f, CAREFREE),
            Report(425_656_577, 407, 1f, CAREFREE),
            Report(425_656_597, 428, 1f, CAREFREE),
            Report(425_656_607, 446, 1f, CAREFREE),
            Report(425_656_653, 492, 1f, CAREFREE),
            Report(425_656_654, 492, 1f, CAREFREE),
            Report(425_656_675, 514, 1f, CAREFREE),
            Report(425_656_870, 709, 1f, CAREFREE),
            Report(425_670_498, 14_283, 1f, CAREFREE),
            Report(425_670_499, 14_283, 1f, CAREFREE),
            Report(425_670_501, 14_283, 1f, CAREFREE),
        )

    private fun Report.state(): PlaybackState = state(PlaybackState.STATE_PAUSED, positionMs, speed, updatedAt)

    /** Spotify's one session, of the given type, at the first recorded state; started. */
    private fun spotify(remote: Boolean): FakeSession {
        val first = recorded.first()
        val session =
            FakeSession(
                SPOTIFY,
                metadata = metadata(title = first.title),
                playbackState = first.state(),
                remote = remote,
            )
        system.active = listOf(session)
        repository.start()
        return session
    }

    /** Sends each recorded state to [session], the metadata first where the track changes, as Spotify does. */
    private fun replay(
        session: FakeSession,
        each: (Report) -> Unit,
    ) {
        var title = recorded.first().title
        for (report in recorded) {
            if (report.title != title) {
                title = report.title
                session.send(metadata(title = title))
            }
            session.settled(report.state())
            each(report)
        }
    }

    @Test
    fun aRemoteSessionPausedAtSpeedOneShowsPlayingAndAtSpeedZeroPaused() {
        val car = spotify(remote = true)
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)

        replay(car) { report ->
            val expected = if (report.speed > 0f) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
            assertEquals("at ${report.updatedAt}", expected, repository.playback.value.status)
            // What the history and insights take as playing: only while it plays.
            assertEquals("at ${report.updatedAt}", report.speed > 0f, repository.playing.value != null)
        }
    }

    @Test
    fun theBarMovesBetweenARemoteSessionsReportsAndMeetsTheNextWithinASecond() {
        val car = spotify(remote = true)
        val shown = mutableListOf<Pair<Report, PlaybackInfo>>()
        replay(car) { shown += it to repository.playback.value }

        val checked =
            shown.zipWithNext().filter { (a, b) -> a.first.speed > 0f && a.first.title == b.first.title }
        assertTrue(checked.size > 1)
        for ((at, next) in checked) {
            val (report, playback) = at
            val then = next.first
            // Where the bar is when the next report comes, against where that report says it is.
            val bar = checkNotNull(playback.displayPositionAt(then.updatedAt)).inWholeMilliseconds
            assertTrue("$report then $then: $bar", abs(bar - then.positionMs) <= ONE_SECOND)
            // Skip detection reads the same moving position.
            assertEquals(bar, playback.positionAt(then.updatedAt)?.inWholeMilliseconds)
        }
        // The longest stretch: 13.6 s of play between two reports, the bar on with it.
        val last = shown.last { it.first.positionMs == 709L }.second
        assertEquals(14_337.milliseconds, last.displayPositionAt(425_670_498))
    }

    @Test
    fun aNewTrackOnARemoteSessionPlaysFromItsStart() {
        val car = spotify(remote = true)
        var first: PlaybackInfo? = null
        replay(car) { if (it.title == CAREFREE && first == null) first = repository.playback.value }

        val playback = checkNotNull(first)
        assertEquals(PlaybackInfo(SPOTIFY, PlaybackStatus.PLAYING, 374.milliseconds, 425_656_537, 1f), playback)
        // Five seconds on, the bar is five seconds into the track: not frozen at the start.
        assertEquals(5_374.milliseconds, playback.displayPositionAt(425_661_537))
        assertEquals(CAREFREE, repository.nowPlaying.value?.title)
    }

    @Test
    fun theSameStatesFromALocalSessionStayPaused() {
        val phone = spotify(remote = false)

        replay(phone) { report ->
            val playback = repository.playback.value
            assertEquals("at ${report.updatedAt}", PlaybackStatus.PAUSED, playback.status)
            assertEquals(report.positionMs.milliseconds, playback.positionAt(report.updatedAt + ONE_SECOND))
            assertNull(repository.playing.value)
        }
    }

    @Test
    fun aLocalSessionStoppedAtSpeedOneStaysStopped() {
        // Audioteka, stopped: `STOPPED` at speed 1, of playback type local.
        followed.value = setOf(AUDIOTEKA)
        val stopped = state(PlaybackState.STATE_STOPPED, positionMs = 406_220, speed = 1f, updatedAt = 426_152_503)
        system.active = listOf(FakeSession(AUDIOTEKA, playbackState = stopped))
        repository.start()

        val playback = repository.playback.value
        assertEquals(PlaybackStatus.STOPPED, playback.status)
        assertEquals(406_220.milliseconds, playback.displayPositionAt(426_162_503))
        assertNull(repository.playing.value)
    }

    @Test
    fun aRemoteSessionPlayingIsChosenOverAnotherPlayersPausedOneListedFirst() {
        followed.value = setOf(YOUTUBE_MUSIC, SPOTIFY)
        val paused = state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f)
        val other =
            FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "If You Could Read My Mind"), playbackState = paused)
        val car =
            FakeSession(
                SPOTIFY,
                metadata = metadata(title = CAREFREE),
                playbackState = recorded.last().state(),
                remote = true,
            )
        system.active = listOf(other, car)

        repository.start()

        assertEquals(CAREFREE, repository.nowPlaying.value?.title)
        assertEquals(PlaybackStatus.PLAYING, repository.playback.value.status)
    }

    @Test
    fun playbackMovingBetweenThePhoneAndAnotherDeviceIsReadByTheNewType() {
        // Paused at speed 1 on the phone: paused. No new state follows the move, only the new type.
        val session = spotify(remote = false)
        session.settled(recorded.last().state())
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)

        session.playOn(remote = true)
        assertEquals(PlaybackStatus.PLAYING, repository.playback.value.status)

        session.playOn(remote = false)
        assertEquals(PlaybackStatus.PAUSED, repository.playback.value.status)
    }

    private companion object {
        const val SUNDOWN = "Sundown"
        const val CAREFREE = "Carefree Highway"
        const val AUDIOTEKA = "com.audioteka"
        const val ONE_SECOND = 1_000L
    }
}
