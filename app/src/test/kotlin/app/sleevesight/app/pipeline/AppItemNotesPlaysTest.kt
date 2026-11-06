package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An item's insight after a write with another model ended, failed, stopped or written (#911), on
 * the same play and on a later one, under each *Reactive* mode: a write that failed or was stopped is
 * written again on its play only on a tap, the earlier model's insight shown with *Rewrite insight*
 * and why; on a later play it counts as none: shown with *Rewrite insight* under *On demand*, written
 * anew under an automatic mode, a rewrite of it (#972). One written is shown, whatever the play or
 * the mode, with no call.
 */
class AppItemNotesPlaysTest {
    private enum class Outcome { FAILED, STOPPED, WRITTEN }

    // One case's world: the AI service, the cache, the parts and the settings, and the notes over them.
    private inner class World(
        scope: TestScope,
    ) {
        val provider = CountingProvider().apply { reply = { "Gordon Lightfoot wrote it [W1]." } }
        val settings =
            MutableStateFlow<Settings?>(
                Settings(aiProvider = ProviderId.ANTHROPIC, writeAutomatically = WriteAutomatically.NEVER)
                    .withModel(ProviderId.ANTHROPIC, FIRST),
            )
        private val parts =
            MutableStateFlow<PipelineParts?>(
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters = emptyList(),
                    resolver = TrackResolver { _, _ -> Resolution.NONE },
                    hasher = JvmHasher,
                    ai = AiAccess(provider, Secret("key")),
                    cache = CardCache(),
                ),
            )
        val notes = AppItemNotes(parts, settings, scope, NameMatcher(JvmAccentFolder))

        fun set(change: (Settings) -> Settings) {
            settings.value = settings.value?.let(change)
        }
    }

    private fun track(play: Long): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "Sundown was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        val playing = HomeFixtures.sundown.copy(play = play)
        return HomeFixtures.track(playing, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher))
    }

    private suspend fun AppItemNotes.song(track: TrackState): ItemShown.Shown =
        item(track, InsightItem.SONG).first { it is ItemShown.Shown && it.state !is InsightState.Writing }
            as ItemShown.Shown

    private fun ItemShown.Shown.writtenBy(): String? = (state as? InsightState.Written)?.writtenBy?.model

    // The first model's insight written on play 1; on play 2, the other model's rewrite of it ended as [outcome].
    private suspend fun TestScope.attempted(
        world: World,
        outcome: Outcome,
    ) {
        val notes = world.notes
        val first = track(play = 1)
        notes.write(first, InsightItem.SONG, ItemAsk.TAP)
        advanceUntilIdle()
        assertEquals(FIRST, notes.song(first).writtenBy())
        world.set { it.withModel(ProviderId.ANTHROPIC, OTHER) }
        val playing = track(play = 2)
        assertEquals(FIRST, notes.song(playing).writtenBy())
        when (outcome) {
            Outcome.FAILED -> world.provider.error = AiErrorKind.SERVER
            Outcome.STOPPED -> world.provider.streaming = 10.seconds
            Outcome.WRITTEN -> Unit
        }
        notes.write(playing, InsightItem.SONG, ItemAsk.TAP)
        if (outcome == Outcome.STOPPED) {
            advanceTimeBy(1.seconds)
            val writing =
                notes
                    .item(
                        playing,
                        InsightItem.SONG,
                    ).first { it is ItemShown.Shown || it is ItemShown.Asking }
            notes.stop(checkNotNull((writing as? ItemShown.Asking)?.key ?: (writing as ItemShown.Shown).key))
        }
        advanceUntilIdle()
        world.provider.error = null
        world.provider.streaming = Duration.ZERO
    }

    @Test
    fun `every outcome, on the same play and a later one, under every mode`() =
        runTest {
            for (outcome in Outcome.entries) {
                for (later in listOf(false, true)) {
                    for (mode in WriteAutomatically.entries) {
                        check(outcome, later, mode)
                    }
                }
            }
        }

    private suspend fun TestScope.check(
        outcome: Outcome,
        later: Boolean,
        mode: WriteAutomatically,
    ) {
        val case = "$outcome, ${if (later) "a later play" else "the same play"}, $mode"
        val world = World(this)
        attempted(world, outcome)
        val calls = world.provider.requests
        world.set { it.copy(writeAutomatically = mode) }
        val playing = track(play = if (later) 3 else 2)
        val notes = world.notes
        notes.song(playing)
        // As the row asks, unfolded at the track's start and opened by a tap.
        notes.write(playing, InsightItem.SONG, ItemAsk.BY_ITSELF)
        notes.write(playing, InsightItem.SONG, ItemAsk.OPENED)
        advanceUntilIdle()
        val shown = notes.song(playing)
        val automatic = mode != WriteAutomatically.NEVER
        when {
            outcome == Outcome.WRITTEN -> {
                assertEquals(case, OTHER, shown.writtenBy())
                assertNull(case, shown.rewriteWith)
                assertEquals(case, calls, world.provider.requests)
            }

            later && automatic -> {
                // Written anew in place of the earlier insight: a rewrite of it, saying so (#972).
                assertEquals(case, calls + 1, world.provider.requests)
                assertEquals(case, OTHER, shown.writtenBy())
                assertEquals(case, setOf(RewritePart.MODEL), (shown.state as InsightState.Written).rewritten)
            }

            else -> {
                // The earlier insight with *Rewrite insight*; why the rewrite ended only on its play.
                assertEquals(case, calls, world.provider.requests)
                assertEquals(case, FIRST, shown.writtenBy())
                assertEquals(case, setOf(RewritePart.MODEL), shown.rewriteWith?.parts)
                val why =
                    when {
                        later -> null
                        outcome == Outcome.STOPPED -> PipelineHost.STOPPED
                        else -> shown.rewriteFailed
                    }
                assertEquals(case, why, shown.rewriteFailed)
                if (!later) assertEquals(case, true, shown.rewriteFailed != null)
                // A tap rewrites it, wherever it ended.
                notes.write(playing, InsightItem.SONG, ItemAsk.TAP)
                advanceUntilIdle()
                val rewritten = notes.song(playing)
                assertEquals(case, OTHER, rewritten.writtenBy())
                assertEquals(case, setOf(RewritePart.MODEL), (rewritten.state as InsightState.Written).rewritten)
            }
        }
    }

    private companion object {
        const val FIRST = "model-first"
        const val OTHER = "model-other"
    }
}
