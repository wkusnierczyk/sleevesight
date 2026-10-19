package app.sleevesight.app.session

import android.media.MediaMetadata
import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/**
 * A session attached in the middle of a track change, at each point of it (`design/architecture.md`,
 * *Settling*): the item its state names then is withheld until the reports resolve it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class SessionAttachTest : SessionRepositoryFixture() {
    /** Moves virtual time on to [ms], running what is due before it. */
    private fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    /**
     * A session attached at some point of a track change from "Sundown" (item 4) to "Carefree
     * Highway" (item 5): what it holds when attached (no metadata yet when [title] is null), the
     * reports after it (each so many ms after the attach), and what the repository should make of
     * them: how many changes, and the track then shown, with its item.
     */
    private data class AttachRow(
        val name: String,
        val title: String?,
        val item: Long,
        val reports: List<Pair<Long, Any>>,
        val changes: Int,
        val track: Pair<String, Long?>,
    )

    /** How long an item seen at the attach may wait for what resolves it, in ms. */
    private val window = SessionRepository.LONGEST_BURST.inWholeMilliseconds

    private val attachRows =
        listOf(
            AttachRow(
                "before the change: the state, then the metadata",
                title = "Sundown",
                item = 4,
                reports = listOf(window + 100 to state(queueItem = 5), window + 150 to carefree),
                changes = 1,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "before the change: the metadata, then the state",
                title = "Sundown",
                item = 4,
                reports = listOf(window + 100 to carefree, window + 150 to state(queueItem = 5)),
                changes = 1,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "between the new state and its metadata",
                title = "Sundown",
                item = 5,
                reports = listOf(150L to carefree),
                changes = 1,
                // Item 5 is taken for Sundown's, as when the metadata comes well after the state.
                track = "Carefree Highway" to null,
            ),
            AttachRow(
                "between the new metadata and its state, the state soon after",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(150L to state(queueItem = 5)),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "between the new metadata and its state, the state over the settle window after",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(800L to state(queueItem = 5)),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                // The cost of the window: taken for a second copy of the song.
                "between the new metadata and its state, the state past the window",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(window + 500 to state(queueItem = 5)),
                changes = 1,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "after the change",
                title = "Carefree Highway",
                item = 5,
                reports = emptyList(),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "no metadata yet, then the metadata soon after, and a second copy minutes later",
                title = null,
                item = 5,
                reports = listOf(150L to carefree, 300_000L to state(queueItem = 6)),
                changes = 1,
                track = "Carefree Highway" to 6,
            ),
            AttachRow(
                "no metadata yet, then the metadata later",
                title = null,
                item = 5,
                reports = listOf(800L to carefree),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "a song queued twice, its second copy minutes later",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(300_000L to state(queueItem = 5)),
                changes = 1,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                // The cost of the window: taken for the new track's state, filling in its item.
                "a song queued twice, its second copy soon after the attach",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(1_000L to state(queueItem = 5)),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
        )

    @Test
    fun aSessionAttachedAnywhereInATrackChangeSeesItOnceOrNotAtAll() {
        followed.value = setOf(YOUTUBE_MUSIC)
        val wrong = mutableListOf<String>()
        for (row in attachRows) {
            // Each row on a fresh start: stopping drops the session and the track shown.
            repository.stop()
            val start = scheduler.currentTime
            val session =
                FakeSession(YOUTUBE_MUSIC, row.title?.let { metadata(title = it) }, state(queueItem = row.item))
            system.active = listOf(session)
            repository.start()
            val tracks = recordTracks()
            for ((after, report) in row.reports) {
                at(start + after)
                when (report) {
                    is PlaybackState -> session.send(report)
                    is MediaMetadata -> session.send(report)
                }
            }
            settle()

            val shown = repository.nowPlaying.value
            val seen = changes(tracks) to (shown?.title to shown?.queueItemId)
            if (seen != row.changes to row.track) wrong += "${row.name}: $seen"
        }
        assertEquals(emptyList<String>(), wrong)
    }
}
