package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.sources.Resolution
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * *Look again* on the card paid for (#617), through the host and a real pipeline: the sources asked
 * afresh past the retrieval cache, no call to the AI service when they find nothing new (the same
 * lines in another order or spacing, a source that failed in whole or in part, or was left
 * provisional, MusicBrainz unreachable on a track served from the cache), one when they do, written
 * with the earlier lines of a source that failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostLookAgainTest : PipelineHostFixture() {
    // Sundown written on a tap, its sources' results kept in the cache.
    private fun TestScope.written() {
        cache.keepsResults = true
        started()
        play("Sundown")
        settle()
        tap()
        settle()
        assertEquals(1, provider.requests)
        assertEquals(1, musicBrainz.asked.size)
    }

    /** A tap on *Look again* as the screen sends it: under the key it is offered for, beside the track shown. */
    private fun lookAgain() {
        host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
    }

    @Test
    fun `the sources are asked afresh, and finding nothing new, nothing is written`() =
        runTest {
            written()
            lookAgain()
            settle()

            assertEquals(2, musicBrainz.asked.size)
            assertEquals(1, provider.requests)
            assertTrue(host.state.value.nothingNew)
            assertTrue(
                host.state.value.track
                    ?.insight is InsightState.Written,
            )
        }

    // A look again, which must find nothing new: no call, and the view says so.
    private fun TestScope.nothingNew() {
        lookAgain()
        settle()
        assertEquals(2, musicBrainz.asked.size)
        assertEquals(1, provider.requestsFor("Sundown"))
        assertTrue(host.state.value.nothingNew)
    }

    @Test
    fun `the same lines in another order and spacing are nothing new`() =
        runTest {
            musicBrainz.lines = listOf("Sundown was recorded in 1973.", "It topped the charts.")
            written()
            musicBrainz.lines = listOf("It topped  the charts. ", "Sundown was\nrecorded in 1973.")
            nothingNew()
        }

    @Test
    fun `a source that answers in part is nothing new`() =
        runTest {
            musicBrainz.lines = listOf("Sundown was recorded in 1973.", "Gordon Lightfoot is Canadian.")
            written()
            // The artist's part did not answer: the song's line alone.
            musicBrainz.lines = listOf("Sundown was recorded in 1973.")
            nothingNew()
        }

    @Test
    fun `a provisional result is nothing new`() =
        runTest {
            written()
            musicBrainz.provisional = true
            nothingNew()
        }

    @Test
    fun `MusicBrainz unreachable on a track served from the cache is nothing new`() =
        runTest {
            written()
            play("Carefree Highway")
            settle()
            play("Sundown")
            settle()
            assertEquals(2, musicBrainz.asked.size)
            musicBrainz.unreachable = true
            resolutions += Resolution(Outcome.Unreachable)
            lookAgain()
            settle()

            assertEquals(3, musicBrainz.asked.size)
            assertEquals(1, provider.requestsFor("Sundown"))
            assertTrue(host.state.value.nothingNew)
        }

    @Test
    fun `one new line is written from, with the earlier lines of a source that failed`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            written()
            musicBrainz.unreachable = true
            lastFm.text = "Sundown was a number one single."
            lookAgain()
            settle()

            assertEquals(2, provider.requestsFor("Sundown"))
            val prompt = provider.prompts.last()
            assertTrue("Sundown was a number one single." in prompt)
            assertTrue("A line from ${SourceId.MUSICBRAINZ.tag}." in prompt)
        }

    @Test
    fun `finding something new, the insight is written from it, in one call`() =
        runTest {
            written()
            musicBrainz.text = "Sundown was a number one single."
            lookAgain()
            settle()

            assertEquals(2, musicBrainz.asked.size)
            assertEquals(2, provider.requests)
            assertTrue("Sundown was a number one single." in provider.prompts.last())
            assertFalse(host.state.value.nothingNew)
            // Nothing more, however long it plays.
            settle()
            assertEquals(2, provider.requests)
        }

    @Test
    fun `while the sources are looked up, the card stays and the view says it looks again`() =
        runTest {
            written()
            musicBrainz.byName = 2.seconds
            lookAgain()
            runCurrent()

            assertTrue(host.state.value.lookingAgain)
            assertTrue(host.state.value.cardShown)
            assertEquals(null, host.state.value.lookAgainKey)
            settle()
            assertFalse(host.state.value.lookingAgain)
        }
}
