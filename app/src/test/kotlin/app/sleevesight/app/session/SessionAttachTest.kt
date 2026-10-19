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
 * *Settling*): the queue item its state names then may be the track before's, and decides nothing.
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

    private val attachRows =
        listOf(
            AttachRow(
                "before the change: the state, then the metadata",
                title = "Sundown",
                item = 4,
                reports = listOf(5_000L to state(queueItem = 5), 5_050L to carefree),
                changes = 1,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "before the change: the metadata, then the state",
                title = "Sundown",
                item = 4,
                reports = listOf(5_000L to carefree, 5_050L to state(queueItem = 5)),
                changes = 1,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "between the new state and its metadata",
                title = "Sundown",
                item = 5,
                reports = listOf(150L to carefree),
                changes = 1,
                track = "Carefree Highway" to 5,
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
                "between the new metadata and its state, the state 800 ms after",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(800L to state(queueItem = 5)),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "between the new metadata and its state, the state 5 s after",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(5_000L to state(queueItem = 5)),
                changes = 0,
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
                "no metadata yet, then the metadata soon after",
                title = null,
                item = 5,
                reports = listOf(150L to carefree),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "no metadata yet, then the metadata 800 ms after",
                title = null,
                item = 5,
                reports = listOf(800L to carefree),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                // Played back to back: one play, its item moved on.
                "a song queued twice, its second copy minutes later",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(300_000L to state(queueItem = 5)),
                changes = 0,
                track = "Carefree Highway" to 5,
            ),
            AttachRow(
                "a title that changes and changes back",
                title = "Carefree Highway",
                item = 4,
                reports = listOf(500L to metadata(), 1_000L to carefree),
                changes = 2,
                track = "Carefree Highway" to 4,
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
