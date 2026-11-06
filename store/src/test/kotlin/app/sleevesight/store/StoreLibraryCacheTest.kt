package app.sleevesight.store

import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * LRCLIB's answer for a track (#997) in the retrieval cache: under the whole query (artist, title,
 * album, length in seconds), each answer, no match included, kept whatever *Keep what sources said
 * about each artist* says.
 */
class StoreLibraryCacheTest {
    private val fixture = CacheFixture()
    private val query = LibraryQuery("Sababa 5", "Nasnusa", "Kokoro", 234)

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `each answer is kept under the whole query, no match included`() =
        runBlocking {
            val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext, ArtistKeeping({ false }) {})
            assertNull(cache.libraryLyrics(query))
            cache.keepLibraryLyrics(query, LibraryLyrics.LYRICS)
            assertEquals(LibraryLyrics.LYRICS, cache.libraryLyrics(query))
            // Another album, none, or another length is another query: not this answer.
            assertNull(cache.libraryLyrics(query.copy(album = "Kokoro (Deluxe)")))
            assertNull(cache.libraryLyrics(query.copy(album = null)))
            assertNull(cache.libraryLyrics(query.copy(seconds = 233)))
            cache.keepLibraryLyrics(query.copy(album = null), LibraryLyrics.NONE)
            assertEquals(LibraryLyrics.NONE, cache.libraryLyrics(query.copy(album = null)))
            assertEquals(LibraryLyrics.LYRICS, cache.libraryLyrics(query))
            cache.keepLibraryLyrics(query, LibraryLyrics.INSTRUMENTAL)
            assertEquals(LibraryLyrics.INSTRUMENTAL, cache.libraryLyrics(query))
            // Spellings the retrieval key folds alike are one query.
            assertEquals(LibraryLyrics.INSTRUMENTAL, cache.libraryLyrics(query.copy(artist = "SABABA 5")))
        }

    @Test
    fun `the length is kept as the query rounds it, so 233_5 s and 234_4 s are one entry and 233_4 s another`() =
        runBlocking {
            val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext, ArtistKeeping.ALWAYS)

            fun at(millis: Long) = query.copy(seconds = LibraryQuery.seconds(millis.milliseconds))
            cache.keepLibraryLyrics(at(233_500), LibraryLyrics.LYRICS)
            assertEquals(LibraryLyrics.LYRICS, cache.libraryLyrics(at(234_499)))
            assertNull(cache.libraryLyrics(at(233_499)))
            assertNull(cache.libraryLyrics(at(234_500)))
        }

    @Test
    fun `the answer survives the retrieval cache's encoding, and an unknown one reads as none`() {
        val result =
            SourceResult(Outcome.None, emptyList(), Duration.ZERO, trackOnly = true, library = LibraryLyrics.LYRICS)
        assertEquals(LibraryLyrics.LYRICS, RetrievalCodec.decode(RetrievalCodec.encode(result))?.library)
        val later = RetrievalCodec.encode(result).replace("\"LYRICS\"", "\"SUNG\"")
        assertNull(RetrievalCodec.decode(later)?.library)
    }
}
