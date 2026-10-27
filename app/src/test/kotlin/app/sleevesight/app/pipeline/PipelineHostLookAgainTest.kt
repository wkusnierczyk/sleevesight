package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.InsightState
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
 * afresh past the retrieval cache, no call to the AI service when they find nothing new, one when
 * they do.
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
