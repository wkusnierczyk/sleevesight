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
 * *Reactive* changed while a track's sources are still being looked up (#553: the insight card's
 * toggle): turned to *Manual*, the run, whose settings were read when it started, writes nothing
 * without a tap; turned on, a run started under *Manual* still writes nothing for its track. And
 * the view's `paused` follows *Saver*'s pause: on past its count, off after a tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostAutomaticWritingTest : PipelineHostFixture() {
    @Test
    fun `turned off while the sources are looked up, the track is not written`() =
        runTest {
            acted()
            musicBrainz.byName = 3.seconds
            play("Two")
            // The run has started, its lookups under way.
            advanceTimeBy(DEBOUNCE + 1.seconds)
            settings.value = settings.value.withAutomaticWriting(false)
            settle()
            assertEquals(0, provider.requestsFor("Two"))
            val insight =
                host.state.value.track
                    ?.insight
            assertEquals(InsightState.Idle(canWrite = true), insight)
        }

    @Test
    fun `turned on while the sources are looked up, the track is not written`() =
        runTest {
            settings.value = settings.value.withWriteAutomatically(WriteAutomatically.NEVER)
            acted()
            musicBrainz.byName = 3.seconds
            play("Two")
            advanceTimeBy(DEBOUNCE + 1.seconds)
            settings.value = settings.value.withAutomaticWriting(true)
            settle()
            assertEquals(0, provider.requestsFor("Two"))
            // The next track is written: the toggle is on again.
            play("Three")
            settle()
            assertEquals(1, provider.requestsFor("Three"))
        }

    @Test
    fun `the view is paused past Saver's count and not after a tap`() =
        runTest {
            settings.value = settings.value.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true)
            acted()
            host.activityStopped(changingConfigurations = false)
            runCurrent()
            play("Two")
            settle()
            assertFalse("one change away is written as usual", host.state.value.paused)
            play("Three")
            settle()
            assertTrue("past the count", host.state.value.paused)
            assertTrue("Write offered because of the pause", host.state.value.lapsed)
            // Turned off: still paused for the toggle, but the card no longer offers to resume.
            settings.value = settings.value.withAutomaticWriting(false)
            settle()
            assertTrue(host.state.value.paused)
            assertFalse("nothing to resume under Manual", host.state.value.lapsed)
            settings.value = settings.value.withAutomaticWriting(true)
            settle()
            host.activityStarted()
            settle()
            assertTrue("an activity start does not undo the pause", host.state.value.paused)
            tap()
            settle()
            assertFalse("a tap resumes", host.state.value.paused)
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
