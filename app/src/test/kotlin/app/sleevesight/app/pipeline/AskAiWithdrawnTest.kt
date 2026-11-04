package app.sleevesight.app.pipeline

import app.sleevesight.app.pipeline.AskAi.Companion.empty
import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * *Ask AI automatically* for a track the session layer took back (#880: an ad told late, published
 * as a track for a moment): a search under way for it is cancelled before its answer, and none asks
 * its call after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskAiWithdrawnTest {
    private val provider = CountingProvider()
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true))

    @Test
    fun `a search under way for a track taken back does not finish`() =
        runTest {
            provider.streaming = 10.seconds
            val app = AskAi(this, settings, CardCache(), provider)
            app.panels.follow(empty(), settings.value)
            runCurrent()
            val asked = provider.requests
            assertTrue("its calls under way", asked > 0)
            app.searches.withdraw(HomeFixtures.sundown)
            advanceUntilIdle()
            assertEquals("cut off", 0, provider.completed)
            assertEquals("nothing more asked", asked, provider.requests)
        }

    @Test
    fun `a search for a track taken back asks nothing`() =
        runTest {
            val app = AskAi(this, settings, CardCache(), provider)
            app.searches.withdraw(HomeFixtures.sundown)
            app.panels.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }
}
