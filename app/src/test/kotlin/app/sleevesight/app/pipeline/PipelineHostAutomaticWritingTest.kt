package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * *Reactive* changed while a track's sources are still being looked up (#553; since #615 from the
 * insight card's menu): turned to *On demand*, the run, whose settings were read when it started, makes
 * no paid call without a tap; turned to an automatic mode, a run started under *On demand* still writes
 * nothing for its track. Past *Saver*'s count, Write is offered because of the pause, and opening
 * the app resumes automatic writing, though not for the track that came to play while it was
 * paused (#625). An automatic mode chosen is the first action; *On demand* chosen is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostAutomaticWritingTest : PipelineHostFixture() {
    @Test
    fun `On demand chosen while the sources are looked up, the track is not written`() =
        runTest {
            acted()
            musicBrainz.byName = 3.seconds
            play("Two")
            // The run has started, its lookups under way.
            advanceTimeBy(DEBOUNCE + 1.seconds)
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            settle()
            assertEquals(0, provider.requestsFor("Two"))
            val insight =
                host.state.value.track
                    ?.insight
            assertEquals(InsightState.Idle(canWrite = true), insight)
        }

    @Test
    fun `an automatic mode chosen while the sources are looked up, the track is not written`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            acted()
            musicBrainz.byName = 3.seconds
            play("Two")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            settle()
            assertEquals(0, provider.requestsFor("Two"))
            // The next track is written: the mode writes automatically again.
            play("Three")
            settle()
            assertEquals(1, provider.requestsFor("Three"))
        }

    @Test
    fun `past Saver's count Write is offered for the pause, and opening the app resumes automatic writing`() =
        runTest {
            settings.value = settings.value.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true)
            acted()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            play("Two")
            settle()
            assertEquals("one change away is written as usual", 1, provider.requestsFor("Two"))
            assertFalse(host.state.value.lapsed)
            play("Three")
            settle()
            assertEquals("past the count", 0, provider.requestsFor("Three"))
            assertTrue("Write offered because of the pause", host.state.value.lapsed)
            // Turned to On demand: nothing would have written, so the pause is not why.
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            settle()
            assertFalse("nothing to resume under On demand", host.state.value.lapsed)
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            settle()
            // Opening the app resumes automatic writing (#625); Three, which came to play while it
            // was paused, still offers Write and says why, and nothing is paid for it.
            host.activityStarted()
            settle()
            assertTrue("Three still says why", host.state.value.lapsed)
            assertEquals(0, provider.requestsFor("Three"))
            play("Four")
            settle()
            assertEquals("resumed: the next track is written", 1, provider.requestsFor("Four"))
        }

    @Test
    fun `an automatic mode chosen is the first action, On demand chosen is not`() =
        runTest {
            started()
            play("One")
            settle()
            assertEquals("no first action yet", 0, provider.requestsFor("One"))
            host.modeChosen(WriteAutomatically.NEVER)
            play("Two")
            settle()
            assertEquals("On demand chosen acts on nothing", 0, provider.requestsFor("Two"))
            // The mode chosen already, chosen again in the menu: automatic writing starts.
            host.modeChosen(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            runCurrent()
            assertEquals("the track decided before stays unwritten", 0, provider.requestsFor("Two"))
            play("Three")
            settle()
            assertEquals(1, provider.requestsFor("Three"))
        }

    // A first action: One written on a tap.
    private fun TestScope.acted() {
        started()
        play("One")
        settle()
        tap()
        settle()
        assertEquals(1, provider.requestsFor("One"))
    }
}
