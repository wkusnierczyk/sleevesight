package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.InsightState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * *Clear history* in the host (#547): the insight shown, the track's state and the ledger go at
 * once, work under way when it comes keeps and shows nothing after it, and the track playing is
 * not looked up again (no paid call on its own) until another one plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostClearTest : PipelineHostFixture() {
    // The clear as the app makes it: the boundary first, then the host's part.
    private fun TestScope.clear() {
        backgroundScope.launch {
            epoch.advance()
            host.forget()
        }
        runCurrent()
    }

    @Test
    fun `a clear empties what is shown, and the track playing is looked up again only after another`() =
        runTest {
            started()
            play("One")
            settle()
            tap()
            settle()
            val insight =
                host.state.value.track
                    ?.insight
            assertTrue(insight is InsightState.Written)

            clear()
            val view = host.state.value
            assertNull(view.track)
            assertFalse(view.cardShown)
            assertNull(view.writeKey)

            play("One", duration = 200.seconds)
            settle()
            assertEquals(listOf("artist|one"), musicBrainz.asked)
            assertNull(host.state.value.track)

            play("Two")
            settle()
            assertEquals(listOf("artist|one", "artist|two"), musicBrainz.asked)
            val shown = host.state.value.track
            assertEquals("Two", shown?.nowPlaying?.title)
            assertEquals("One was written once, before the clear", 1, provider.requestsFor("One"))
        }

    @Test
    fun `a model reply held when the clear comes is neither kept nor shown`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            play("One")
            settle()
            tap()
            advanceTimeBy(1.seconds)
            assertEquals(1, provider.requests)

            clear()
            advanceTimeBy(30.seconds)

            assertEquals(0, provider.completed)
            assertEquals(0, cache.held)
            assertNull(host.state.value.track)
        }

    @Test
    fun `a lookup held when the clear comes keeps and shows nothing`() =
        runTest {
            cache.keepsResults = true
            musicBrainz.byName = 3.seconds
            started()
            play("One")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            assertEquals(listOf("artist|one"), musicBrainz.asked)

            clear()
            settle()

            assertEquals(0, cache.held)
            assertNull(host.state.value.track)
        }
}
