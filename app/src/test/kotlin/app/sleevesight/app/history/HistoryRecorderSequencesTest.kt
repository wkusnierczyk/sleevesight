package app.sleevesight.app.history

import android.media.session.PlaybackState
import app.sleevesight.app.session.FakeSession
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.PlayedTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Each sequence of reports, through the real session layer, recorder and history, and the rows
 * it leaves: title, listening session, and when each was seen to start and stop playing, in
 * milliseconds of virtual time. Times are as seen, once the reports have settled: [S] after the
 * last report of a burst.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
internal class HistoryRecorderSequencesTest(
    private val name: String,
    private val steps: Steps,
) : HistoryRecorderFixture() {
    @Test
    fun leavesTheRows() {
        steps.run(this)

        assertEquals(name, steps.rows, allRows().map { it.row() })
    }

    private fun PlayedTrack.row() =
        Row(
            title = track.title,
            session = session,
            playedAt = (playedAt - base).inWholeMilliseconds,
            stoppedAt = stoppedAt?.let { (it - base).inWholeMilliseconds },
            album = track.album,
            artists = track.artists,
            player = track.player,
        )

    /** A row as the test expects it. */
    data class Row(
        val title: String,
        val session: Long,
        val playedAt: Long,
        val stoppedAt: Long?,
        val album: String? = null,
        val artists: String = "Gordon Lightfoot",
        val player: String = SPOTIFY,
    )

    class Steps(
        val rows: List<Row>,
        val run: HistoryRecorderFixture.() -> Unit,
    )

    companion object {
        /** The settle window, in milliseconds. */
        private val S = SessionRepository.SETTLE.inWholeMilliseconds

        /** The session gap, in milliseconds. */
        private val GAP = Settings.DEFAULT_SESSION_GAP.inWholeMilliseconds

        private const val MINUTE = 60_000L
        private const val FORTY_MINUTES = 40 * MINUTE
        private const val SUNDOWN = "Sundown"
        private const val CAREFREE = "Carefree Highway"

        // Built in the test, in Robolectric's sandbox: the framework types need it.
        private fun paused() = state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f)

        private fun sequence(
            name: String,
            vararg rows: Row,
            run: HistoryRecorderFixture.() -> Unit,
        ): Array<Any> = arrayOf(name, Steps(rows.toList(), run))

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun sequences(): List<Array<Any>> = SEQUENCES

        // The steps run in the test, not here: a framework type needs Robolectric's sandbox.
        private val SEQUENCES =
            listOf(
                sequence("a track found playing at start", Row(SUNDOWN, 1, 0, null)) { spotify() },
                sequence("a track found paused is played once it plays", Row(SUNDOWN, 1, 5_000 + S, null)) {
                    val spotify = spotify(playbackState = paused())
                    at(5_000)
                    spotify.report(state())
                },
                sequence(
                    "the next track follows",
                    Row(SUNDOWN, 1, 0, 60_000 + S),
                    Row(CAREFREE, 1, 60_000 + S, null),
                ) {
                    val spotify = spotify()
                    at(60_000)
                    spotify.report(metadata(title = CAREFREE))
                },
                sequence(
                    "a paused track, then the next track's state before its metadata",
                    Row(CAREFREE, 1, 10_100 + S, null, player = YOUTUBE_MUSIC),
                ) {
                    val youtube =
                        begin(
                            FakeSession(
                                YOUTUBE_MUSIC,
                                playbackState = state(PlaybackState.STATE_PAUSED, speed = 0f, queueItem = 4),
                            ),
                        )
                    at(10_000)
                    youtube.send(state(queueItem = 5))
                    at(10_100)
                    youtube.report(metadata(title = CAREFREE))
                },
                sequence("a short pause", Row(SUNDOWN, 1, 0, null)) {
                    val spotify = spotify()
                    at(10_000)
                    spotify.report(paused())
                    at(70_000)
                    spotify.report(state())
                },
                sequence(
                    "a long pause",
                    Row(SUNDOWN, 1, 0, 10_000 + S),
                    Row(SUNDOWN, 2, 10_000 + GAP + 1_000 + S, null),
                ) {
                    val spotify = spotify()
                    at(10_000)
                    spotify.report(paused())
                    at(10_000 + GAP + 1_000)
                    spotify.report(state())
                },
                sequence("the session layer stopped and started within the gap", Row(SUNDOWN, 1, 0, null)) {
                    spotify()
                    at(30_000)
                    stopLayer()
                    at(31_000)
                    startLayer()
                },
                sequence(
                    "the session layer stopped and started across the gap",
                    Row(SUNDOWN, 1, 0, 30_000),
                    Row(SUNDOWN, 2, 30_000 + GAP + 1, null),
                ) {
                    spotify()
                    at(30_000)
                    stopLayer()
                    at(30_000 + GAP + 1)
                    startLayer()
                },
                sequence("artists sent after the title", Row(SUNDOWN, 1, 0, null)) {
                    val spotify = spotify(metadata = metadata(artist = null))
                    at(1_000)
                    spotify.report(metadata())
                },
                sequence(
                    "a new title under one media id, with a length, past the gap",
                    Row(SUNDOWN, 1, 0, FORTY_MINUTES + S),
                    Row(CAREFREE, 1, FORTY_MINUTES + S, null),
                ) {
                    val spotify = spotify(metadata = metadata(mediaId = "stream:1", durationMs = 225_000))
                    at(FORTY_MINUTES)
                    spotify.report(metadata(title = CAREFREE, mediaId = "stream:1", durationMs = 200_000))
                },
                sequence(
                    "a new title under one media id, with no length, past the gap",
                    Row(SUNDOWN, 1, 0, FORTY_MINUTES + S),
                    Row(CAREFREE, 1, FORTY_MINUTES + S, null),
                ) {
                    val spotify = spotify(metadata = metadata(mediaId = "stream:1"))
                    at(FORTY_MINUTES)
                    spotify.report(metadata(title = CAREFREE, mediaId = "stream:1"))
                },
                sequence(
                    "a stream with no length plays on past the gap and gets an album",
                    Row(SUNDOWN, 1, 0, null, album = "Live"),
                ) {
                    val spotify = spotify(metadata = metadata(mediaId = "stream:1"))
                    at(FORTY_MINUTES)
                    spotify.report(metadata(mediaId = "stream:1", album = "Live"))
                },
                sequence(
                    "a stream with no length, paused and resumed within the gap, gets an album",
                    Row(SUNDOWN, 1, 0, null, album = "Live"),
                ) {
                    // Its row is heard until its start (no length), so the album must continue the
                    // resumed row rather than start one, which would be past the gap.
                    val spotify = spotify(metadata = metadata(mediaId = "stream:1"))
                    at(20 * MINUTE)
                    spotify.report(paused())
                    at(45 * MINUTE)
                    spotify.report(state())
                    at(50 * MINUTE)
                    spotify.report(metadata(mediaId = "stream:1", album = "Live"))
                },
                sequence(
                    "a song queued twice, played back to back, is one play",
                    Row(SUNDOWN, 1, 0, null, player = YOUTUBE_MUSIC),
                ) {
                    val youtube = begin(FakeSession(YOUTUBE_MUSIC, playbackState = state(queueItem = 4)))
                    at(225_000)
                    youtube.report(state(queueItem = 5))
                },
                sequence(
                    "a song queued twice, with another track between, is two plays",
                    Row(SUNDOWN, 1, 0, 60_000 + S, player = YOUTUBE_MUSIC),
                    Row(CAREFREE, 1, 60_000 + S, 120_000 + S, player = YOUTUBE_MUSIC),
                    Row(SUNDOWN, 1, 120_000 + S, null, player = YOUTUBE_MUSIC),
                ) {
                    val youtube = begin(FakeSession(YOUTUBE_MUSIC, playbackState = state(queueItem = 4)))
                    at(60_000)
                    youtube.send(state(queueItem = 5))
                    youtube.report(metadata(title = CAREFREE))
                    at(120_000)
                    youtube.send(state(queueItem = 6))
                    youtube.report(metadata())
                },
                sequence(
                    "a live recording after the studio one, both on one title, is two plays",
                    Row(SUNDOWN, 1, 0, 225_000 + S, album = "Sundown", player = YOUTUBE_MUSIC),
                    Row(SUNDOWN, 1, 225_000 + S, null, album = "Live", player = YOUTUBE_MUSIC),
                ) {
                    val studio = metadata(album = "Sundown")
                    val youtube = begin(FakeSession(YOUTUBE_MUSIC, studio, state(queueItem = 4)))
                    at(225_000)
                    youtube.send(state(queueItem = 5))
                    youtube.report(metadata(album = "Live"))
                },
                sequence(
                    // The first row keeps the player's last report of it, which named no artists.
                    "a title with no artists between two artists is two plays",
                    Row(SUNDOWN, 1, 0, 2_000 + S, artists = ""),
                    Row(SUNDOWN, 1, 2_000 + S, null, artists = "Someone Else"),
                ) {
                    val spotify = spotify()
                    at(1_000)
                    spotify.report(metadata(artist = null))
                    at(2_000)
                    spotify.report(metadata(artist = "Someone Else"))
                },
                sequence("a track plays on past its length and the gap and gets new art", Row(SUNDOWN, 1, 0, null)) {
                    val spotify = spotify()
                    at(FORTY_MINUTES)
                    spotify.report(metadata(durationMs = 225_000, artUri = "content://art/2"))
                },
                sequence(
                    "a switch to another player with the same track, and back",
                    Row(SUNDOWN, 1, 0, 60_000 + S),
                    Row(SUNDOWN, 1, 60_000 + S, 120_000 + S, player = YOUTUBE_MUSIC),
                    Row(SUNDOWN, 1, 120_000 + S, null),
                ) {
                    val same = metadata(mediaId = "same:1", durationMs = 225_000)
                    val spotify = FakeSession(SPOTIFY, metadata = same)
                    val youtube = FakeSession(YOUTUBE_MUSIC, metadata = same, playbackState = paused())
                    beginAll(spotify, youtube)
                    at(60_000)
                    youtube.send(state())
                    spotify.send(paused())
                    settleAll()
                    at(120_000)
                    spotify.send(state())
                    youtube.send(paused())
                    settleAll()
                },
                sequence(
                    "a process start finds the track of a stored row that was never stopped",
                    Row(SUNDOWN, 1, -60_000, null),
                ) {
                    stored(
                        NowPlaying(SUNDOWN, "Gordon Lightfoot", duration = 225.seconds, player = SPOTIFY),
                        base - 60.seconds,
                    )
                    spotify()
                },
                sequence(
                    "a change that settles while buffering, then plays",
                    Row(SUNDOWN, 1, 0, 60_000 + S),
                    Row(CAREFREE, 1, 61_500 + S, null),
                ) {
                    val spotify = spotify()
                    at(60_000)
                    spotify.send(state(PlaybackState.STATE_BUFFERING))
                    spotify.send(metadata(title = CAREFREE))
                    settleAll()
                    at(61_500)
                    spotify.report(state())
                },
            )
    }
}
