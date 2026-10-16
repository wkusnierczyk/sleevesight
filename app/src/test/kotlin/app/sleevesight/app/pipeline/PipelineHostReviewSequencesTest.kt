package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End to end, through the real `Pipeline`: the sequences the third and fourth review rounds of
 * PR #155 found, as `PipelineCoordinatorTest` runs them in its simulated world.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostReviewSequencesTest : PipelineHostFixture() {
    @Test
    fun `consecutive rebuilds while the replay gathers write the track once`() =
        runTest {
            started()
            play("Alpha")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            keyChanges.value++
            advanceTimeBy(500.milliseconds)
            keyChanges.value++
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
        }

    @Test
    fun `a rebuild in a newer track's debounce while the old track streams waits for the call, then goes on`() =
        runTest {
            provider.streaming = 10.seconds
            started()
            play("Alpha")
            settle()
            host.write()
            advanceTimeBy(2.seconds)
            play("Bravo")
            advanceTimeBy(100.milliseconds)
            keyChanges.value++
            advanceTimeBy(60.seconds)
            play("Charlie")
            advanceTimeBy(60.seconds)

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha", "artist|bravo", "artist|charlie"), musicBrainz.asked)
            assertEquals(
                "Charlie",
                host.state.value.track
                    ?.nowPlaying
                    ?.title,
            )
        }

    @Test
    fun `a stale run made current again while hidden starts no model call`() =
        runTest {
            started()
            play("Zero")
            settle()
            host.write()
            settle()
            musicBrainz.byName = 2.seconds
            play("Alpha")
            advanceTimeBy(DEBOUNCE + 1.8.seconds)
            play("Bravo")
            advanceTimeBy(300.milliseconds)
            host.activityStopped(changingConfigurations = false)
            play("Alpha")
            settle()

            assertEquals(0, provider.requestsFor("Alpha"))
            assertEquals(0, provider.requestsFor("Bravo"))
        }

    @Test
    fun `a new length of a written track runs its lookups again, and writes nothing again`() =
        runTest {
            started()
            play("Alpha", 106.seconds)
            settle()
            host.write()
            settle()
            // YouTube Music's update: the same title, artists and player, a new length.
            play("Alpha", 279.seconds)
            settle()

            assertEquals(1, provider.requestsFor("Alpha"))
            assertEquals(listOf("artist|alpha", "artist|alpha"), musicBrainz.asked)
            assertEquals(
                InsightState.Idle(canWrite = true),
                host.state.value.track
                    ?.insight,
            )
        }

    @Test
    fun `requests are not allowed before the first run is acknowledged, an activity started or not`() =
        runTest {
            acknowledged.value = false
            started()
            settings.value = settings.value.copy(lookUpInBackground = true)
            runCurrent()
            assertEquals(false, host.lookupsAllowed.first())

            acknowledged.value = true
            runCurrent()
            assertEquals(true, host.lookupsAllowed.first())
        }
}
