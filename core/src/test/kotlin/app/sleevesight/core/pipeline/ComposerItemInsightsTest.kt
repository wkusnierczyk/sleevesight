package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.ItemPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

/**
 * The composer's insight beside the artist's (#1199): on a track whose roles tell the composer apart,
 * the Composer row is about the composer and the Artist row about the lead performer, each from the
 * lines about them alone; another performer's chip about that performer; the composer's card is the
 * one an artist's insight about the composer has, so it is written once. A track whose roles tell no
 * composer keeps one Artist row, about the primary artist, as before.
 */
class ComposerItemInsightsTest {
    private val items =
        ItemInsights(
            AiAccess(FakeProvider { FakeProvider.done("A line [W1].") }, Secret("k")),
            MemoryCache(),
            JvmHasher,
            NameMatcher(JvmAccentFolder),
        )
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun about(
        artist: String,
        text: String,
    ) = line(SourceId.WIKIPEDIA, text, Scope.ARTIST).copy(about = artist)

    private val lines =
        listOf(
            about(MOZART, "Wolfgang Amadeus Mozart was an Austrian composer."),
            about(KHATIA, "Khatia Buniatishvili is a Georgian-French pianist."),
            about(ACADEMY, "The Academy of St Martin in the Fields is a chamber orchestra."),
        )

    private fun track(
        title: String,
        artists: String,
        drafts: List<EvidenceDraft> = lines,
        roles: ArtistRoles? = null,
    ): TrackState {
        val playing = nowPlaying(title, artists, "Mozart: Piano Concertos Nos. 20 & 23")
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.ArtistArticle, drafts, ZERO), false),
                ),
            resolution = roles?.let { Resolution(recordingMbid = "r", roles = it) },
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
        )
    }

    private val k545 = track(K545, "$MOZART, $KHATIA, $ACADEMY")

    private suspend fun texts(
        item: InsightItem,
        on: TrackState,
    ) = checkNotNull(items.evidence(item, on, settings)).evidence.lines.map { it.text }

    @Test
    fun `the composer's row is about the composer, the artist's about the lead performer, each from their own lines`() =
        runTest {
            assertEquals(MOZART, items.name(InsightItem.COMPOSER, k545))
            assertEquals(KHATIA, items.name(InsightItem.ARTIST, k545))
            assertEquals(CardEntity.Artist(KHATIA, null), items.entity(InsightItem.ARTIST, k545))
            assertEquals(listOf(lines[0].text), texts(InsightItem.COMPOSER, k545))
            assertEquals(listOf(lines[1].text), texts(InsightItem.ARTIST, k545))
            // Another performer's chip: that performer's own lines.
            val academy = InsightItem.named(ACADEMY)
            assertEquals(ACADEMY, items.name(academy, k545))
            assertEquals(listOf(lines[2].text), texts(academy, k545))
        }

    @Test
    fun `the roles MusicBrainz told split a title with no composer lead`() =
        runTest {
            val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA))
            val nessun = track("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA", emptyList(), roles)
            assertEquals(PUCCINI, items.name(InsightItem.COMPOSER, nessun))
            assertEquals(PAVAROTTI, items.name(InsightItem.ARTIST, nessun))
            assertEquals(listOf(MEHTA), items.split(nessun)?.others)
            // Unresolved, nothing tells the roles: one Artist row, about the first credited artist.
            val unknown = track("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA", emptyList())
            assertNull(items.name(InsightItem.COMPOSER, unknown))
            assertNull(items.evidence(InsightItem.COMPOSER, unknown, settings))
            assertEquals(PUCCINI, items.name(InsightItem.ARTIST, unknown))
        }

    @Test
    fun `the lone credited performer's lines leave out the uncredited composer's, which are the composer's`() =
        runTest {
            // #1376: Wikipedia looks up Puccini's article too, on *Nessun Dorma* by Pavarotti alone.
            val drafts =
                listOf(
                    about(PAVAROTTI, "Luciano Pavarotti was an Italian tenor."),
                    about(PUCCINI, "Giacomo Puccini was an Italian composer."),
                )
            val roles = ArtistRoles(emptyList(), listOf(PAVAROTTI), classical = true, uncredited = listOf(PUCCINI))
            val nessun = track("Nessun Dorma", PAVAROTTI, drafts, roles)
            assertEquals(PAVAROTTI, items.name(InsightItem.ARTIST, nessun))
            assertEquals(listOf(drafts[0].text), texts(InsightItem.ARTIST, nessun))
            assertEquals(PUCCINI, items.name(InsightItem.COMPOSER, nessun))
            assertEquals(listOf(drafts[1].text), texts(InsightItem.COMPOSER, nessun))
        }

    @Test
    fun `the composer's card is the one an artist's insight about the composer has, written once`() =
        runTest {
            val composer = checkNotNull(items.evidence(InsightItem.COMPOSER, k545, settings))
            val alone = track("Eine kleine Nachtmusik", MOZART, listOf(lines[0]))
            val artist = checkNotNull(items.evidence(InsightItem.ARTIST, alone, settings))
            assertEquals(
                items.key(InsightItem.ARTIST, alone, artist, settings),
                items.key(InsightItem.COMPOSER, k545, composer, settings),
            )
        }

    // As the app's artist web lookup: what was asked for, a search finding nothing.
    private class Asked : ArtistWebLookup {
        val queries = mutableListOf<ArtistWebQuery>()

        override suspend fun kept(artist: ArtistWebQuery): List<EvidenceDraft>? = null.also { queries += artist }

        override suspend fun search(
            artist: ArtistWebQuery,
            sent: () -> Unit,
            keeping: (List<EvidenceDraft>) -> Unit,
            mayPay: () -> Boolean,
        ): List<EvidenceDraft> = emptyList<EvidenceDraft>().also { queries += artist }

        override suspend fun keep(
            artist: ArtistWebQuery,
            lines: List<EvidenceDraft>,
        ) = Unit

        override suspend fun renew(artist: ArtistWebQuery) = Unit

        override fun pageLine(
            artist: ArtistWebQuery,
            page: WebPage,
        ): String? = null
    }

    @Test
    fun `a replay keys the composer with no id as its first play did, not as the first artist with one`() =
        runTest {
            val web = Asked()
            val searching =
                ItemInsights(
                    AiAccess(FakeProvider { FakeProvider.done("A line [W1].") }, Secret("k")),
                    MemoryCache(),
                    JvmHasher,
                    NameMatcher(JvmAccentFolder),
                    artistWeb = web,
                )
            val webOn =
                Settings(
                    aiProvider = ProviderId.ANTHROPIC,
                    enabledSources = Settings().enabledSources + SourceSwitch.WEB,
                    webEngines = setOf(WebEngine.EXA),
                    aiWebSearch = AiWebSearch.ONCE,
                )
            val roles = ArtistRoles(listOf(MOZART), listOf(KHATIA, ACADEMY), classical = true)
            // Mozart has no MusicBrainz id: the replay's kept artists leave him out.
            val artists =
                listOf(
                    ResolvedArtist(MOZART, null),
                    ResolvedArtist(KHATIA, "mb-khatia"),
                    ResolvedArtist(ACADEMY, "mb-asmf"),
                )
            val base = track(K545, "$MOZART, $KHATIA, $ACADEMY", emptyList())
            val fresh = base.copy(resolution = Resolution(recordingMbid = "r", artists = artists, roles = roles))
            val kept =
                SourceResult(Outcome.SongArticle, emptyList(), ZERO, labelArtists = artists.drop(1), roles = roles)
            val replay = base.copy(sources = base.sources + (SourceId.MUSICBRAINZ to SourceState.Done(kept, true)))
            for (item in listOf(InsightItem.COMPOSER, InsightItem.ARTIST, InsightItem.named(ACADEMY))) {
                assertEquals(searching.entity(item, fresh), searching.entity(item, replay), "$item")
                val first = checkNotNull(searching.evidence(item, fresh, webOn))
                val again = checkNotNull(searching.evidence(item, replay, webOn))
                assertEquals(
                    searching.key(item, fresh, first, webOn),
                    searching.key(item, replay, again, webOn),
                    "$item",
                )
            }
            assertEquals(CardEntity.Artist(MOZART, null), searching.entity(InsightItem.COMPOSER, replay))
            assertEquals(CardEntity.Artist(KHATIA, "mb-khatia"), searching.entity(InsightItem.ARTIST, replay))
            // Each web lookup by its own artist, on the replay as on the first play.
            val mozart = ArtistWebQuery(MOZART, null)
            val khatia = ArtistWebQuery(KHATIA, "mb-khatia")
            val academy = ArtistWebQuery(ACADEMY, "mb-asmf")
            assertEquals(listOf(mozart, mozart, khatia, khatia, academy, academy), web.queries)
        }

    @Test
    fun `a split track's song, its own lines only listing it, reads the composer's and the performers' (#1133)`() =
        runTest {
            // The same track, its roles telling the composer apart or not (as before #1199).
            val split = track(K545, "$MOZART, $KHATIA, $ACADEMY")
            val unsplit =
                split.copy(
                    resolution =
                        Resolution(
                            recordingMbid = "r",
                            roles = ArtistRoles(emptyList(), split.normalized.artists),
                        ),
                )
            assertNull(items.split(unsplit))
            val song = checkNotNull(items.evidence(InsightItem.SONG, split, settings))
            val before = checkNotNull(items.evidence(InsightItem.SONG, unsplit, settings))
            assertEquals(true, song.onlyListings)
            assertEquals(true, song.parts)
            // The composer's for the music, the performers' for this recording.
            assertEquals(
                lines.map { it.text },
                song.evidence.lines
                    .filter { it.scope == Scope.ARTIST }
                    .map { it.text },
            )
            // Unsplit, the first credited artist's, in one part, keyed as before.
            assertEquals(false, before.parts)
            assertEquals(
                listOf(lines[0].text),
                before.evidence.lines
                    .filter { it.scope == Scope.ARTIST }
                    .map { it.text },
            )
            assertEquals(
                ItemPrompt.ONLY_LISTINGS_VERSION,
                items.key(InsightItem.SONG, unsplit, before, settings)?.promptVersion,
            )
            assertEquals(ItemPrompt.PARTS_VERSION, items.key(InsightItem.SONG, split, song, settings)?.promptVersion)
        }

    @Test
    fun `a composer's line with no about, taken by the name it holds, is named the composer's (#1133)`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("A line [W1].") }
            val writing =
                ItemInsights(AiAccess(provider, Secret("k")), MemoryCache(), JvmHasher, NameMatcher(JvmAccentFolder))
            // Kept before #522: the adapter did not say whom it is about, and its text names Mozart.
            val older = line(SourceId.WIKIPEDIA, "Wolfgang Amadeus Mozart was an Austrian composer.", Scope.ARTIST)
            val split = track(K545, "$MOZART, $KHATIA, $ACADEMY", listOf(older, lines[1]))
            val song = checkNotNull(writing.evidence(InsightItem.SONG, split, settings))
            assertEquals(true, song.parts)
            val key = checkNotNull(writing.key(InsightItem.SONG, split, song, settings))
            writing.write(key, InsightItem.SONG, split, song) { _, _ -> }
            val prompt = provider.requests.single().prompt
            assertTrue("(Wikipedia, composer) Wolfgang Amadeus Mozart was an Austrian composer." in prompt, prompt)
            assertTrue("(Wikipedia, performer) Khatia Buniatishvili is a Georgian-French pianist." in prompt, prompt)
        }

    @Test
    fun `a pop track keeps one Artist row about its primary artist`() =
        runTest {
            val pop = track("Somebody That I Used To Know", "Gotye, Kimbra", emptyList())
            assertNull(items.name(InsightItem.COMPOSER, pop))
            assertEquals("Gotye", items.name(InsightItem.ARTIST, pop))
            assertEquals(CardEntity.Artist("Gotye", null), items.entity(InsightItem.ARTIST, pop))
        }

    private companion object {
        const val K545 = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
        const val MOZART = "Wolfgang Amadeus Mozart"
        const val KHATIA = "Khatia Buniatishvili"
        const val ACADEMY = "Academy of St. Martin in the Fields"
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
    }
}
