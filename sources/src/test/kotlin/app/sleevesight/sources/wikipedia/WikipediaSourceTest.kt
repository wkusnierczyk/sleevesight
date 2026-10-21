// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.sources.wikipedia

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.testing.MemoryArtistResults
import app.sleevesight.fixtures.RegressionCase
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.musicbrainz.MusicBrainzClient
import app.sleevesight.sources.testing.ReplayNetClient
import app.sleevesight.sources.wikidata.WikidataClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The Wikipedia adapter against recorded MusicBrainz, Wikidata and Wikipedia responses. */
class WikipediaSourceTest {
    private val net = ReplayNetClient("musicbrainz", "wikidata", "wikipedia")
    private val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)
    private val at = Instant.parse("2026-10-14T00:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = at
        }
    private val adapter = adapter()

    private fun adapter(
        kept: ArtistResults = ArtistResults.None,
        net: NetClient = this.net,
    ): WikipediaSource =
        WikipediaSource(
            WikipediaClient(net),
            IdentityCheck(WikidataClient(net), RecordedTracks.names),
            RecordedTracks.names,
            clock,
            artists = kept,
        )

    private suspend fun resolution(n: Int): Resolution =
        resolver.resolve(RecordedTracks.track(n), RecordedTracks.duration(n))

    private suspend fun lookup(
        n: Int,
        on: WikipediaSource = adapter,
    ): SourceResult = on.lookup(RecordedTracks.track(n), CompletableDeferred(resolution(n)))

    @Test
    fun `a second track by the same artist asks nothing about the artist while the artist cache has it`() =
        runTest {
            val kept = MemoryArtistResults(clock, 7.days)
            val sundown = RegressionCase.RICH_DATA.tracks.single()
            val first = lookup(sundown, adapter(kept))
            assertEquals(setOf("$GORDON_LIGHTFOOT Gordon Lightfoot"), kept.keys(WikipediaSource.ARTIST_PART))

            // Another song of his, which the recordings do not hold: its articles by name are none.
            val carefree = RecordedTracks.track(sundown).copy(title = "Carefree Highway")
            val gordon = Resolution(artists = listOf(ResolvedArtist("Gordon Lightfoot", GORDON_LIGHTFOOT)))
            val cached = NoSongArticle(net, "Carefree Highway")
            val result = adapter(kept, cached).lookup(carefree, CompletableDeferred(gordon))
            val fresh = NoSongArticle(net, "Carefree Highway")
            val asked = adapter(net = fresh).lookup(carefree, CompletableDeferred(gordon))

            assertEquals(1, cached.sent.size, "only the lookup by name: ${cached.sent.map { it.query }}")
            assertTrue(fresh.sent.size > 1, "without the cache, the artist's item and article are asked for")
            assertEquals(Outcome.ArtistArticle, result.outcome)
            assertEquals(asked.outcome, result.outcome)
            assertEquals(asked.evidence, result.evidence)
            val artistLines = first.evidence.filter { it.scope == Scope.ARTIST }
            assertTrue(artistLines.isNotEmpty())
            assertEquals(artistLines, result.evidence)
            // Kept until a week after `at`, so the retrieval cache keeps the result no longer.
            assertEquals(at + 7.days, result.freshUntil)
            assertEquals(null, asked.freshUntil)
            assertEquals(null, first.freshUntil)
        }

    @Test
    fun `the kept part's version follows the adapter's extraction version`() {
        assertEquals(adapter.extractionVersion, WikipediaSource.ARTIST_PART.extraction)
    }

    @Test
    fun `an artist without a MusicBrainz id is not kept`() =
        runTest {
            val kept = MemoryArtistResults()
            // Peleboy and Reem have no MusicBrainz id in the recorded resolutions.
            for (n in listOf(8, 12)) lookup(n, adapter(kept))

            assertEquals(emptySet(), kept.keys(WikipediaSource.ARTIST_PART))
        }

    @Test
    fun `an artist whose article could not be read is not kept`() =
        runTest {
            val kept = MemoryArtistResults()
            val sundown = RegressionCase.RICH_DATA.tracks.single()
            val resolved = resolution(sundown)
            // The artist's lead, and only it, fails.
            val down = NoSongArticle(net, failing = { it.query["titles"] == "Gordon Lightfoot" })

            val result = adapter(kept, down).lookup(RecordedTracks.track(sundown), CompletableDeferred(resolved))

            assertEquals(Outcome.SongArticle, result.outcome)
            assertEquals(emptySet(), kept.keys(WikipediaSource.ARTIST_PART))
        }

    @Test
    fun `rich data - song and artist articles, quoted verbatim and few, with Wikidata's lines`() =
        runTest {
            val result = lookup(RegressionCase.RICH_DATA.tracks.single())
            assertEquals(Outcome.SongArticle, result.outcome)
            val song = result.evidence.filter { it.source == SourceId.WIKIPEDIA && it.scope == Scope.SONG }
            assertEquals(
                "\"Sundown\" is a song by Canadian folk artist Gordon Lightfoot, from the titular album, " +
                    "released as a single in March 1974.",
                song.first().text,
            )
            assertTrue(
                song.all {
                    it.url == "https://en.wikipedia.org/wiki/Sundown_(Gordon_Lightfoot_song)" &&
                        "Sundown" in it.text
                },
            )
            assertTrue(song.size <= WikipediaSource.MAX_SENTENCES)
            assertTrue(
                result.evidence.any {
                    it.source == SourceId.WIKIDATA &&
                        it.text == "“Sundown” was first published on 25 March 1974."
                },
            )
            val artist =
                result.evidence
                    .filter {
                        it.scope == Scope.ARTIST && it.source == SourceId.WIKIPEDIA
                    }.map { it.text }
            assertEquals(
                "Gordon Meredith Lightfoot Jr. (November 17, 1938 – May 1, 2023) was a Canadian singer-songwriter " +
                    "who achieved worldwide success and helped define the singer-songwriter era of the 1970s.",
                artist.first(),
            )
            assertTrue(artist.all { it.count { c -> c == '"' } % 2 == 0 }, "$artist")
            assertEquals(emptyList(), result.rejections)
            assertEquals(emptyList(), result.unverified)
        }

    @Test
    fun `an artist's own song whose item names only later performers is scoped as the track's song`() =
        runTest {
            // Chuck Berry's "Roll Over Beethoven": Wikidata names him as its writer only (#76).
            val result = lookup(55)
            assertEquals(Outcome.SongArticle, result.outcome)
            val song = result.evidence.filter { it.url == "https://en.wikipedia.org/wiki/Roll_Over_Beethoven" }
            assertTrue(song.isNotEmpty(), "${result.evidence}")
            assertTrue(song.all { it.scope == Scope.SONG }, "$song")
            assertTrue(result.evidence.none { it.scope == Scope.ORIGINAL_SONG }, "${result.evidence}")
        }

    @Test
    fun `same-name artist - the wrong Reem is rejected, with no lines`() =
        runTest {
            for (n in RegressionCase.SAME_NAME_ARTIST.tracks) {
                val result = lookup(n)
                assertTrue(result.outcome is Outcome.Rejected, "track $n: ${result.outcome}")
                assertEquals(emptyList(), result.evidence)
                assertTrue(
                    Rejection("Reem", "“Reem” is a disambiguation page") in result.rejections,
                    "${result.rejections}",
                )
            }
        }

    @Test
    fun `a same-title song by someone else is a collision, rejected`() =
        runTest {
            // Peleboy's "We In Here" is not DMX's song of that name.
            val result = lookup(8)
            assertTrue(result.outcome is Outcome.Rejected, "${result.outcome}")
            assertEquals(emptyList(), result.evidence)
        }

    @Test
    fun `covers - without MusicBrainz's cover detection the original's article gives no lines`() =
        runTest {
            val (follow, sun, interstellar) = RegressionCase.COVERS.tracks
            assertEquals(
                Outcome.Rejected("“I Will Follow You into the Dark” is by Death Cab for Cutie, not by Yanjoco"),
                lookup(follow).outcome,
            )
            assertTrue(lookup(sun).outcome is Outcome.Rejected)
            assertEquals(Outcome.None, lookup(interstellar).outcome)
            for (n in RegressionCase.COVERS.tracks) assertEquals(emptyList(), lookup(n).evidence, "track $n")
        }

    @Test
    fun `featured artist - Them's song and article, and Van Morrison, unresolved, rejected rather than guessed`() =
        runTest {
            val result = lookup(RegressionCase.FEATURED_ARTIST.tracks.single())
            assertEquals(Outcome.SongArticle, result.outcome)
            assertEquals(listOf("Van Morrison"), result.rejections.map { it.subject })
            assertTrue(
                result.evidence.any {
                    it.scope == Scope.ARTIST &&
                        it.text.startsWith("Them were a Northern Irish rock band")
                },
            )
        }

    @Test
    fun `three credited artists - the anthem is confirmed by its credited composer`() =
        runTest {
            val result = lookup(RegressionCase.THREE_ARTISTS.tracks.single())
            assertEquals(Outcome.SongArticle, result.outcome)
            assertEquals(
                "\"Ode to Newfoundland\" is the official provincial anthem of Newfoundland and Labrador.",
                result.evidence.first().text,
            )
        }

    @Test
    fun `compilation release year - Snowbird keeps its sentences in a paragraph with a nested quotation`() =
        runTest {
            val texts = lookup(36).evidence.map { it.text }
            assertTrue(
                texts.any {
                    it.startsWith("Anne Murray and Gene MacLellan had met while both were regulars") &&
                        it.endsWith("This Way Is My Way, in 1969.")
                },
                "$texts",
            )
        }

    @Test
    fun `nothing documented gives none`() =
        runTest {
            for (n in listOf(23, 30)) assertEquals(Outcome.None, lookup(n).outcome, "track $n")
        }

    @Test
    fun `it looks up by name before the resolution lands, and awaits it only to verify`() =
        runTest {
            val n = RegressionCase.RICH_DATA.tracks.single()
            val resolved = resolution(n)
            val pending = CompletableDeferred<Resolution>()
            val before = net.sent.size
            val result = async { adapter.lookup(RecordedTracks.track(n), pending) }
            yield()
            val asked = net.sent.drop(before)
            assertEquals(listOf("https://en.wikipedia.org/w/api.php"), asked.map { it.url })
            assertFalse(result.isCompleted)
            pending.complete(resolved)
            assertEquals(Outcome.SongArticle, result.await().outcome)
        }

    @Test
    fun `a quotation is never cut, and a piece left open is dropped`() {
        assertEquals(
            listOf("Dylan said, \"I like it. I really do.\"", "It charted."),
            WikipediaSource.split("Dylan said, \"I like it. I really do.\" It charted."),
        )
        assertEquals(listOf("It charted."), WikipediaSource.split("It charted. He said, \"No. Never."))
        // A nested quotation, as in Snowbird's lead, keeps its sentence whole.
        assertEquals(
            listOf("Murray would recall: \"Gene told me he wrote [\"Snowbird\"] in twenty minutes.\"", "It sold well."),
            WikipediaSource.split(
                "Murray would recall: \"Gene told me he wrote [\"Snowbird\"] in twenty minutes.\" It sold well.",
            ),
        )
    }

    @Test
    fun `a paragraph with a closing quote right after a number gives nothing`() {
        val dropped =
            listOf(
                "It came out as a 7\" single. It charted.",
                "Dylan said, \"It came out on a 7\" single. I like it.\" It charted.",
                "Dylan said, \"It came out on a 7\" single. Later we released a 12\" mix.\" It charted.",
                "Foo came out on a 7\" single. Critics called Foo \"a gem. A true gem.\" " +
                    "Foo was also issued as a 12\" single.",
                "He said, \"It was a 7\" single. I loved it.\" Fans bought the 12\" version.",
                "He said, \"It was a 7\" single. I loved it.\"; fans bought the 12\" one.",
                "It came out as a 7½\" single. He said, \"Yes. Sure.\" It charted.",
                "He said, “It was a 7” single. I loved it.” It charted.",
                // The cost: quoted titles ending in a digit go too.
                "\"1999\" is a song by American musician Prince. It was released in 1982.",
                "\"Summer of '69\" is a song by Bryan Adams. It charted.",
                "Prince is a musician. His hits include \"1999\" and \"Kiss\". He died in 2016.",
            )
        for (paragraph in dropped) assertEquals(emptyList(), WikipediaSource.split(paragraph), paragraph)
    }

    @Test
    fun `article addresses are percent-encoded paths`() {
        assertEquals("https://en.wikipedia.org/wiki/Why%3F_(Bronski_Beat_song)", articleUrl("Why? (Bronski Beat song)"))
        assertEquals("https://en.wikipedia.org/wiki/100%25_Pure_Love", articleUrl("100% Pure Love"))
        assertEquals("https://en.wikipedia.org/wiki/Baby,_Please_Don't_Go", articleUrl("Baby, Please Don't Go"))
        assertEquals("https://en.wikipedia.org/wiki/Beyonc%C3%A9", articleUrl("Beyoncé"))
    }

    @Test
    fun `song titles follow Wikipedia's naming and casing`() {
        assertEquals(
            listOf(
                "Here Comes The Sun (Alexander S. song)",
                "Here Comes The Sun (song)",
                "Here Comes The Sun",
                "Here Comes the Sun (Alexander S. song)",
                "Here Comes the Sun (song)",
                "Here Comes the Sun",
            ),
            WikipediaSource.songTitles(RecordedTracks.track(48)),
        )
    }

    /**
     * The recordings, but for a lookup by name whose titles start with [title], which finds no
     * article, and the requests [failing] picks out, which fail. Keeps what it is sent in [sent].
     */
    private class NoSongArticle(
        private val replay: NetClient,
        private val title: String? = null,
        private val failing: (NetRequest) -> Boolean = { false },
    ) : NetClient {
        val sent = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            sent += request
            return when {
                failing(request) -> NetResult.Failure(NetError.Offline("test"))
                title != null && request.query["titles"]?.startsWith(title) == true ->
                    NetResult.Success(NetResponse(200, emptyMap(), """{"query":{"pages":[]}}""".encodeToByteArray()))
                else -> replay.send(request)
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val GORDON_LIGHTFOOT = "9d97b077-b28d-4ba8-a3d9-c71926e3b2b6"
    }
}
