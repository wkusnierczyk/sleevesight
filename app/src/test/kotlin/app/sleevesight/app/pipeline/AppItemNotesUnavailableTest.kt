package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The song's, the artist's and the album's insights while the selected model is found unavailable
 * (#540, #911), through the pipeline's own rebuild: the parts carry no AI access then
 * (`AppPipelineParts`, `ModelCheck.blocks`), and what is kept still shows, the model's own insight
 * first, else another model's, with nothing offered to write.
 */
class AppItemNotesUnavailableTest {
    private val provider = CountingProvider().apply { reply = { "Gordon Lightfoot wrote it [W1]." } }
    private val cache = CardCache()

    // The app's wiring, every AI service through [provider]; nothing here sends a request.
    private val wiring =
        appPipelineWiring("0.1.0", JvmAccentFolder, JvmResourceReader, ArtistResults.None, aiThrough = { provider })
    private val keys = PipelineKeys(ai = Secret("key"))
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(aiProvider = ProviderId.ANTHROPIC, writeAutomatically = WriteAutomatically.NEVER)
                .withModel(ProviderId.ANTHROPIC, FIRST),
        )
    private val parts = MutableStateFlow<PipelineParts?>(null)

    @After
    fun close() = wiring.close()

    // The check of the selected model, and the parts it builds as the app's do.
    private class Built(
        val check: ModelCheck,
        val build: AppPipelineParts,
    )

    private fun TestScope.built(): Built {
        val check = ModelCheck(wiring.aiProviders, { Secret("key") }, backgroundScope)
        return Built(check, AppPipelineParts(wiring, Normalizer(JvmAccentFolder), models = check) { cache })
    }

    // A rebuild, as the host's after a settings change or a model found unavailable.
    private fun Built.rebuild() {
        parts.value = build(keys, checkNotNull(settings.value))
    }

    // Once the check has heard the refusal and marked the selected model unavailable.
    private suspend fun Built.heard() {
        check.unavailable.first { it.isNotEmpty() }
    }

    private fun model(id: String) {
        settings.value = settings.value?.withModel(ProviderId.ANTHROPIC, id)
    }

    private fun track(): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "Sundown was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher))
    }

    private suspend fun AppItemNotes.song(track: TrackState): ItemShown =
        item(track, InsightItem.SONG).first {
            it is ItemShown.Offered ||
                it is ItemShown.NoService ||
                (it as? ItemShown.Shown)?.state is InsightState.Written
        }

    private fun ItemShown.writtenBy(): String? =
        ((this as? ItemShown.Shown)?.state as? InsightState.Written)
            ?.writtenBy
            ?.model

    @Test
    fun `a rewrite the model was found unavailable for shows the insight kept, with nothing to tap`() =
        runTest {
            val built = built()
            built.rebuild()
            val notes = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))
            val first = track()
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(FIRST, notes.song(first).writtenBy())

            model(OTHER)
            built.rebuild()
            assertEquals(FIRST, notes.song(first).writtenBy())

            // The rewrite's call answered as a model the service does not know: the model is marked
            // unavailable, and the parts are built again without AI access.
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            built.heard()
            val unavailable = built.check.of(checkNotNull(settings.value), ProviderId.ANTHROPIC)
            assertEquals(UnavailableModel(ProviderId.ANTHROPIC, OTHER), unavailable)
            built.rebuild()
            assertNull(parts.value?.ai)

            // Not *no AI service*: the insight kept, as written by the first model, with Rewrite not offered.
            val shown = notes.song(first) as ItemShown.Shown
            assertEquals(FIRST, shown.writtenBy())
            assertEquals(setOf(RewritePart.MODEL), shown.rewriteWith?.parts)
            assertFalse(shown.canWrite)
            assertEquals(2, provider.requests)

            // A new process, read from the cache alone: the same.
            val again = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder)).song(first)
            assertEquals(FIRST, again.writtenBy())
            assertFalse((again as ItemShown.Shown).canWrite)
        }

    @Test
    fun `with the model found unavailable, its own insight kept is the one shown`() =
        runTest {
            val built = built()
            built.rebuild()
            val notes = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))
            val first = track()
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            model(OTHER)
            built.rebuild()
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(OTHER, notes.song(first).writtenBy())

            // The other model found unavailable later (a call of the key insight's refused), the parts rebuilt.
            val refused = CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)
            built.check.answered(built.check.ticket(), ProviderId.ANTHROPIC, OTHER, Secret("key"), refused)
            built.check.ticket()
            built.rebuild()
            assertNull(parts.value?.ai)

            val shown = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder)).song(first) as ItemShown.Shown
            assertEquals(OTHER, shown.writtenBy())
            assertNull(shown.rewriteWith)
        }

    @Test
    fun `with nothing kept, an unavailable model offers nothing to write`() =
        runTest {
            val built = built()
            built.rebuild()
            val first = track()
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            val notes = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            built.heard()
            built.rebuild()
            assertNull(parts.value?.ai)
            assertEquals(
                ItemShown.NoService,
                AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder)).song(first),
            )
        }

    private companion object {
        const val FIRST = "model-first"
        const val OTHER = "model-other"
    }
}
