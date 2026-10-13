// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.sources.wikipedia

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck
import io.github.wkusnierczyk.sleevesight.core.resolution.RecordingResolver
import io.github.wkusnierczyk.sleevesight.core.sources.Rejection
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import io.github.wkusnierczyk.sleevesight.sources.RecordedTracks
import io.github.wkusnierczyk.sleevesight.sources.musicbrainz.MusicBrainzClient
import io.github.wkusnierczyk.sleevesight.sources.testing.ReplayNetClient
import io.github.wkusnierczyk.sleevesight.sources.wikidata.WikidataClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
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
    private val adapter =
        WikipediaSource(
            WikipediaClient(net),
            IdentityCheck(WikidataClient(net), RecordedTracks.names),
            RecordedTracks.names,
            clock,
        )

    private suspend fun resolution(n: Int): Resolution =
        resolver.resolve(RecordedTracks.track(n), RecordedTracks.duration(n))

    private suspend fun lookup(n: Int): SourceResult =
        adapter.lookup(RecordedTracks.track(n), CompletableDeferred(resolution(n)))

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
    fun `a quotation is never cut, and one left open is dropped`() {
        assertEquals(
            listOf("Dylan said, \"I like it. I really do.\"", "It charted."),
            WikipediaSource.split("Dylan said, \"I like it. I really do.\" It charted."),
        )
        assertEquals(listOf("It charted."), WikipediaSource.split("It charted. He said, \"No. Never."))
        assertEquals(
            listOf("It came out as a 7\" single.", "It charted."),
            WikipediaSource.split("It came out as a 7\" single. It charted."),
        )
        assertEquals(
            listOf("\"1999\" is a song by American musician Prince.", "It was released in 1982."),
            WikipediaSource.split("\"1999\" is a song by American musician Prince. It was released in 1982."),
        )
        assertEquals(
            listOf("\"Summer of '69\" is a song by Bryan Adams.", "It charted."),
            WikipediaSource.split("\"Summer of '69\" is a song by Bryan Adams. It charted."),
        )
        assertEquals(
            listOf("Prince is a musician.", "His hits include \"1999\" and \"Kiss\".", "He died in 2016."),
            WikipediaSource.split("Prince is a musician. His hits include \"1999\" and \"Kiss\". He died in 2016."),
        )
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
}
