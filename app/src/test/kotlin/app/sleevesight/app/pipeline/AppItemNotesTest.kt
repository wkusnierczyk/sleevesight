package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
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

    // What was counted as paid (#885: an album's web searches among it).
    private var paid = QuestionCost()

    private fun TestScope.notes(epoch: ClearEpoch = ClearEpoch()) =
        AppItemNotes(
            parts,
            settings,
            this,
            NameMatcher(JvmAccentFolder),
            epoch,
            paid = { paid += it },
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

    // An album with a web search (#885): its lookups give the web's line once a search kept it. With
    // no [engine] (Exa's key not given), no search, as `AlbumSource` without one; with [free] none,
    // nothing but the web's line.
    private inner class WebAlbums(
        private val engine: Boolean = true,
        private val free: Boolean = true,
    ) : AlbumLookup {
        var searches = 0
        var kept: List<EvidenceDraft>? = null
        private val web = HomeFixtures.draft(SourceId.WEB, "Sundown topped the Billboard 200.", scope = Scope.WEB)

        // While set, a search's read of the kept lines waits for it.
        var reading: CompletableDeferred<Unit>? = null

        // While set, a search that kept its lines waits for it before it returns.
        var returning: CompletableDeferred<Unit>? = null

        override val searchesWeb: Boolean get() = engine

        override suspend fun lookup(query: AlbumQuery): AlbumFound {
            albumLookups++
            gathering?.await()
            val lines =
                listOf(
                    HomeFixtures.draft(SourceId.WIKIPEDIA, "Sundown is his tenth album.", scope = Scope.RELEASE),
                ).takeIf { free }.orEmpty()
            return AlbumFound(lines + kept.orEmpty(), webDue = query.web && engine && kept == null)
        }

        // As `AlbumSource`'s: none with web search off; a request sent only when nothing is kept, and
        // only while [mayPay] says so after the read; the lines given to [keeping] before they are kept.
        override suspend fun searchWeb(
            query: AlbumQuery,
            mayPay: () -> Boolean,
            keeping: (List<EvidenceDraft>) -> Unit,
            sent: () -> Unit,
        ): List<EvidenceDraft>? {
            val may = query.web && engine
            if (may) reading?.await()
            return when {
                !may -> null
                kept != null -> kept
                !mayPay() -> null
                else -> searched(keeping, sent)
            }
        }

        // The search's request sent, its lines given to [keeping], kept, then returned.
        private suspend fun searched(
            keeping: (List<EvidenceDraft>) -> Unit,
            sent: () -> Unit,
        ): List<EvidenceDraft> {
            searches++
            sent()
            return listOf(web).also {
                keeping(it)
                kept = it
                returning?.await()
            }
        }
    }

    // The parts, with [albums] gathering the album's evidence.
    private fun withAlbums(albums: AlbumLookup) {
        parts.value =
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                albums = albums,
            )
    }

    @Test
    fun `an album's web search is made by its write only, and its insight is found again with no search`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            // Shown, unfolded, its key looked up: no search.
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ALBUM))
            assertEquals(0, web.searches)

            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests)
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
            assertTrue("Sundown topped the Billboard 200." in provider.prompts.single())
            val shown = notes.settled(first, InsightItem.ALBUM) as ItemShown.Shown
            assertTrue(
                shown.evidence.evidence.lines
                    .any { it.source == SourceId.WEB },
            )
            // *Sources* lists the web's line with the album's.
            assertTrue(
                notes
                    .albumEvidence(first)
                    .first()
                    ?.lines
                    .orEmpty()
                    .any { it.source == SourceId.WEB },
            )

            // The next track of the album finds it kept, under the same key, with no search or call.
            val next = track(HomeFixtures.carefree)
            assertTrue(notes.settled(next, InsightItem.ALBUM) is ItemShown.Shown)
            notes.write(next, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests)
            // The lines kept are not a search paid for.
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
        }

    @Test
    fun `an album opened on two tracks, written on one, shows on the other with the web's lines and no call`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            val notes = notes()
            // Both opened before either is written: each gathered without the web's lines.
            val first = track(HomeFixtures.sundown, songArticle = true)
            val next = track(HomeFixtures.carefree)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ALBUM))
            assertEquals(ItemShown.Offered, notes.settled(next, InsightItem.ALBUM))

            notes.write(next, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests)

            // Back on the first: the insight written on the next, under the key the web's lines give.
            val shown = notes.settled(first, InsightItem.ALBUM) as ItemShown.Shown
            assertTrue(shown.state is InsightState.Written)
            assertTrue(
                shown.evidence.evidence.lines
                    .any { it.source == SourceId.WEB },
            )
            // Its ids mapped to its own track's lines: the album's lines are none of the first's.
            assertTrue(shown.evidence.trackIds.isEmpty())
            assertTrue(
                notes
                    .albumEvidence(first)
                    .first()
                    ?.lines
                    .orEmpty()
                    .any { it.source == SourceId.WEB },
            )
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests)
            assertEquals(2, albumLookups)
        }

    @Test
    fun `a search paid for with no call after it ends its question with its cost`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            notes().write(track(HomeFixtures.sundown), InsightItem.ALBUM)
            advanceUntilIdle()
            // The web's lines no longer kept, the card still is: searched again, then found kept.
            web.kept = null
            events.clear()
            notes().write(track(HomeFixtures.carefree), InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(2, web.searches)
            assertEquals(1, provider.requests)
            val ended = events.filterIsInstance<PipelineEvent.AiEnded>().single()
            assertEquals(AiOutcome.ANSWERED, ended.outcome)
            assertEquals(QuestionCost(searches = 1), ended.cost)
            assertEquals(1, events.filterIsInstance<PipelineEvent.AiAsked>().size)
        }

    @Test
    fun `web search switched off while the album is gathered makes no search`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            val notes = notes()
            val gate = CompletableDeferred<Unit>()
            gathering = gate
            notes.write(track(HomeFixtures.sundown), InsightItem.ALBUM)
            advanceUntilIdle()
            val now = checkNotNull(settings.value)
            settings.value = now.copy(enabledSources = now.enabledSources - SourceSwitch.WEB)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, web.searches)
            assertEquals(0, paid.searches)
            // The insight is written from what the free sources found.
            assertEquals(1, provider.requests)
        }

    @Test
    fun `an album kept from an earlier search is not counted as a search paid for`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            // Another track's write searched and kept the lines; its card is gone.
            web.searchWeb(AlbumQuery("Sundown", "Gordon Lightfoot", "rg-sundown"))
            val notes = notes()
            notes.write(track(HomeFixtures.sundown), InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, web.searches)
            assertEquals(QuestionCost(calls = 1), paid)
        }

    @Test
    fun `no web search for the album while the coordinator's gate is closed`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            val notes = notes()
            open = false
            notes.write(track(HomeFixtures.sundown), InsightItem.ALBUM, ItemAsk.BY_ITSELF)
            advanceUntilIdle()
            assertEquals(0, web.searches)
            assertEquals(0, provider.requests)
        }

    @Test
    fun `an album opened before Exa's key was given is searched for once it is`() =
        runTest {
            withAlbums(WebAlbums(engine = false))
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ALBUM))

            // The key given: the parts rebuilt with the album's web search; its next write searches.
            val web = WebAlbums()
            withAlbums(web)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ALBUM))
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests)
            assertTrue("Sundown topped the Billboard 200." in provider.prompts.single())
        }

    @Test
    fun `an album with nothing to write from before Exa's key was given is searched for once it is`() =
        runTest {
            withAlbums(WebAlbums(engine = false, free = false))
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            val none = notes.item(first, InsightItem.ALBUM).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.NoEvidence, none.state)
            assertEquals(0, provider.requests)

            // The key given: gathered again, a search due; the earlier "nothing to write from" stands for none.
            val web = WebAlbums(free = false)
            withAlbums(web)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ALBUM))
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests)
            assertTrue((notes.settled(first, InsightItem.ALBUM) as ItemShown.Shown).state is InsightState.Written)
        }

    // An album's write, its search's read of the kept lines held until [then] has changed what it may do.
    private fun TestScope.albumReadHeld(then: () -> Unit): AppItemNotes {
        val web = WebAlbums()
        withAlbums(web)
        val notes = notes()
        val reading = CompletableDeferred<Unit>()
        web.reading = reading
        notes.write(track(HomeFixtures.sundown), InsightItem.ALBUM)
        advanceUntilIdle()
        then()
        reading.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, web.searches)
        assertEquals(0, paid.searches)
        assertEquals(0, provider.requests)
        assertEquals(null, web.kept)
        return notes
    }

    @Test
    fun `the gate closed while the album's kept lines are read makes no search, and the album is offered again`() =
        runTest {
            val notes = albumReadHeld { open = false }
            assertEquals(
                ItemShown.Offered,
                notes.item(track(HomeFixtures.sundown), InsightItem.ALBUM).first { it !is ItemShown.Gathering },
            )
        }

    @Test
    fun `web search switched off while the album's kept lines are read makes no search`() =
        runTest {
            albumReadHeld {
                settings.value = settings.value?.let { it.copy(enabledSources = it.enabledSources - SourceSwitch.WEB) }
            }
        }

    @Test
    fun `another track asking while the album's search keeps its lines makes no second write`() =
        runTest {
            val web = WebAlbums()
            withAlbums(web)
            val notes = notes()
            val returning = CompletableDeferred<Unit>()
            web.returning = returning
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            // The lines kept, the write not yet on their key: the next track gathers them and asks.
            assertEquals(1, web.searches)
            val next = track(HomeFixtures.carefree)
            notes.write(next, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(0, provider.requests)

            returning.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertEquals(1, web.searches)
            assertEquals(QuestionCost(searches = 1, calls = 1), paid)
            assertTrue((notes.settled(first, InsightItem.ALBUM) as ItemShown.Shown).state is InsightState.Written)
            assertTrue((notes.settled(next, InsightItem.ALBUM) as ItemShown.Shown).state is InsightState.Written)
            // The first write ends answered, with the search and the call; the next asked for nothing.
            val ended = events.filterIsInstance<PipelineEvent.AiEnded>().single()
            assertEquals(AiOutcome.ANSWERED, ended.outcome)
            assertEquals(QuestionCost(searches = 1, calls = 1), ended.cost)
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
    fun `the same song from another album shows none of the first album's lines`() =
        runTest {
            val notes = notes()
            val original = track(HomeFixtures.sundown)
            notes.item(original, InsightItem.ALBUM).first { it !is ItemShown.Gathering }
            assertTrue(notes.albumEvidence(original).first() != null)
            // The same song, the same lookup key, played from a compilation.
            val compilation = original.copy(nowPlaying = original.nowPlaying.copy(album = "Gord's Gold"))
            assertEquals(original.normalized.lookupKey, compilation.normalized.lookupKey)
            assertEquals(null, notes.albumEvidence(compilation).first())
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
    fun `under On demand an opening by a tap writes, as the tap's consent, and opening again makes no call`() =
        runTest {
            settings.value = Settings(aiProvider = ProviderId.ANTHROPIC, writeAutomatically = WriteAutomatically.NEVER)
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.SONG))
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertTrue(notes.settled(first, InsightItem.SONG) is ItemShown.Shown)
            assertEquals(1, provider.requests)
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            // Nothing asks by itself under On demand.
            notes.write(first, InsightItem.ARTIST, ItemAsk.BY_ITSELF)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `a write whose row is no longer followed goes on, and its insight is kept and shown when followed again`() =
        runTest {
            provider.streaming = 10.seconds
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            // The panel open, then closed while it writes (#948): nothing follows the item meanwhile.
            val watching = launch { notes.item(first, InsightItem.SONG).collect {} }
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceTimeBy(1.seconds)
            watching.cancel()
            advanceUntilIdle()
            assertEquals(1, provider.completed)
            assertEquals(1, cache.held)
            // Opened again: the insight kept, with no call.
            assertTrue(notes.settled(first, InsightItem.SONG) is ItemShown.Shown)
            notes.write(first, InsightItem.SONG, ItemAsk.OPENED)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
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
