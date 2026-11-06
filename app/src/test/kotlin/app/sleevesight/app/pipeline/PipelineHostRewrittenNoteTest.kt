package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // Look again finds something new, then another model is chosen: the rebuild replaces the run
    // that looked, and its evidence is what the look again found, so a rewrite still names it.
    @Test
    fun `what Look again found new is still named after a settings change replaced its run`() =
        runTest {
            cache.keepsResults = true
            written("Sundown")
            musicBrainz.text = "Sundown was a number one single."
            host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
            settle()
            assertTrue(host.state.value.somethingNew)

            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            val both = setOf(RewritePart.NEW_FINDINGS, RewritePart.MODEL)
            assertEquals(
                both,
                host.state.value.rewriteWith
                    ?.parts,
            )

            tap()
            settle()
            assertEquals(both, shown().rewritten)
            assertEquals(OTHER_MODEL, shown().writtenBy?.model)

            // The card written from it was compared with nothing new: another model is all a rewrite changes.
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, THIRD_MODEL)
            settle()
            assertEquals(
                setOf(RewritePart.MODEL),
                host.state.value.rewriteWith
                    ?.parts,
            )
        }

    // Look again finds a new MusicBrainz fact, then Last.fm's key is removed: the evidence written
    // from is no longer the evidence the look again found it in, so both are named.
    @Test
    fun `what Look again found new is still named after another source changed, with updated sources`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            cache.keepsResults = true
            written("Sundown")
            musicBrainz.text = "Sundown was a number one single."
            host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
            settle()
            assertTrue(host.state.value.somethingNew)

            keys = PipelineKeys(ai = Secret("ai"))
            keyChanges.value++
            settle()
            val both = setOf(RewritePart.NEW_FINDINGS, RewritePart.UPDATED_SOURCES)
            assertEquals(
                both,
                host.state.value.rewriteWith
                    ?.parts,
            )

            tap()
            settle()
            assertEquals(both, shown().rewritten)
        }

    // A source removed, then Look again finds nothing new (missing lines never count as new): the
    // evidence still is not the card's, so the offer and the rewrite name updated sources.
    @Test
    fun `a Look again that finds nothing new after a source was removed still names updated sources`() =
        runTest {
            keys = PipelineKeys(ai = Secret("ai"), sources = SourceKeys(lastFm = Secret("lastfm")))
            cache.keepsResults = true
            written("Sundown")
            keys = PipelineKeys(ai = Secret("ai"))
            keyChanges.value++
            settle()
            assertEquals(
                setOf(RewritePart.UPDATED_SOURCES),
                host.state.value.rewriteWith
                    ?.parts,
            )

            host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
            settle()
            assertTrue(host.state.value.nothingNew)
            assertTrue(host.state.value.rewrite)
            assertEquals(
                setOf(RewritePart.UPDATED_SOURCES),
                host.state.value.rewriteWith
                    ?.parts,
            )

            tap()
            settle()
            assertEquals(setOf(RewritePart.UPDATED_SOURCES), shown().rewritten)
        }

    // Look again with nothing new and nothing else changed: nothing is offered, so nothing named.
    @Test
    fun `a Look again that finds nothing new with nothing else changed names nothing`() =
        runTest {
            cache.keepsResults = true
            written("Sundown")
            host.lookAgain(checkNotNull(host.state.value.lookAgainKey), checkNotNull(changes.value?.nowPlaying))
            settle()
            assertTrue(host.state.value.nothingNew)
            assertFalse(host.state.value.rewrite)
            assertNull(host.state.value.rewriteWith)
        }

    // The card paid for, shown in place of the rebuilt run's own insight (#606), while Rewrite is
    // offered beside it: it was written the first time, and the offer names what changed.
    @Test
    fun `the card paid for shown in place says nothing of a rewrite, and the offer names the model`() =
        runTest {
            written("Alpha")
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()

            assertTrue(host.state.value.cardShown)
            assertTrue(host.state.value.rewrite)
            assertEquals(emptySet<RewritePart>(), shown().rewritten)
            assertEquals(
                setOf(RewritePart.MODEL),
                host.state.value.rewriteWith
                    ?.parts,
            )
        }

    // A rewrite whose call fails shows the card paid for again, still offering what a rewrite would
    // use; the retry's insight says it.
    @Test
    fun `a failed rewrite still names the model, and the retry's insight says it`() =
        runTest {
            written("Alpha")
            settings.value = settings.value.withModel(ProviderId.ANTHROPIC, OTHER_MODEL)
            settle()
            provider.error = AiErrorKind.OVERLOADED
            tap()
            settle()
            assertNotNull(host.state.value.rewriteFailed)
            assertTrue(host.state.value.cardShown)
            assertEquals(
                setOf(RewritePart.MODEL),
                host.state.value.rewriteWith
                    ?.parts,
            )

            provider.error = null
            tap()
            settle()
            assertEquals(setOf(RewritePart.MODEL), shown().rewritten)
            assertEquals(OTHER_MODEL, shown().writtenBy?.model)
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
        const val THIRD_MODEL = "claude-third"
    }
}
