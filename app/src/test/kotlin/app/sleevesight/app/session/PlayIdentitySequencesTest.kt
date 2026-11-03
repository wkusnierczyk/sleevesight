package app.sleevesight.app.session

import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

/**
 * The identity model (#297) through the real session layer, as a table: a sequence of YouTube Music
 * reports in virtual time (patterns from a capture on a phone; made-up titles), the number of track
 * changes it publishes, and when the last one began. The session starts on "Sundown", queue item 4,
 * 200 s in.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
internal class PlayIdentitySequencesTest(
    private val name: String,
    private val case: Case,
) : SessionRepositoryFixture() {
    class Case(
        val changes: Int,
        val lastChangedAt: Long?,
        val steps: PlayIdentitySequencesTest.() -> Unit,
        val attachedOn: Long = 4,
    )

    @Test
    fun publishesTheChanges() {
        val youtube = youtubeMusic(item = case.attachedOn)
        youtube.send(state(positionMs = 200_000, updatedAt = 0, queueItem = case.attachedOn))
        settle()
        session = youtube
        val tracks = recordTracks()
        case.steps(this)
        settle()

        assertEquals(name, case.changes, changes(tracks))
        assertEquals(name, case.lastChangedAt, repository.lastChange.value?.changedAt)
    }

    private lateinit var session: FakeSession

    fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    fun title(
        ms: Long,
        title: String? = "Sundown",
        artist: String? = "Gordon Lightfoot",
        album: String? = null,
    ) {
        at(ms)
        session.send(metadata(title = title, artist = artist, album = album))
    }

    fun slot(
        ms: Long,
        item: Long,
        positionMs: Long = 0,
        status: Int = PlaybackState.STATE_PLAYING,
    ) {
        at(ms)
        session.send(state(status, positionMs = positionMs, updatedAt = ms, queueItem = item))
    }

    companion object {
        private const val NEXT = "Carefree Highway"

        private fun case(
            name: String,
            changes: Int,
            lastChangedAt: Long?,
            attachedOn: Long = 4,
            steps: PlayIdentitySequencesTest.() -> Unit,
        ): Array<Any> = arrayOf(name, Case(changes, lastChangedAt, steps, attachedOn))

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            listOf(
                case("edge 1: attached between the new slot and its title", 1, 1_000, attachedOn = 5) {
                    title(1_000, NEXT)
                },
                case("the slot leads the title within a settle: timed from the slot", 1, 1_000) {
                    slot(1_000, 5)
                    title(1_150, NEXT)
                },
                case("edge 5: the slot leads the title by 600 ms: timed from the slot", 1, 1_000) {
                    slot(1_000, 5)
                    title(1_600, NEXT)
                },
                case("the slot leads the title by more than a second: timed at the title", 1, 2_500) {
                    slot(1_000, 5)
                    title(2_500, NEXT)
                },
                case("edge 5: a slot lagging a title-first change does not time the next", 2, 1_800) {
                    title(1_000, NEXT)
                    slot(1_400, 5)
                    title(1_800, "Rainy Day People")
                },
                case("a slot moving under the same title is one play (repeat from another slot)", 0, null) {
                    slot(1_000, 5)
                },
                case("repeat-one: back to the start on the same slot is one play", 0, null) {
                    slot(1_000, 4, positionMs = 200_000, status = PlaybackState.STATE_PAUSED)
                    slot(1_050, 4)
                },
                case("a placeholder album, then the real one within the first second: one change", 1, 1_000) {
                    title(1_000, NEXT, album = "Gordon Lightfoot")
                    title(1_400, NEXT, album = "Real Album")
                },
                case("edge 2: another album under the same title after the first second: a change", 1, 5_000) {
                    title(1_000, album = "Studio")
                    title(5_000, album = "Live")
                },
                // Timed from the stop at 0 just before the title, the existing rule for a state that falls back.
                case("a search play: title alone, then artists and album, then a fresh slot: one change", 1, 900) {
                    slot(900, -1, status = PlaybackState.STATE_STOPPED)
                    title(1_000, NEXT, artist = null)
                    title(1_150, NEXT, album = "Real Album")
                    slot(1_190, 31)
                },
                case("the same track again from search, right after itself: one play", 0, null) {
                    slot(1_000, -1, status = PlaybackState.STATE_STOPPED)
                    title(1_100)
                    slot(1_140, 62)
                },
                case("edge 4: X, then the title without artists, then Y: a change", 1, 3_000) {
                    title(1_000, artist = null)
                    title(3_000, artist = "Someone Else")
                },
            )
    }
}
