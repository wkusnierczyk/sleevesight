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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The song's, the artist's and the album's insights after the model changed (#911), as the key
 * insight's: the insight another model wrote, kept, is shown with *Rewrite insight* when the item's
 * model has written none, and a tap rewrites it, saying so; a rewrite stopped or failed shows it
 * again. On the play where the model changed it stays in every mode; on a later play an automatic
 * mode writes the item anew. The model's own insight, when kept, is always the one shown.
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

    // Sundown (or [playing]) on its [play]th play, with a song line of its own and the artist's line.
    private fun track(
        play: Long? = null,
        playing: NowPlaying = HomeFixtures.sundown.copy(play = play),
    ): TrackState {
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
    fun `under On demand, a row whose cache is still read is not offered, and an opening meanwhile writes nothing`() =
        runTest {
            written(notes(), track(play = 1))

            model(OTHER)
            // A slow cache: the other model's insight is found only once it is let through (#948).
            val held = CompletableDeferred<Unit>()
            cache.readHeld = held
            val notes = notes()
            val playing = track(play = 2)
            val seen = mutableListOf<ItemShown>()
            val watching = launch { notes.item(playing, InsightItem.SONG).collect { seen += it } }
            advanceUntilIdle()
            assertEquals(ItemShown.Gathering, seen.last())
            assertTrue(seen.toString(), ItemShown.Offered !in seen)
            // A tap's opening under On demand while it reads: no call.
            notes.write(playing, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            held.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(ItemShown.Offered !in seen)
            assertKeptWithRewrite(seen.last() as ItemShown.Shown)
            watching.cancel()
        }

    @Test
    fun `an item's write under way is followed for what it is about, on the next track of the artist too`() =
        runTest {
            provider.streaming = 10.seconds
            val notes = notes()
            val first = track()
            val next = track(playing = HomeFixtures.carefree)
            assertEquals(false, notes.writes(first, InsightItem.ARTIST).first())
            notes.write(first, InsightItem.ARTIST, ItemAsk.TAP)
            advanceTimeBy(1.seconds)
            assertEquals(true, notes.writes(first, InsightItem.ARTIST).first())
            // The same artist on the next track; not that track's song, nor the artist's album.
            assertEquals(true, notes.writes(next, InsightItem.ARTIST).first())
            assertEquals(false, notes.writes(next, InsightItem.SONG).first())
            assertEquals(false, notes.writes(first, InsightItem.ALBUM).first())
            advanceUntilIdle()
            assertEquals(false, notes.writes(next, InsightItem.ARTIST).first())
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
    fun `under an automatic mode, an item opened on a later play is written anew`() =
        runTest {
            val notes = notes()
            written(notes, track(play = 1))

            mode(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
            model(OTHER)
            val later = track(play = 2)
            notes.write(later, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            val shown = notes.song(later) as ItemShown.Shown
            assertEquals(OTHER, shown.writtenBy())
            assertNull(shown.rewriteWith)
            // Written anew, as for any item its model has not written: not a rewrite.
            assertEquals(emptySet<RewritePart>(), (shown.state as InsightState.Written).rewritten)
            assertEquals(2, provider.requests)
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
            val failed =
                notes
                    .item(
                        failing,
                        InsightItem.SONG,
                    ).first { (it as? ItemShown.Shown)?.state is InsightState.Failed }
            assertNull((failed as ItemShown.Shown).rewriteWith)
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
