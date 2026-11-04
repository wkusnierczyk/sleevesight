package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Page boilerplate taken out of web lines (#905), on `exa-01-sundown-boilerplate.response.json`:
 * one page of each kind the #767 phone round met on thinly documented tracks (a video site, a
 * streaming service, a store, a chord sheet, a lyrics site, a rights notice), on its site's host,
 * with the sites' fixed text as those pages had it and the project's own words for the rest
 * (`fixtures/http/web/README.md`); and the facts in prose that look like boilerplate, which stay.
 * A part goes whole or stays whole: nothing is cut out of one.
 */
class WebBoilerplateTest {
    private val recording = "web/exa-01-sundown-boilerplate.response.json"
    private val response = ExaWebSearch.JSON.decodeFromString<ExaSearchResponse>(Fixtures.text("http/$recording"))
    private val filter = RecordingFilter(JvmAccentFolder)
    private val subject = listOf("Sundown", "Gordon Lightfoot")

    // The line kept for the fixture's page at [url], empty when none is.
    private fun lineFor(url: String): String =
        ExaWebSearch.lineOf(response.results.single { it.url == url }, subject, filter::fold)

    // The line kept for a page at [url] with [highlights].
    private fun lineOf(
        url: String,
        vararg highlights: String,
    ): String =
        ExaWebSearch.lineOf(ExaResult(url, "Sundown - Gordon Lightfoot", highlights.toList()), subject, filter::fold)

    // A page of each kind with rules of its own, and one with none.
    private val sites =
        listOf(
            "https://www.youtube.com/watch?v=fixture",
            "https://open.spotify.com/track/fixture",
            "https://sundown-fixture.bandcamp.com/track/sundown",
            "https://tabs.ultimate-guitar.com/tab/fixture",
            "https://www.letras.com/fixture/",
            "https://records.example/sundown",
        )

    // Asserts that [highlight] comes out as it went in, on every kind of site.
    private fun assertKeptEverywhere(highlight: String) {
        for (url in sites) assertEquals(highlight, lineOf(url, highlight), url)
    }

    @Test
    fun `a video page with only its title and footer gives no line`() {
        assertEquals("", lineFor("https://www.youtube.com/watch?v=sundown-official"))
    }

    @Test
    fun `a video page's part of channel stats goes, its description stays`() {
        assertEquals(
            "## Description Official audio for Sundown by Gordon Lightfoot, the title track of his 1974 album.",
            lineFor("https://www.youtube.com/watch?v=sundown-1974"),
        )
    }

    @Test
    fun `a streaming page's navigation goes, and a part with a fact stays whole, sign-in text and all`() {
        assertEquals(
            "Song # Sundown Gordon Lightfoot • Sundown• 1974• 3:33 ## Lyrics " +
                "Sign in to see lyrics and listen to the full track",
            lineFor("https://open.spotify.com/track/sundown-fixture"),
        )
    }

    @Test
    fun `a store page's parts of its title, rights line and shows go`() {
        assertEquals(
            "### credits from Sundown, released January 1, 1974",
            lineFor("https://sundown-fixture.bandcamp.com/track/sundown"),
        )
    }

    @Test
    fun `a part of supporters' user names goes`() {
        assertEquals(
            "1. | Sundown 03:33 | ### credits released January 1, 1974",
            lineFor("https://sundown-fixture.bandcamp.com/album/sundown"),
        )
    }

    @Test
    fun `a chord sheet's title alone gives no line`() {
        assertEquals("", lineFor("https://tabs.ultimate-guitar.com/tab/fixture/sundown-chords"))
    }

    @Test
    fun `a chord sheet's parts of fields and a practice tip go, its tempo stays`() {
        assertEquals(
            "# Sundown Chords by Gordon Lightfoot Tempo: 112 bpm",
            lineFor("https://chordu.com/chords-tabs-sundown-fixture"),
        )
    }

    @Test
    fun `a part of chords alone goes, and lyrics with chords between them stay whole`() {
        assertEquals(
            "\u200B Am G \u200BThe evening light along the shore \u200B Fmaj7 C \u200BA quiet road back home",
            lineFor("https://www.cifraclub.com.br/fixture/sundown/"),
        )
    }

    @Test
    fun `a lyrics site's parts of format, similar artists and its fixed sentence go, the lyric stays`() {
        assertEquals("The evening light along the shore.", lineFor("https://lyrics.example/gordon-lightfoot/sundown"))
    }

    @Test
    fun `a part of transcript markers alone goes, a lyric with them stays whole`() {
        assertEquals(
            "the evening light [Music] ♪ along the shore ♪",
            lineFor("https://www.youtube.com/watch?v=sundown-live"),
        )
    }

    @Test
    fun `a part that is only a rights line goes, the copyright line naming the label stays`() {
        assertEquals(
            "Sundown, Gordon Lightfoot, 1974. © 1974 Reprise Records.",
            lineFor("https://records.example/sundown"),
        )
    }

    @Test
    fun `a page with a fact and no boilerplate is kept as it came`() {
        assertEquals(
            "Gordon Lightfoot wrote Sundown at a farm he rented north of Toronto.",
            lineFor("https://songnotes.example/gordon-lightfoot/sundown"),
        )
    }

    @Test
    fun `pages that are only boilerplate give no line, and the next pages take their places`() =
        runTest {
            val track = BelowSoundtrack.track(1).let { Normalizer(JvmAccentFolder).normalize(it.title, it.artist) }
            val result =
                ExaWebSearch(ReplayNetClient.recorded(recording), Secret("test-exa-key"), JvmAccentFolder)
                    .lookup(track, CompletableDeferred(Resolution.NONE))
            assertEquals(
                listOf(
                    "https://songnotes.example/gordon-lightfoot/sundown",
                    "https://www.youtube.com/watch?v=sundown-1974",
                    "https://open.spotify.com/track/sundown-fixture",
                    "https://sundown-fixture.bandcamp.com/track/sundown",
                    "https://sundown-fixture.bandcamp.com/album/sundown",
                ),
                result.evidence.map { it.url },
            )
            assertEquals(result.evidence.map { lineFor(it.url!!) }, result.evidence.map { it.text })
        }

    @Test
    fun `an album's pages lose their boilerplate too`() =
        runTest {
            val found =
                ExaAlbumSearch(ReplayNetClient.recorded(recording), Secret("test-exa-key"), JvmAccentFolder)
                    .search("Sundown", "Gordon Lightfoot")
            val lines = assertIs<Lookup.Found<List<EvidenceDraft>>>(found).value
            assertEquals("https://songnotes.example/gordon-lightfoot/sundown", lines.first().url)
            assertEquals(lines.map { lineFor(it.url!!) }, lines.map { it.text })
        }

    @Test
    fun `a site's page text counts as boilerplate only on its own site`() {
        val fields = "Chords used: E A B7 C#m Tuning: Standard Tuning (EADGBE) Capo:+0fret"
        assertEquals(fields, lineOf("https://lyrics.example/sundown", fields))
        assertEquals("", lineOf("https://chordu.com/fixture", fields))
    }

    @Test
    fun `a description after a video's stats in one part is kept whole`() {
        assertKeptEverywhere("- Likes: 98K ## Description Gordon Lightfoot recorded Sundown in 1974.")
    }

    @Test
    fun `credits naming the subject are kept in any form and case`() {
        assertKeptEverywhere("Music by Gordon Lightfoot. ... Lyrics by Gordon Lightfoot.")
        assertKeptEverywhere("Music: gordon lightfoot.")
    }

    @Test
    fun `chords named in a sentence are kept whole, on a chord site too`() {
        assertKeptEverywhere("The progression in Sundown by Gordon Lightfoot is Am G D.")
        assertKeptEverywhere("La progression de Sundown par Gordon Lightfoot est Am G D.")
        assertEquals(
            "The progression in Sundown by Gordon Lightfoot is Am G D.",
            lineOf("https://chordu.com/fixture", "The progression in Sundown by Gordon Lightfoot is Am G D."),
        )
    }

    @Test
    fun `prose naming artists with digits in their names is kept`() {
        assertKeptEverywhere("The artist lists U2 and UB40 as influences on this recording.")
    }

    @Test
    fun `a lyric with chord names in it is kept, off a chord site and on Bandcamp`() {
        val lyric = "Fmaj7 the evening light along the shore Em7 a quiet road back home tonight"
        for (url in sites - "https://tabs.ultimate-guitar.com/tab/fixture") assertEquals(lyric, lineOf(url, lyric), url)
    }

    @Test
    fun `chord names in prose are kept`() {
        assertKeptEverywhere("The verse alternates Am G throughout the recording.")
        assertKeptEverywhere("Le couplet alterne Am G tout au long de l'enregistrement.")
    }

    @Test
    fun `credits are kept, the subject's name in them too`() {
        assertKeptEverywhere("Music: Gordon Lightfoot. ... Lyrics: Gordon Lightfoot.")
        assertKeptEverywhere("Sundown, written by Gordon Lightfoot")
    }

    @Test
    fun `a part with a number is kept`() {
        assertKeptEverywhere("Gordon Lightfoot recorded Sundown at Eastern Sound ... 1974. ... © 1974")
    }

    @Test
    fun `the words of a rights notice in a title or a lyric are kept`() {
        assertKeptEverywhere("Their follow-up single was All Rights Reserved.")
        assertKeptEverywhere("We sang all rights reserved for the ones who stay")
    }

    @Test
    fun `a long highlight is cleaned without exhausting the stack`() {
        val long = "Sundown by Gordon Lightfoot, 1974 " + "word ".repeat(10_000)
        val chords = "Sundown by Gordon Lightfoot, 1974 " + "Am G ".repeat(10_000) + "The evening light"
        val address = "Sundown by Gordon Lightfoot, 1974 " + "a.".repeat(10_000) + "com"
        for (url in sites + "https://chordu.com/fixture") {
            for (text in listOf(long, chords, address)) {
                val line = lineOf(url, text)
                assertTrue(line.startsWith("Sundown by Gordon Lightfoot, 1974"), "$url: $line")
                assertTrue(line.length <= ExaWebSearch.LINE_CHARACTERS + 1, url)
            }
        }
    }

    // The track's web search on the one result at [url] titled [title] with [highlights].
    private suspend fun lookUp(
        url: String,
        title: String,
        vararg highlights: String,
    ): SourceResult {
        val track = BelowSoundtrack.track(1).let { Normalizer(JvmAccentFolder).normalize(it.title, it.artist) }
        val body =
            ExaWebSearch.JSON.encodeToString(ExaSearchResponse(listOf(ExaResult(url, title, highlights.toList()))))
        return ExaWebSearch(ReplayNetClient.ok(body), Secret("test-exa-key"), JvmAccentFolder)
            .lookup(track, CompletableDeferred(Resolution.NONE))
    }

    @Test
    fun `a page titled after neither is judged before its parts go, and keeps its fact`() =
        runTest {
            val result =
                lookUp(
                    "https://soundtrack.example/below",
                    "Below (2002) soundtrack",
                    "Sundown - Gordon Lightfoot",
                    "Plays as the crew surfaces; courtesy of Reprise Records",
                )
            assertEquals(
                listOf("Plays as the crew surfaces; courtesy of Reprise Records"),
                result.evidence.map { it.text },
            )
        }

    @Test
    fun `an album's page titled after neither is judged before its parts go too`() =
        runTest {
            val body =
                ExaWebSearch.JSON.encodeToString(
                    ExaSearchResponse(
                        listOf(
                            ExaResult(
                                "https://records.example/listing/1974",
                                "Record shop listing",
                                listOf("Sundown - Gordon Lightfoot", "Pressed on Reprise, in a gatefold sleeve"),
                            ),
                        ),
                    ),
                )
            val found =
                ExaAlbumSearch(ReplayNetClient.ok(body), Secret("test-exa-key"), JvmAccentFolder)
                    .search("Sundown", "Gordon Lightfoot")
            val lines = assertIs<Lookup.Found<List<EvidenceDraft>>>(found).value
            assertEquals(listOf("Pressed on Reprise, in a gatefold sleeve"), lines.map { it.text })
        }

    @Test
    fun `a lyrics page with only its title gives no line, its address kept for the lyrics link`() =
        runTest {
            val genius = "https://genius.com/Gordon-lightfoot-sundown-lyrics"
            val title = "Gordon Lightfoot – Sundown Lyrics | Genius Lyrics"
            val result = lookUp(genius, title, title)
            assertTrue(result.evidence.isEmpty())
            assertEquals(listOf(genius), result.pages)
            // About the recording, but nothing to say: nothing found, not a rejection.
            assertEquals(Outcome.None, result.outcome)
        }

    @Test
    fun `release facts naming a single or an album are kept`() {
        assertKeptEverywhere("Sundown is a single by Gordon Lightfoot.")
        assertKeptEverywhere("Sundown is track one of the album Sundown by Gordon Lightfoot.")
        assertKeptEverywhere("Album: Sundown")
    }

    @Test
    fun `a fact after a practice tip or a shows list keeps its part`() {
        val tip =
            "Sundown chords, your practice should emphasize these chord progressions: E, A and D. " +
                "Lightfoot recorded it at Eastern Sound."
        assertEquals(tip, lineOf("https://chordu.com/fixture", tip))
        val shows = "### shows - Jun 12 Riverside Hall Orillia, ON. Recorded at Eastern Sound."
        assertEquals(shows, lineOf("https://sundown-fixture.bandcamp.com/track/sundown", shows))
    }

    @Test
    fun `cached lines from before the cleaning are not served`() {
        // The retrieval cache misses an entry kept under another extraction version
        // (`RetrievalCacheTest`); lines kept before #905 were made at version 2.
        val adapter = ExaWebSearch(ReplayNetClient.ok("{}"), Secret("test-exa-key"), JvmAccentFolder)
        assertEquals(3, adapter.extractionVersion)
    }
}
