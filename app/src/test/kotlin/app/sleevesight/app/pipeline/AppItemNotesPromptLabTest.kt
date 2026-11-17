package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptChoice
import app.sleevesight.core.synthesis.PromptChoices
import app.sleevesight.core.synthesis.PromptKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The song's, the artist's and the album's insights across a switch of the prompt lab (#1388): one
 * shown on a play stays held from an automatic write there, whichever prompt wrote it, on → off and
 * off → on, under every automatic mode; the other prompt's text is never shown in its place; a tap
 * still writes it, with the prompt now in use.
 */
class AppItemNotesPromptLabTest {
    private val variantB =
        PromptChoices(
            PromptKind.entries.associateWith { kind ->
                LabPrompts.variant(kind, "B")?.let { PromptChoice("B", it.instructions) }
                    ?: PromptChoice(LabPrompts.RELEASE, null)
            },
        )

    // One case's world: the AI service, the cache, the parts built with the lab's choices, and the notes.
    private inner class World(
        scope: TestScope,
        mode: WriteAutomatically,
        choices: PromptChoices,
    ) {
        val provider = CountingProvider().apply { reply = { "Gordon Lightfoot wrote it [W1]." } }
        private val cache = CardCache()
        private val settings =
            MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, writeAutomatically = mode))
        private val parts = MutableStateFlow<PipelineParts?>(parts(choices))
        val notes = AppItemNotes(parts, settings, scope, NameMatcher(JvmAccentFolder))

        private fun parts(choices: PromptChoices) =
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key"), choices),
                cache = cache,
            )

        // The pipeline rebuilt with [choices], as a switch of the lab rebuilds it.
        fun switched(choices: PromptChoices) {
            parts.value = parts(choices)
        }
    }

    private fun track(): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "Sundown was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(
            HomeFixtures.sundown.copy(play = 1),
            sources = sources,
            evidence = EvidenceSet.of(drafts, JvmHasher),
        )
    }

    private suspend fun AppItemNotes.shown(track: TrackState): ItemShown =
        item(track, InsightItem.SONG).first { it !is ItemShown.Shown || it.state !is InsightState.Writing }

    private fun ItemShown.variant(): String? =
        ((this as? ItemShown.Shown)?.state as? InsightState.Written)?.writtenBy?.variant

    private fun ItemShown.written(): Boolean = (this as? ItemShown.Shown)?.state is InsightState.Written

    private suspend fun TestScope.check(
        mode: WriteAutomatically,
        from: PromptChoices,
        to: PromptChoices,
    ) {
        val case = "${if (from.on) "on → off" else "off → on"}, $mode"
        val world = World(this, mode, from)
        val notes = world.notes
        val playing = track()
        notes.write(playing, InsightItem.SONG, ItemAsk.TAP)
        advanceUntilIdle()
        val before = notes.shown(playing)
        assertEquals(case, true, before.written())
        val first = before.variant()
        assertEquals(case, 1, world.provider.requests)

        world.switched(to)
        notes.shown(playing)
        // As the row asks, unfolded at the track's start and opened.
        notes.write(playing, InsightItem.SONG, ItemAsk.BY_ITSELF)
        notes.write(playing, InsightItem.SONG, ItemAsk.OPENED)
        advanceUntilIdle()
        assertEquals("$case: no call on its own", 1, world.provider.requests)
        val after = notes.shown(playing)
        assertEquals("$case: the other prompt's text not shown", false, after.written())

        notes.write(playing, InsightItem.SONG, ItemAsk.TAP)
        advanceUntilIdle()
        assertEquals("$case: a tap writes", 2, world.provider.requests)
        val tapped = notes.shown(playing)
        assertEquals(case, true, tapped.written())
        assertNotEquals(case, first, tapped.variant())
    }

    @Test
    fun `across a switch of the lab, an item shown on the play is held from an automatic write, and a tap writes it`() =
        runTest {
            for (mode in WriteAutomatically.entries.filter { it != WriteAutomatically.NEVER }) {
                check(mode, from = variantB, to = PromptChoices.NONE)
                check(mode, from = PromptChoices.NONE, to = variantB)
            }
        }
}
