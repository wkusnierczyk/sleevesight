package app.sleevesight.app.pipeline

import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What *Rewrite insight* would change, and what a rewrite changed (#854), through the host and a
 * real pipeline: the view names what a rewrite would use while it is offered (`rewriteWith`); the
 * insight it writes says what it used, and so does the same card served from the cache on a later
 * play. An insight written the first time says nothing of a rewrite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostRewrittenNoteTest : PipelineHostFixture() {
    @Test
    fun `another model is named while Rewrite is offered, and the rewrite and a later play say it`() =
        runTest {
            written("Alpha")
            assertEquals(emptySet<RewritePart>(), shown().rewritten)
            assertNull(host.state.value.rewriteWith)

            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            val offer = checkNotNull(host.state.value.rewriteWith)
            assertEquals(setOf(RewritePart.MODEL), offer.parts)
            assertEquals(OTHER_MODEL, offer.with.model)

            tap()
            settle()
            assertEquals(setOf(RewritePart.MODEL), shown().rewritten)
            assertEquals(OTHER_MODEL, shown().writtenBy?.model)
            assertNull("nothing more to rewrite", host.state.value.rewriteWith)

            // A later play, after another track: the same card, from the cache, says it too.
            play("Beta")
            settle()
            play("Alpha")
            settle()
            assertTrue(shown().cached)
            assertEquals(setOf(RewritePart.MODEL), shown().rewritten)
            assertEquals(2, provider.requestsFor("Alpha"))
        }

    @Test
    fun `what Look again found new is named while Rewrite is offered, and the rewrite says it`() =
        runTest {
            cache.keepsResults = true
            written("Sundown")
            musicBrainz.text = "Sundown was a number one single."
            host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
            settle()
            assertEquals(
                setOf(RewritePart.NEW_FINDINGS),
                host.state.value.rewriteWith
                    ?.parts,
            )

            tap()
            settle()
            assertEquals(setOf(RewritePart.NEW_FINDINGS), shown().rewritten)
        }

    // [title] written on a tap under *On demand*, with nothing offered beside it.
    private fun TestScope.written(title: String) {
        settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
        started()
        play(title)
        settle()
        tap()
        settle()
        assertEquals(1, provider.requestsFor(title))
    }

    private fun shown(): InsightState.Written =
        host.state.value.track
            ?.insight as InsightState.Written

    private companion object {
        const val OTHER_MODEL = "claude-other"
    }
}
