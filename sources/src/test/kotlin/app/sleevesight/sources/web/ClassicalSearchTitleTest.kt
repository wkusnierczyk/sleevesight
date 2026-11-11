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

    // As the track's own lines are judged (`ExaWebSearch`, `ExaLanguageSearch`, #1195).
    private fun recording(
        track: NormalizedTrack,
        title: String,
        snippet: String,
    ) = filter.isAbout(track, title, snippet, "https://page.example/", recording = true)

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
    fun `the movement follows the quoted work without the colon or comma between them`() {
        val k466 =
            normalizer.normalize(
                "Mozart: Piano Concerto No. 20 in D Minor, K.466: II. Romance",
                "Wolfgang Amadeus Mozart, Khatia Buniatishvili",
            )
        assertEquals(
            "\"Piano Concerto No. 20 in D Minor, K.466\" II. Romance by Wolfgang Amadeus Mozart and " +
                "Khatia Buniatishvili, song",
            ExaSearchRequest.of(k466).query,
        )
        val jupiter =
            normalizer.normalize(
                "Mozart: Symphony No. 41 in C Major, K. 551, \u201CJupiter\u201D: I. Allegro vivace",
                "Wolfgang Amadeus Mozart, Wiener Philharmoniker",
            )
        assertEquals(
            "\"Symphony No. 41 in C Major, K. 551\" \u201CJupiter\u201D: I. Allegro vivace by " +
                "Wolfgang Amadeus Mozart and Wiener Philharmoniker, song",
            ExaSearchRequest.of(jupiter).query,
        )
        // A work too generic to stand in for the track: the title as searched, quoted whole.
        val surprise = normalizer.normalize("Haydn: Symphony \"Surprise\": II. Andante", "Joseph Haydn, Antal Doráti")
        assertEquals(
            "\"Symphony \"Surprise\": II. Andante\" by Joseph Haydn and Antal Doráti, song",
            ExaSearchRequest.of(surprise).query,
        )
    }

    @Test
    fun `a page on the sonata naming a performer is kept, not one naming only the composer or another sonata`() =
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
                // Not `classicalnotes.example`'s page, on the sonata naming only Mozart: it may be about
                // another recording, and the track's own lines are taken as facts about this one (#1195).
                listOf(
                    "https://pianoguide.example/mozart/piano-sonata-16-k545",
                    "https://streaming.example/track/k545-andante-buniatishvili",
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
    fun `the track's own lines want a performer on a page naming only the work, the Song insight's do not (#1195)`() {
        val composerOnly = "Mozart wrote the Piano Sonata No. 16 in C major, K. 545 in 1788; Wolfgang Amadeus Mozart."
        val otherPianist = "Wolfgang Amadeus Mozart's Piano Sonata No. 16 in C major, K. 545, as Pires recorded it."
        val performer = "Khatia Buniatishvili plays the Piano Sonata No. 16 in C major, K.545 slowly."
        val conductor = "The Academy of St. Martin in the Fields and the Piano Sonata No. 16 in C major, K. 545."
        val whole = "Piano Sonata No. 16 in C major, K. 545 \"Sonata facile\": II. Andante, Wolfgang Amadeus Mozart."
        for (snippet in listOf(composerOnly, otherPianist)) {
            assertFalse(recording(k545, "Notes", snippet), snippet)
            assertTrue(about(k545, "Notes", snippet), "the Song insight's, about the work: $snippet")
        }
        for (snippet in listOf(performer, conductor)) assertTrue(recording(k545, "Notes", snippet), snippet)
        // The title as searched, whole, keeps today's rule: any credited artist, the composer too.
        assertTrue(recording(k545, "Notes", whole))
        // What no rule kept before is not kept now: a page on another sonata, one naming no artist.
        assertFalse(recording(k545, "Notes", "Khatia Buniatishvili plays the Piano Sonata No. 11 in A major, K. 331."))
        assertFalse(recording(k545, "Notes", "The Piano Sonata No. 16 in C major, K. 545, a recording of note."))
        // Titled after the composer alone, without the work: dropped, as before.
        assertFalse(recording(k545, "Wolfgang Amadeus Mozart", performer))
    }

    @Test
    fun `a track whose title names no composer is judged the same for its own lines (#1195)`() {
        val sundown = normalizer.normalize("Sundown", "Gordon Lightfoot")
        val pages =
            listOf(
                "Star Wars" to "Star Wars: Main Title, John Williams's fanfare.",
                "Film music" to "Star Wars: Main Title, John Williams's fanfare.",
                "John Williams" to "Star Wars: Main Title, his fanfare.",
                "Film music" to "Star Wars, John Williams's score for the film.",
                "Sundown" to "Gordon Lightfoot's Sundown, a 1974 single.",
                "Gordon Lightfoot" to "Sundown and other songs.",
            )
        for (track in listOf(starWars, starWarsLso, sundown)) {
            for ((title, snippet) in pages) {
                assertEquals(about(track, title, snippet), recording(track, title, snippet), "$title: $snippet")
            }
        }
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
    fun `the song's own search leaves out the composer's lead`() =
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
        }

    @Test
    fun `the song's search in the track's language leaves out the composer's lead`() =
        runTest {
            val portuguese = Languages.ofCode("pt")!!
            val net = ReplayNetClient.ok("""{"results":[]}""")
            ExaSongSearch(net, Secret("k"), JvmAccentFolder, { LanguageItems.NONE }, clock)
                .search(SongWebQuery(k545, portuguese, Resolution.NONE))
            val sent =
                Json
                    .parseToJsonElement(
                        net.sent
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            val title = "Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
            assertEquals(
                "\"$title\" Wolfgang Amadeus Mozart, ${portuguese.song}",
                sent["query"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "$title Wolfgang Amadeus Mozart",
                sent["contents"]
                    ?.jsonObject
                    ?.get("highlights")
                    ?.jsonObject
                    ?.get("query")
                    ?.jsonPrimitive
                    ?.content,
            )
            // The track's own search in the language (`ExaLanguageSearch`) as well.
            val language = ExaLanguageQuery.of(LanguageQuery(k545, Resolution.NONE, portuguese), LanguageItems.NONE)
            assertEquals(
                "\"$title\" Wolfgang Amadeus Mozart Khatia Buniatishvili Academy of St. Martin in the Fields, " +
                    portuguese.song,
                language.query,
            )
        }

    @Test
    fun `a work's German nickname is left out, and a page on the work is kept`() {
        val jupiter =
            normalizer.normalize(
                "Mozart: Sinfonie Nr. 41 in C-Dur, KV 551 \u201EJupiter\u201C: I. Allegro vivace",
                "Wolfgang Amadeus Mozart, Wiener Philharmoniker, Karl B\u00F6hm",
            )
        assertEquals(
            "\"Sinfonie Nr. 41 in C-Dur, KV 551\" \u201EJupiter\u201C: I. Allegro vivace by Wolfgang Amadeus Mozart, " +
                "Wiener Philharmoniker and Karl B\u00F6hm, song",
            ExaSearchRequest.of(jupiter).query,
        )
        assertTrue(about(jupiter, "Mozart-Sinfonien", "Karl B\u00F6hm dirigiert die Sinfonie Nr. 41 in C-Dur, KV 551."))
        assertFalse(
            about(jupiter, "Mozart-Sinfonien", "Karl B\u00F6hm dirigiert die Sinfonie Nr. 40 in g-Moll, KV 550."),
        )
    }

    private companion object {
        const val RECORDING = "1179-k545-andante"
    }
}
