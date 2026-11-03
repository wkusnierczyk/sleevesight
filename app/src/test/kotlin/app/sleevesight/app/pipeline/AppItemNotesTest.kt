package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The song's, the artist's and the album's insights in the app (#522): each written on its own, a
 * call each; the artist's and the album's found again with no call on the next track of the album;
 * nothing written for an item never opened; forgotten on *Clear history*.
 */
class AppItemNotesTest {
    private val provider = CountingProvider().apply { reply = { "Gordon Lightfoot wrote it [W1]." } }
    private val cache = CardCache()

    // While set, an album's lookup waits for it: its gathering under way.
    private var gathering: CompletableDeferred<Unit>? = null

    // The album's lookups made, and whether a source of theirs could not be asked.
    private var albumLookups = 0
    private var albumUnreachable = false
    private val albums =
        AlbumLookup {
            albumLookups++
            gathering?.await()
            AlbumFound(
                listOf(HomeFixtures.draft(SourceId.WIKIPEDIA, "Sundown is his tenth album.", scope = Scope.RELEASE)),
                unreachable = albumUnreachable,
            )
        }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                albums = albums,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    // The coordinator's gate, as the test sets it.
    private var open = true
    private val events = mutableListOf<PipelineEvent>()

    private fun TestScope.notes(epoch: ClearEpoch = ClearEpoch()) =
        AppItemNotes(
            parts,
            settings,
            this,
            NameMatcher(JvmAccentFolder),
            epoch,
            events = { events += it },
            gate = { open },
        )

    // A track of Sundown, with a song line of its own and the artist's line; with [songArticle], a
    // Wikipedia line about the song before it, so the artist's line is the track's `W2`.
    private fun track(
        playing: NowPlaying,
        songArticle: Boolean = false,
    ): TrackState {
        val drafts =
            listOfNotNull(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "${playing.title} was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "${playing.title} is a song.").takeIf { songArticle },
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        // As the run gathered them: each source's lines in its result, and the evidence assembled from them.
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(playing, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher)).copy(
            resolution =
                Resolution(
                    recordingMbid = "rec-${playing.title}",
                    artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
                    releaseDates = listOf(ReleaseDate("Sundown", "1974", "rg-sundown", "Album")),
                ),
        )
    }

    private suspend fun AppItemNotes.settled(
        track: TrackState,
        item: InsightItem,
    ): ItemShown =
        item(track, item).first {
            it is ItemShown.Offered ||
                (it as? ItemShown.Shown)?.state is InsightState.Written
        }

    @Test
    fun `the artist's insight is written once for an album's tracks, and the next track's opens with no call`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ARTIST))
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertTrue(notes.settled(first, InsightItem.ARTIST) is ItemShown.Shown)
            assertEquals(1, provider.requests)

            val next = track(HomeFixtures.carefree)
            val shown = notes.settled(next, InsightItem.ARTIST) as ItemShown.Shown
            assertTrue(shown.state is InsightState.Written)
            notes.write(next, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `the album's insight is written once for its tracks, and an item never opened costs nothing`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            notes.write(track(HomeFixtures.carefree), InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            // The song's was never opened: no call for it.
            assertEquals(0, provider.requestsFor("the song's own story"))
        }

    @Test
    fun `an artist's insight kept from another track cites this track's lines`() =
        runTest {
            val notes = notes()
            val title = track(HomeFixtures.sundown, songArticle = true)
            notes.write(title, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals("W2", (notes.settled(title, InsightItem.ARTIST) as ItemShown.Shown).evidence.trackIds["W1"])

            val next = track(HomeFixtures.carefree)
            assertEquals("W1", (notes.settled(next, InsightItem.ARTIST) as ItemShown.Shown).evidence.trackIds["W1"])
            assertEquals(1, provider.requests)
        }

    @Test
    fun `no call is made while the coordinator's gate is closed`() =
        runTest {
            val notes = notes()
            open = false
            notes.write(track(HomeFixtures.sundown), InsightItem.SONG, ItemAsk.BY_ITSELF)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertTrue(events.any { it is PipelineEvent.AiNotAsked })
            assertEquals(ItemShown.Offered, notes.settled(track(HomeFixtures.sundown), InsightItem.SONG))
        }

    @Test
    fun `insights switched off while the album is gathered make no call`() =
        runTest {
            val notes = notes()
            val gate = CompletableDeferred<Unit>()
            gathering = gate
            notes.write(track(HomeFixtures.sundown), InsightItem.ALBUM)
            advanceUntilIdle()
            settings.value = settings.value?.copy(writeInsights = false)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `another AI service selected while the album is gathered makes no call`() =
        runTest {
            val notes = notes()
            val gate = CompletableDeferred<Unit>()
            gathering = gate
            notes.write(track(HomeFixtures.sundown), InsightItem.ALBUM)
            advanceUntilIdle()
            // Selected before the parts are rebuilt for it: they still carry the service before.
            settings.value = settings.value?.copy(aiProvider = ProviderId.OPENAI)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `clear history while the album is gathered keeps nothing and makes no call`() =
        runTest {
            val notes = notes()
            val gate = CompletableDeferred<Unit>()
            gathering = gate
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            notes.forget()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            // Nothing it gathered was kept: the album is gathered again when next asked.
            var lookups = 0
            gathering = null
            parts.value =
                parts.value?.let { made ->
                    PipelineParts(
                        made.normalizer,
                        made.adapters,
                        made.resolver,
                        made.hasher,
                        made.ai,
                        made.cache,
                        albums = {
                            lookups++
                            AlbumFound(emptyList())
                        },
                    )
                }
            notes.item(first, InsightItem.ALBUM).first { it !is ItemShown.Gathering }
            assertEquals(1, lookups)
        }

    @Test
    fun `an item the gates refuse goes back to offered, not asking`() =
        runTest {
            val notes = notes()
            open = false
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(ItemShown.Offered, notes.item(first, InsightItem.SONG).first())
        }

    // The key of [item]'s write under way on [track], as the row has it.
    private suspend fun writingKey(
        notes: AppItemNotes,
        track: TrackState,
        item: InsightItem,
    ): CardKey =
        when (
            val shown =
                notes.item(track, item).first {
                    it is ItemShown.Asking ||
                        (it as? ItemShown.Shown)?.state is InsightState.Writing
                }
        ) {
            is ItemShown.Asking -> shown.key
            else -> checkNotNull((shown as ItemShown.Shown).key)
        }

    @Test
    fun `stop cuts off an album's write whose lookup could not ask every source`() =
        runTest {
            provider.streaming = 10.seconds
            albumUnreachable = true
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ALBUM, ItemAsk.TAP)
            advanceTimeBy(1.seconds)
            notes.stop(writingKey(notes, first, InsightItem.ALBUM))
            advanceUntilIdle()
            assertEquals(0, provider.completed)
            assertEquals(0, cache.held)
        }

    @Test
    fun `the track updated while the album is gathered gathers it once, under one key`() =
        runTest {
            val notes = notes()
            val gate = CompletableDeferred<Unit>()
            gathering = gate
            val first = track(HomeFixtures.sundown)
            // The row followed afresh at each update of the track (the key insight streaming), and a write.
            val updates =
                listOf("It", "It was", "It was recorded").map { words ->
                    first.copy(insight = InsightState.Writing(words, model = "m"))
                }
            for (update in updates) {
                val watching = launch { notes.item(update, InsightItem.ALBUM).collect {} }
                advanceUntilIdle()
                watching.cancel()
            }
            notes.write(updates.last(), InsightItem.ALBUM, ItemAsk.OPENED)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, albumLookups)
            assertEquals(1, provider.requests)
            // The row shows what the write wrote: one key.
            assertTrue(notes.settled(first, InsightItem.ALBUM) is ItemShown.Shown)
        }

    @Test
    fun `the album's own lines are there for Sources only once its insight has gathered them on the track`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            assertEquals(null, notes.albumEvidence(first).first())
            notes.item(first, InsightItem.ALBUM).first { it !is ItemShown.Gathering }
            assertEquals(
                listOf("Sundown is his tenth album."),
                notes
                    .albumEvidence(first)
                    .first()
                    ?.lines
                    ?.map { it.text },
            )
            // Another track of the album has not gathered them yet.
            assertEquals(null, notes.albumEvidence(track(HomeFixtures.carefree)).first())
        }

    @Test
    fun `an album a source could not be asked for is kept for a while, not asked again at each update`() =
        runTest {
            albumUnreachable = true
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            repeat(3) { notes.item(first, InsightItem.ALBUM).first { it !is ItemShown.Gathering } }
            assertEquals(1, albumLookups)
        }

    @Test
    fun `stop cuts the call off, keeps nothing, and only a tap writes it again`() =
        runTest {
            provider.streaming = 10.seconds
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceTimeBy(1.seconds)
            notes.stop(writingKey(notes, first, InsightItem.SONG))
            advanceUntilIdle()
            assertEquals(
                InsightState.Stopped,
                (
                    notes.item(first, InsightItem.SONG).first {
                        it is ItemShown.Shown
                    } as ItemShown.Shown
                ).state,
            )
            assertEquals(0, provider.completed)
            assertEquals(0, cache.held)

            // Opening it again does not; a tap on Write insight does, a new call.
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            notes.write(first, InsightItem.SONG, ItemAsk.TAP)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            assertTrue(notes.settled(first, InsightItem.SONG) is ItemShown.Shown)
        }

    @Test
    fun `clear history forgets what was shown`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertTrue(notes.settled(first, InsightItem.SONG) is ItemShown.Shown)
            notes.forget()
            cache.clearCards()
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.SONG))
        }
}
