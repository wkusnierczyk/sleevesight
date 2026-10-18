package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back online (#192), through the host: the phone's connection reaches the coordinator, the run
 * that asks again carries what the run before gathered, and the view says the phone is offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostBackOnlineTest : PipelineHostFixture() {
    @Test
    fun `back online, the playing track's unreachable source is asked again, and the others kept`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            val events = mutableListOf<PipelineEvent>()
            backgroundScope.launch { host.events.toList(events) }
            started()
            online.value = false
            lastFm.unreachable = true
            play("A")
            settle()
            assertTrue(host.state.value.offline)
            assertEquals(1, lastFm.asked.size)
            assertEquals(Outcome.Unreachable, lastFmState().result.outcome)

            lastFm.unreachable = false
            online.value = true
            runCurrent()
            assertFalse(host.state.value.offline)
            settle()

            assertEquals(1, musicBrainz.asked.size)
            assertEquals(2, lastFm.asked.size)
            assertEquals(1, resolved.size)
            assertEquals(Outcome.SongArticle, lastFmState().result.outcome)
            assertEquals(listOf(SourceId.LASTFM), events.filterIsInstance<PipelineEvent.AskedAgain>().single().sources)
            // No action yet since the app opened: Write is offered, nothing written unasked.
            assertEquals(0, provider.requests)
            assertTrue(host.state.value.writeKey != null)
        }

    private fun lastFmState(): SourceState.Done =
        host.state.value.track
            ?.sources
            ?.get(SourceId.LASTFM) as SourceState.Done

    @Test
    fun `back online with nothing unreachable starts nothing`() =
        runTest {
            started()
            play("A")
            settle()
            online.value = false
            runCurrent()
            online.value = true
            settle()
            assertEquals(1, musicBrainz.asked.size)
            assertEquals(1, resolved.size)
        }
}
