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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The song's, the artist's and the album's insights while the selected model is found unavailable
 * (#540, #911), through the pipeline's own rebuild: the parts carry no AI access then
 * (`AppPipelineParts`, `ModelCheck.blocks`), and what is kept still shows, the model's own insight
 * first, else another model's, with nothing offered to write; and, after a write refused, what the
 * panel is given to say as the model unavailable (#969). Under Robolectric: the parts write the
 * prompts' dates with the phone's patterns (`phoneDateForm`, #957), which only Android has.
 */
@RunWith(RobolectricTestRunner::class)
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
    private fun Built.rebuild(with: PipelineKeys = keys) {
        parts.value = build(with, checkNotNull(settings.value))
    }

    // Once the check has heard the refusal and marked the selected model unavailable.
    private suspend fun Built.heard() {
        check.unavailable.first { it.isNotEmpty() }
    }

    private fun model(id: String) {
        settings.value = settings.value?.withModel(ProviderId.ANTHROPIC, id)
    }

    private fun track(play: Long? = null): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "Sundown was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        val playing = HomeFixtures.sundown.copy(play = play)
        return HomeFixtures.track(playing, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher))
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

    // The insight the first model wrote kept, the other model chosen, and the song's next write refused
    // as a model the service does not know ([ask] on [later]), heard, and the parts built again (#969).
    private suspend fun TestScope.refusedOn(
        notes: AppItemNotes,
        built: Built,
        later: TrackState,
        ask: ItemAsk,
    ): ItemShown.Shown {
        // Shown first, as the panel shows it before it asks: the first model's insight, with Rewrite.
        assertEquals(FIRST, notes.song(later).writtenBy())
        provider.error = AiErrorKind.MODEL_NOT_FOUND
        notes.write(later, InsightItem.SONG, ask)
        advanceUntilIdle()
        built.heard()
        built.rebuild()
        return notes.item(later, InsightItem.SONG).first {
            it is ItemShown.Shown && it.state !is InsightState.Writing
        } as ItemShown.Shown
    }

    @Test
    fun `under On demand, a rewrite refused keeps why beside the insight kept, which the note says instead`() =
        runTest {
            val built = built()
            built.rebuild()
            val notes = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))
            val playing = track(play = 1)
            notes.write(playing, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            model(OTHER)
            built.rebuild()

            // What the panel is given on this play (#969): the insight kept, nothing to tap, and why the
            // rewrite failed, which the panel leaves out while it says the model is unavailable.
            val shown = refusedOn(notes, built, playing, ItemAsk.TAP)
            assertEquals(FIRST, shown.writtenBy())
            assertFalse(shown.canWrite)
            assertEquals(MODEL_NOT_FOUND, shown.rewriteFailed)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `automatically, a write anew refused on a later play is the failure the note replaces`() =
        runTest {
            settings.value = settings.value?.copy(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            val built = built()
            built.rebuild()
            val notes = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))
            notes.write(track(play = 1), InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            model(OTHER)
            built.rebuild()

            // A later play: its row unfolded at the track's start writes anew, by itself, and is refused.
            // Not a rewrite: the write failed, which the panel says as the model unavailable, in its place.
            val shown = refusedOn(notes, built, track(play = 2), ItemAsk.BY_ITSELF)
            assertEquals(InsightState.Failed(MODEL_NOT_FOUND), shown.state)
            assertNull(shown.rewriteFailed)
            assertFalse(shown.canWrite)
            assertEquals(2, provider.requests)
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
    fun `with the AI key removed, an insight kept is not shown, and the row says no service is ready`() =
        runTest {
            val built = built()
            built.rebuild()
            val notes = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))
            val first = track()
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(FIRST, notes.song(first).writtenBy())
            model(OTHER)

            // No key: no AI access, and not for a model found unavailable. As before #911, as the key
            // insight is: no service, whatever is kept, its own or another model's.
            built.rebuild(PipelineKeys())
            assertNull(parts.value?.ai)
            assertFalse(checkNotNull(parts.value).modelUnavailable)
            assertEquals(
                ItemShown.NoService,
                AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder)).song(first),
            )
            model(FIRST)
            assertEquals(
                ItemShown.NoService,
                AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder)).song(first),
            )
            assertEquals(1, provider.requests)
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
        const val MODEL_NOT_FOUND = "model_not_found"
    }
}
