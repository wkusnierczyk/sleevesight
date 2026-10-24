package app.sleevesight.app.session

import android.media.session.PlaybackState
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A followed session that is, or becomes, a call (#435), through every way the session layer
 * learns of a session ([Entry]) and at each point of its reports' life ([Timing]): nothing of the
 * call is published; another followed player that plays stays shown; and music reported once the
 * call is over shows as usual. The session's getters are not read once it is marked
 * (`FakeSession.reads`); that tells something only for [Entry.INITIAL], since the other entries
 * hand their data to a callback as its arguments, which the published state checks instead.
 *
 * The mark is flipped silently, as Android 16 does for a change of usage within a local session,
 * except for [Entry.AUDIO_INFO], which flips it through the audio-info callback.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
internal class SessionCallGateTest(
    private val entry: Entry,
    private val timing: Timing,
) : SessionRepositoryFixture() {
    /** How the call's data reaches the session layer. */
    enum class Entry { INITIAL, METADATA, QUEUE, QUEUE_TITLE, PLAYBACK_STATE, AUDIO_INFO }

    /**
     * When the session is marked a call: [BEFORE] its data is reported; [DURING_SETTLE], after it
     * is reported within a settle window (a track change's) and before that settles; [AFTER] it
     * has settled (shown, since nothing marked it yet), and before its next report.
     */
    enum class Timing { BEFORE, DURING_SETTLE, AFTER }

    private val callMetadata = metadata(title = CALLER, artist = "Voice call")
    private val callState = state(PlaybackState.STATE_PLAYING, positionMs = CALL_POSITION_MS)
    private val callQueue = listOf(queueItem(1, CALLER))

    private val app =
        if (entry == Entry.INITIAL) {
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = callMetadata,
                playbackState = callState,
                queue = callQueue,
                queueTitle = CALL_QUEUE,
            )
        } else {
            FakeSession(YOUTUBE_MUSIC, metadata = metadata(title = "Song"))
        }
    private val spotify = FakeSession(SPOTIFY)

    /** [app]'s reads when it was marked a call; none may follow. */
    private var readsAtCall = -1

    private fun markCall() {
        if (entry == Entry.AUDIO_INFO) app.call(true) else app.marksCall = true
        readsAtCall = app.reads
    }

    /** Reports the call's data through [entry]. */
    private fun report() {
        when (entry) {
            Entry.INITIAL -> app.send(callState)
            Entry.METADATA, Entry.AUDIO_INFO -> app.send(callMetadata)
            Entry.QUEUE -> app.sendQueueItems(callQueue)
            Entry.QUEUE_TITLE -> app.sendQueueTitle(CALL_QUEUE)
            Entry.PLAYBACK_STATE -> app.send(callState)
        }
    }

    @Test
    fun nothingOfTheCallIsReadOrShown() {
        followed.value = setOf(YOUTUBE_MUSIC, SPOTIFY)
        system.active = listOf(app, spotify)
        val tracks = recordTracks()
        val states = mutableListOf<PlaybackInfo>()
        scope.launch { repository.playback.collect { states += it } }
        val queues = mutableListOf<SessionQueue?>()
        scope.launch { repository.queue.collect { queues += it } }

        runTheCall()
        settle()

        val case = "$entry, $timing"
        assertEquals(case, readsAtCall, app.reads)
        assertEquals(case, sundown, repository.nowPlaying.value)
        assertEquals(case, SPOTIFY, repository.playback.value.player)
        assertEquals(case, null, repository.queue.value)
        if (timing != Timing.AFTER) assertTrue(case, tracks.none { it?.title == CALLER })
        if (timing == Timing.BEFORE) {
            assertTrue(case, states.none { it.player == YOUTUBE_MUSIC && (it.position ?: Duration.ZERO) >= CALL_MS })
            assertTrue(case, queues.none { it?.title == CALL_QUEUE })
        }

        // The call is over: a settle window after it is first handled so, the session is read
        // afresh, and music shows as usual.
        app.marksCall = false
        system.change(listOf(app, spotify))
        app.send(metadata(title = "Fresh"))
        waitOutTheCall()
        app.settled(state(PlaybackState.STATE_PLAYING, positionMs = 1_000))
        assertEquals(case, "Fresh", repository.nowPlaying.value?.title)
    }

    /** Starts the session layer, and marks the call and reports its data at [timing]. */
    private fun runTheCall() {
        when (timing) {
            Timing.BEFORE -> {
                if (entry == Entry.INITIAL) markCall()
                repository.start()
                settle()
                if (entry != Entry.INITIAL) {
                    markCall()
                    report()
                }
            }
            Timing.DURING_SETTLE -> {
                if (entry == Entry.INITIAL) {
                    // Marked as the callback registers, before the first reads.
                    app.onRegister = { if (readsAtCall < 0) markCall() }
                    repository.start()
                } else {
                    repository.start()
                    settle()
                    // A track change opens the settle window; the call's report comes within it.
                    app.send(metadata(title = "Next"))
                    report()
                    markCall()
                }
            }
            Timing.AFTER -> {
                repository.start()
                settle()
                if (entry != Entry.INITIAL) report()
                settle()
                markCall()
                report()
            }
        }
    }

    companion object {
        private const val CALLER = "Alice"
        private const val CALL_QUEUE = "Calls"
        private const val CALL_POSITION_MS = 7_777L
        private val CALL_MS = CALL_POSITION_MS.milliseconds

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, {1}")
        fun cases(): List<Array<Any>> = Entry.entries.flatMap { e -> Timing.entries.map { t -> arrayOf<Any>(e, t) } }
    }
}
