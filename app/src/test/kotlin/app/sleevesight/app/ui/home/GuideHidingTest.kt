package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home guide's *Hide once* (#627, #759): until Home is left or a track plays, held in memory only. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideHidingTest {
    @Test
    fun aNewProcessShowsTheGuide() {
        assertFalse(GuideHiding().hidden.value)
    }

    @Test
    fun hiddenUntilATrackPlays() =
        runTest(UnconfinedTestDispatcher()) {
            val playing = MutableStateFlow<NowPlaying?>(null)
            val hiding = GuideHiding()
            backgroundScope.launch { hiding.clearOnPlay(playing) }
            hiding.hide()
            assertTrue(hiding.hidden.value)
            playing.value = null
            assertTrue(hiding.hidden.value)
            playing.value = sundown
            assertFalse(hiding.hidden.value)
        }

    @Test
    fun hiddenUntilHomeIsLeft() {
        val hiding = GuideHiding()
        hiding.hide()
        assertTrue(hiding.hidden.value)
        hiding.homeLeft()
        assertFalse(hiding.hidden.value)
    }
}
