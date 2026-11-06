package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The song's, the artist's and the album's insights after the model changed (#911), as the key
 * insight's: the insight another model wrote, kept, is shown with *Rewrite insight* when the item's
 * model has written none, and a tap rewrites it, saying so; a rewrite stopped or failed shows it
 * again. On the play where the model changed it stays in every mode; on a later play an automatic
 * mode writes the item anew, a rewrite as the tap's is (#972). The model's own insight, when kept, is
 * always the one shown.
 */
class AppItemNotesRewriteTest {
    private val provider = CountingProvider().apply { reply = { "Gordon Lightfoot wrote it [W1]." } }
    private val cache = CardCache()
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
            ),
        )
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(aiProvider = ProviderId.ANTHROPIC, writeAutomatically = WriteAutomatically.NEVER)
                .withModel(ProviderId.ANTHROPIC, FIRST),
        )

    // A new instance is a new process: nothing in memory, the cache kept.
    private fun TestScope.notes() = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))

    // Sundown on its [play]th play, with a song line of its own and the artist's line.
    private fun track(play: Long? = null): TrackState {
        val playing: NowPlaying = HomeFixtures.sundown.copy(play = play)
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "Sundown was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(playing, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher))
    }

    private fun model(id: String) {
        settings.value = settings.value?.withModel(ProviderId.ANTHROPIC, id)
    }

    private fun mode(mode: WriteAutomatically) {
        settings.value = settings.value?.copy(writeAutomatically = mode)
    }

    // What the song's row shows once it has settled: offered, or an insight written.
    private suspend fun AppItemNotes.song(track: TrackState): ItemShown =
        item(track, InsightItem.SONG).first {
            it is ItemShown.Offered || (it as? ItemShown.Shown)?.state is InsightState.Written
        }

    // The song's insight written on a tap with the first model, and shown.
    private suspend fun TestScope.written(
        notes: AppItemNotes,
        track: TrackState,
    ) {
        notes.write(track, InsightItem.SONG, ItemAsk.TAP)
        advanceUntilIdle()
        assertEquals(FIRST, notes.song(track).writtenBy())
        assertEquals(1, provider.requests)
    }

    @Test
    fun `another model chosen shows the insight kept with Rewrite, and a tap rewrites it with that model`() =
        runTest {
            val notes = notes()
            val first = track()
            written(notes, first)

            model(OTHER)
            val offered = notes.song(first) as ItemShown.Shown
            assertKeptWithRewrite(offered)

            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            val rewritten = notes.song(first) as ItemShown.Shown
            assertEquals(OTHER, rewritten.writtenBy())
            assertEquals(OTHER, rewritten.model)
            assertNull(rewritten.rewriteWith)
            assertEquals(setOf(RewritePart.MODEL), (rewritten.state as InsightState.Written).rewritten)
            assertEquals(OTHER, provider.models.last())
            assertEquals(2, provider.requests)

            // Kept with what it changed: a new process says it too.
            val later = notes().song(first) as ItemShown.Shown
            assertEquals(setOf(RewritePart.MODEL), (later.state as InsightState.Written).rewritten)
        }

    @Test
    fun `under On demand, on a later play in a new process, the insight kept is shown with Rewrite`() =
        runTest {
            written(notes(), track(play = 1))

            model(OTHER)
            assertKeptWithRewrite(notes().song(track(play = 2)) as ItemShown.Shown)
            assertEquals(1, provider.requests)
        }

    @Test
    fun `under an automatic mode, an item opened on a later play is rewritten, saying so`() =
        runTest {
            val notes = notes()
            written(notes, track(play = 1))
            // A first write, with nothing kept: not a rewrite.
            assertEquals(
                emptySet<RewritePart>(),
                ((notes.song(track(play = 1)) as ItemShown.Shown).state as InsightState.Written).rewritten,
            )

            mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            model(OTHER)
            val later = track(play = 2)
            notes.write(later, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            val shown = notes.song(later) as ItemShown.Shown
            assertEquals(OTHER, shown.writtenBy())
            assertNull(shown.rewriteWith)
            // It replaced the insight kept from the first model, as a tap on Rewrite does (#972).
            assertEquals(setOf(RewritePart.MODEL), (shown.state as InsightState.Written).rewritten)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `under an automatic mode, an item written by itself on a later play is rewritten, saying so`() =
        runTest {
            written(notes(), track(play = 1))

            // A new process, its row not yet shown: the write by itself still finds the insight kept.
            mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            model(OTHER)
            val notes = notes()
            val later = track(play = 2)
            notes.write(later, InsightItem.SONG, ItemAsk.BY_ITSELF)
            advanceUntilIdle()
            val shown = notes.song(later) as ItemShown.Shown
            assertEquals(OTHER, shown.writtenBy())
            assertNull(shown.rewriteWith)
            assertEquals(setOf(RewritePart.MODEL), (shown.state as InsightState.Written).rewritten)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `an automatic write that failed, retried on the same play, is a rewrite, saying so`() =
        runTest { retriedOnItsPlay(stopped = false) }

    @Test
    fun `an automatic write that was stopped, retried on the same play, is a rewrite, saying so`() =
        runTest { retriedOnItsPlay(stopped = true) }

    // The first model's insight kept; on a later play under an automatic mode the other model's write
    // fails, or is [stopped], and the earlier insight shows again, saying so, as after a tap's rewrite
    // (#972); a tap on that play writes it again, as a rewrite.
    private suspend fun TestScope.retriedOnItsPlay(stopped: Boolean) {
        written(notes(), track(play = 1))

        mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
        model(OTHER)
        if (stopped) provider.streaming = 10.seconds else provider.error = AiErrorKind.SERVER
        val notes = notes()
        val later = track(play = 2)
        notes.write(later, InsightItem.SONG, ItemAsk.OPENED)
        if (stopped) {
            advanceTimeBy(1.seconds)
            val writing = notes.item(later, InsightItem.SONG).first { it is ItemShown.Shown || it is ItemShown.Asking }
            notes.stop(checkNotNull((writing as? ItemShown.Asking)?.key ?: (writing as ItemShown.Shown).key))
        }
        advanceUntilIdle()
        // The earlier insight shown again, saying why, as after a tap's rewrite.
        val ended = notes.song(later) as ItemShown.Shown
        assertKeptWithRewrite(ended)
        if (stopped) {
            assertEquals(PipelineHost.STOPPED, ended.rewriteFailed)
        } else {
            assertTrue(ended.rewriteFailed != null && ended.rewriteFailed != PipelineHost.STOPPED)
        }

        provider.error = null
        provider.streaming = Duration.ZERO
        notes.write(later, InsightItem.SONG, ItemAsk.TAP)
        advanceUntilIdle()
        val shown = notes.song(later) as ItemShown.Shown
        assertEquals(OTHER, shown.writtenBy())
        assertEquals(setOf(RewritePart.MODEL), (shown.state as InsightState.Written).rewritten)
        assertEquals(3, provider.requests)
    }

    @Test
    fun `on the play the model changed while its row showed an insight, no automatic write replaces it`() =
        runTest {
            mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            val notes = notes()
            val playing = track(play = 1)
            notes.write(playing, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            // Shown on this play.
            assertEquals(FIRST, notes.song(playing).writtenBy())

            model(OTHER)
            assertKeptWithRewrite(notes.song(playing) as ItemShown.Shown)
            notes.write(playing, InsightItem.SONG, ItemAsk.BY_ITSELF)
            notes.write(playing, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertKeptWithRewrite(notes.song(playing) as ItemShown.Shown)

            // The next play of it opens it written anew.
            notes.write(track(play = 2), InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            assertEquals(OTHER, provider.models.last())
        }

    @Test
    fun `an automatic write that failed on a later play does not hide the insight kept on the next`() =
        runTest {
            val notes = notes()
            written(notes, track(play = 1))

            // A later play under an automatic mode: written anew with the other model, and it fails.
            mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            model(OTHER)
            provider.error = AiErrorKind.SERVER
            val failing = track(play = 2)
            notes.song(failing)
            notes.write(failing, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            // A rewrite of the insight kept, as a tap's (#972): it comes back, saying why.
            val failed = notes.song(failing) as ItemShown.Shown
            assertKeptWithRewrite(failed)
            assertTrue(failed.rewriteFailed != null && failed.rewriteFailed != PipelineHost.STOPPED)
            assertEquals(2, provider.requests)

            // The next play, under On demand, in the same process: the insight kept, with Rewrite.
            provider.error = null
            mode(WriteAutomatically.NEVER)
            val next = track(play = 3)
            val shown = notes.song(next) as ItemShown.Shown
            assertKeptWithRewrite(shown)
            assertNull(shown.rewriteFailed)
            // Nothing writes it unasked; a tap rewrites it.
            notes.write(next, InsightItem.SONG, ItemAsk.BY_ITSELF)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            notes.write(next, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            val rewritten = notes.song(next) as ItemShown.Shown
            assertEquals(OTHER, rewritten.writtenBy())
            assertEquals(setOf(RewritePart.MODEL), (rewritten.state as InsightState.Written).rewritten)
            assertEquals(3, provider.requests)
        }

    @Test
    fun `the chosen model's own insight is shown, never another's in its place, and the earlier one again`() =
        runTest {
            val notes = notes()
            val first = track()
            written(notes, first)
            model(OTHER)
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(2, provider.requests)

            // A new process: both kept, each under its own model.
            val again = notes()
            val own = again.song(first) as ItemShown.Shown
            assertEquals(OTHER, own.writtenBy())
            assertNull(own.rewriteWith)

            // The earlier model chosen again: its own, with no call and nothing offered.
            model(FIRST)
            val earlier = again.song(first) as ItemShown.Shown
            assertEquals(FIRST, earlier.writtenBy())
            assertNull(earlier.rewriteWith)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a rewrite stopped shows the earlier insight again, saying so, with Rewrite`() =
        runTest {
            val notes = notes()
            val first = track()
            written(notes, first)
            model(OTHER)
            notes.song(first)
            provider.streaming = 10.seconds

            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceTimeBy(1.seconds)
            val writing = notes.item(first, InsightItem.SONG).first { it is ItemShown.Asking || it.isWriting() }
            notes.stop(checkNotNull((writing as? ItemShown.Asking)?.key ?: (writing as ItemShown.Shown).key))
            advanceUntilIdle()

            val shown = notes.song(first) as ItemShown.Shown
            assertKeptWithRewrite(shown)
            assertEquals(PipelineHost.STOPPED, shown.rewriteFailed)

            // A tap on Rewrite insight writes it again, a rewrite still.
            provider.streaming = 0.seconds
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            val rewritten = notes.song(first) as ItemShown.Shown
            assertEquals(OTHER, rewritten.writtenBy())
            assertEquals(setOf(RewritePart.MODEL), (rewritten.state as InsightState.Written).rewritten)
        }

    @Test
    fun `a rewrite failed shows the earlier insight again, saying why, with Rewrite`() =
        runTest {
            val notes = notes()
            val first = track()
            written(notes, first)
            model(OTHER)
            notes.song(first)
            provider.error = AiErrorKind.SERVER

            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()

            val shown = notes.song(first) as ItemShown.Shown
            assertKeptWithRewrite(shown)
            assertTrue(shown.rewriteFailed != null && shown.rewriteFailed != PipelineHost.STOPPED)
        }

    private fun ItemShown.isWriting() = (this as? ItemShown.Shown)?.state is InsightState.Writing

    private fun ItemShown.writtenBy(): String? =
        ((this as? ItemShown.Shown)?.state as? InsightState.Written)
            ?.writtenBy
            ?.model

    // The insight the first model wrote, shown with *Rewrite insight* offered to write it with the other.
    private fun assertKeptWithRewrite(shown: ItemShown.Shown) {
        assertEquals(FIRST, shown.writtenBy())
        assertEquals(FIRST, shown.model)
        assertEquals(setOf(RewritePart.MODEL), shown.rewriteWith?.parts)
        assertEquals(OTHER, shown.rewriteWith?.with?.model)
    }

    private companion object {
        const val FIRST = "model-first"
        const val OTHER = "model-other"
    }
}
