package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.Resolution
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.Fixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Page boilerplate taken out of web lines (#905), on `exa-01-sundown-boilerplate.response.json`:
 * one page of each kind the #767 phone round met on thinly documented tracks (a video site, a
 * streaming service, a store, a chord sheet, a lyrics site, a rights notice), with the sites' fixed
 * text as those pages had it and the project's own words for the rest (`fixtures/http/web/README.md`).
 */
class WebBoilerplateTest {
    private val recording = "web/exa-01-sundown-boilerplate.response.json"
    private val response = ExaWebSearch.JSON.decodeFromString<ExaSearchResponse>(Fixtures.text("http/$recording"))
    private val filter = RecordingFilter(JvmAccentFolder)
    private val subject = listOf("Sundown", "Gordon Lightfoot")

    // The line kept for the fixture's page at [url], empty when none is.
    private fun lineFor(url: String): String =
        ExaWebSearch.lineOf(response.results.single { it.url == url }, subject, filter::fold)

    @Test
    fun `a video page's footer is taken out, and a page with only its title gives no line`() {
        assertEquals("", lineFor("https://videos.example/watch?v=sundown-official"))
    }

    @Test
    fun `a video page's channel stats are taken out and its description kept`() {
        assertEquals(
            "## Description Official audio for Sundown by Gordon Lightfoot, the title track of his 1974 album.",
            lineFor("https://videos.example/watch?v=sundown-1974"),
        )
    }

    @Test
    fun `a streaming page's navigation and sign-in text are taken out`() {
        assertEquals(
            "Song # Sundown Gordon Lightfoot • Sundown• 1974• 3:33 ## Lyrics",
            lineFor("https://streaming.example/track/sundown-lightfoot"),
        )
    }

    @Test
    fun `a store page's rights line, shows and supporters' label are taken out`() {
        assertEquals(
            "### credits from Sundown, released January 1, 1974",
            lineFor("https://store.example/track/sundown"),
        )
    }

    @Test
    fun `a list of user names is taken out`() {
        assertEquals(
            "1. | Sundown 03:33 | ### credits released January 1, 1974",
            lineFor("https://store.example/album/sundown"),
        )
    }

    @Test
    fun `a chord sheet's title alone gives no line`() {
        assertEquals("", lineFor("https://chords.example/tab/gordon-lightfoot/sundown"))
    }

    @Test
    fun `a chord sheet's tuning, capo, chords and practice tip are taken out, its tempo kept`() {
        assertEquals(
            "# Sundown Chords by Gordon Lightfoot Tempo: 112 bpm",
            lineFor("https://chords.example/chords/gordon-lightfoot/sundown"),
        )
    }

    @Test
    fun `chord symbols between lyrics are taken out, an article after them kept`() {
        assertEquals(
            "The evening light along the shore A quiet road back home ... A small town after dark",
            lineFor("https://chords.example/lyrics/gordon-lightfoot/sundown"),
        )
    }

    @Test
    fun `a lyrics site's format and similar-artists lines are taken out, the lyric kept`() {
        assertEquals("The evening light along the shore.", lineFor("https://lyrics.example/gordon-lightfoot/sundown"))
    }

    @Test
    fun `a transcript's markers are taken out`() {
        assertEquals("the evening light along the shore", lineFor("https://videos.example/watch?v=sundown-live"))
    }

    @Test
    fun `a rights notice is taken out, the copyright line naming the label kept`() {
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
                    "https://videos.example/watch?v=sundown-1974",
                    "https://streaming.example/track/sundown-lightfoot",
                    "https://store.example/track/sundown",
                    "https://store.example/album/sundown",
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
    fun `cached lines from before the cleaning are not served`() {
        // The retrieval cache misses an entry kept under another extraction version
        // (`RetrievalCacheTest`); lines kept before #905 were made at version 2.
        val adapter = ExaWebSearch(ReplayNetClient.ok("{}"), Secret("test-exa-key"), JvmAccentFolder)
        assertEquals(3, adapter.extractionVersion)
    }
}
