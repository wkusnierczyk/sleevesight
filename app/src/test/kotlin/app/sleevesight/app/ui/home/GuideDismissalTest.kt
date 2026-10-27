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

/** The home guide's *Dismiss once* (#627): until a track plays, held in memory only. */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideDismissalTest {
    @Test
    fun aNewProcessShowsTheGuide() {
        assertFalse(GuideDismissal().dismissed.value)
    }

    @Test
    fun dismissedUntilATrackPlays() =
        runTest(UnconfinedTestDispatcher()) {
            val playing = MutableStateFlow<NowPlaying?>(null)
            val dismissal = GuideDismissal()
            backgroundScope.launch { dismissal.clearOnPlay(playing) }
            dismissal.dismiss()
            assertTrue(dismissal.dismissed.value)
            playing.value = null
            assertTrue(dismissal.dismissed.value)
            playing.value = sundown
            assertFalse(dismissal.dismissed.value)
        }
}
