package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.language.Languages
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.fixtures.Fixtures
import app.sleevesight.sources.wikipedia.WikipediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A title that leads with its credited composer (#1179), `Mozart: Piano Sonata No. 16 …`, is searched
 * without the lead by every source the web search, Wikipedia and the song's search reach, and a page on
 * its work counts; any other title, one with a colon that names no credited artist among them, is
 * searched exactly as before. Synthetic Exa results (`fixtures/http/web/README.md`); no Exa call.
 */
class ClassicalSearchTitleTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val filter = RecordingFilter(JvmAccentFolder)
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-11-10T00:00:00Z")
        }

    private val k545 =
        normalizer.normalize(
            "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
        )
    private val starWars = normalizer.normalize("Star Wars: Main Title", "John Williams")
    private val starWarsLso = normalizer.normalize("Star Wars: Main Title", "John Williams, London Symphony Orchestra")

    private fun about(
        track: NormalizedTrack,
        title: String,
        snippet: String,
    ) = filter.isAbout(track, title, snippet, "https://page.example/")

    @Test
    fun `the track web search leaves out the composer's lead and quotes the work`() {
        val request = ExaSearchRequest.of(k545)
        assertEquals(
            "\"Piano Sonata No. 16 in C Major, K. 545\" \"Sonata facile\": II. Andante by Wolfgang Amadeus Mozart, " +
                "Khatia Buniatishvili and Academy of St. Martin in the Fields, song",
            request.query,
        )
        assertEquals(
            "Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante Wolfgang Amadeus Mozart " +
                "Khatia Buniatishvili Academy of St. Martin in the Fields",
            request.contents.highlights.query,
        )
    }

    @Test
    fun `a page on the sonata that names the work and a performer is kept, one on another sonata is not`() =
        runTest {
            val net = ReplayNetClient.recorded("web/exa-$RECORDING.response.json")
            val result =
                ExaWebSearch(
                    net,
                    Secret("k"),
                    JvmAccentFolder,
                    clock,
                ).lookup(k545, CompletableDeferred(Resolution.NONE))
            assertEquals(
                Json.parseToJsonElement(Fixtures.text("http/web/exa-$RECORDING.request.json")),
                Json.parseToJsonElement(
                    net.sent
                        .single()
                        .body!!
                        .text,
                ),
            )
            assertEquals(
                listOf(
                    "https://pianoguide.example/mozart/piano-sonata-16-k545",
                    "https://streaming.example/track/k545-andante-buniatishvili",
                    "https://classicalnotes.example/mozart-sonata-facile",
                ),
                result.evidence.map { it.url },
            )
        }

    @Test
    fun `the filter takes the title as searched or its work, and still wants a credited artist`() {
        assertTrue(
            about(
                k545,
                "Andante",
                "Piano Sonata No. 16 in C major, K. 545 \"Sonata facile\": II. Andante, Khatia Buniatishvili.",
            ),
        )
        assertTrue(about(k545, "Notes", "Khatia Buniatishvili plays the Piano Sonata No. 16 in C major, K.545 slowly."))
        assertFalse(about(k545, "Notes", "The Piano Sonata No. 16 in C major, K. 545, a recording of note."))
        assertFalse(about(k545, "Notes", "Khatia Buniatishvili plays the Piano Sonata No. 11 in A major, K. 331."))
        assertTrue(filter.canCheck(k545))
    }

    @Test
    fun `a title with a colon that names no credited artist is searched as before`() {
        for (track in listOf(starWars, starWarsLso)) {
            assertEquals(track.title, track.searchTitle)
            assertEquals(null, track.searchWork)
            assertEquals(track.lookupKey, track.searchKey)
            // The whole title, as a phrase: not its part before the colon.
            assertTrue(about(track, "Film music", "Star Wars: Main Title, John Williams's fanfare."))
            assertFalse(about(track, "Film music", "Star Wars, John Williams's score for the film."))
        }
        assertEquals("\"Star Wars: Main Title\" by John Williams, song", ExaSearchRequest.of(starWars).query)
        assertEquals(
            "\"Star Wars: Main Title\" by John Williams and London Symphony Orchestra, song",
            ExaSearchRequest.of(starWarsLso).query,
        )
        assertEquals(
            listOf(
                "Star Wars: Main Title (John Williams song)",
                "Star Wars: Main Title (song)",
                "Star Wars: Main Title",
            ),
            WikipediaSource.songTitles(starWars),
        )
    }

    @Test
    fun `Wikipedia's song titles leave out the composer's lead`() {
        val title = "Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
        assertEquals(
            listOf("$title (Wolfgang Amadeus Mozart song)", "$title (song)", title),
            WikipediaSource.songTitles(k545).take(3),
        )
        assertTrue(WikipediaSource.songTitles(k545).none { it.startsWith("Mozart") })
    }

    @Test
    fun `the song's own search and its search in a language leave out the composer's lead`() =
        runTest {
            val net = ReplayNetClient.ok("""{"results":[]}""")
            ExaSongSearch(net, Secret("k"), JvmAccentFolder, { LanguageItems.NONE }, clock).search(SongWebQuery(k545))
            val sent =
                Json
                    .parseToJsonElement(
                        net.sent
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertEquals(
                "\"Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante\" " +
                    "Wolfgang Amadeus Mozart review interview",
                sent["query"]?.jsonPrimitive?.content,
            )
            val portuguese = Languages.ofCode("pt")!!
            val language = ExaLanguageQuery.of(LanguageQuery(k545, Resolution.NONE, portuguese), LanguageItems.NONE)
            assertTrue(language.query.startsWith("\"Piano Sonata No. 16 in C Major"), language.query)
        }

    private companion object {
        const val RECORDING = "1179-k545-andante"
    }
}
