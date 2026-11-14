package app.sleevesight.sources.wikipedia

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.ComposedDates
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the music was composed, as the infobox of a work's Wikipedia article says it (#1273), for the
 * 20 well-known works sampled on 2026-11-14, from their recorded articles (`fixtures/http/wikipedia-1273/`):
 * the request the song's lead is read with, and what its infobox dates.
 */
class WikipediaInfoboxSampleTest {
    private val client = ReplayNetClient("wikipedia-1273")
    private val wikipedia = WikipediaClient(client)

    // Each sampled work's article, and the years its infobox gives, null for none.
    private val sample =
        mapOf(
            "Piano Sonata No. 16 (Mozart)" to (1788 to 1788),
            "Piano Sonata No. 14 (Beethoven)" to (1801 to 1801),
            "Goldberg Variations" to null,
            "Nocturnes, Op. 9 (Chopin)" to null,
            "Suite bergamasque" to (1890 to 1905),
            "Gymnopédies" to null,
            // Its infobox has a `period` (Romantic), no date.
            "Nessun dorma" to null,
            "The Four Seasons (Vivaldi)" to null,
            "Pachelbel's Canon" to null,
            "Adagio for Strings" to (1936 to 1936),
            "In the Hall of the Mountain King" to null,
            "Adagio in G minor" to null,
            "Ellens dritter Gesang" to null,
            "Enigma Variations" to null,
            "Piano Concerto No. 2 (Rachmaninoff)" to null,
            "Spiegel im Spiegel" to (1978 to 1978),
            "Liebesträume" to null,
            "Pavane (Fauré)" to null,
            "Symphony No. 5 (Beethoven)" to (1804 to 1808),
            "Cello Suites (Bach)" to (1717 to 1723),
        )

    @Test
    fun `the sampled works' infoboxes date seven of the twenty`() =
        runTest {
            for ((title, years) in sample) {
                val lead = (wikipedia.songLead(title) as Lookup.Found).value
                assertTrue(lead?.text.orEmpty().isNotBlank(), title)
                val url = articleUrl(title)
                val expected = years?.let { (from, to) -> ComposedDate("$from", "$to", SourceId.WIKIPEDIA, url) }
                assertEquals(expected, lead?.opening?.let { ComposedDates.ofInfobox(it, url) }, title)
            }
        }

    @Test
    fun `the lead and its section's wikitext come in one request`() =
        runTest {
            wikipedia.songLead("Symphony No. 5 (Beethoven)")
            val query = client.sent.single().query
            assertEquals("extracts|revisions", query["prop"])
            assertEquals("0", query["rvsection"])
            assertEquals("Symphony No. 5 (Beethoven)", query["titles"])
        }
}
