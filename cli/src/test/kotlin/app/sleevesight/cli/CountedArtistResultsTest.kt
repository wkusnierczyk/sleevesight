package app.sleevesight.cli

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CountedArtistResultsTest {
    private val lastFm = ArtistPart("lastfm.artist", 1, 1, setOf(SourceId.LASTFM))
    private val wikipedia = ArtistPart("wikipedia.artist", 1, 1, setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA))

    @Test
    fun `each read is counted as a hit or a miss, per part, and the report says so`() =
        runBlocking {
            val counted = CountedArtistResults(MemoryArtistResults())
            counted.get(wikipedia, "a")
            counted.keep(wikipedia, "a", "v")
            counted.get(wikipedia, "a")
            counted.get(wikipedia, "a")
            counted.get(lastFm, "b")

            assertEquals(
                listOf(ArtistCacheCount("lastfm.artist", 0, 1), ArtistCacheCount("wikipedia.artist", 2, 1)),
                counted.counts(),
            )
            assertEquals(
                "Artist cache: lastfm.artist 0 hits, 1 misses; wikipedia.artist 2 hits, 1 misses.",
                Report.artistCacheLine(counted.counts()),
            )
            assertEquals("Artist cache: not read.", Report.artistCacheLine(emptyList()))
        }

    @Test
    fun `it keeps only what its cache keeps, so without one the adapters keep their own in memory`() {
        assertTrue(CountedArtistResults(MemoryArtistResults()).persists)
        assertFalse(CountedArtistResults(ArtistResults.None).persists)
    }
}
