package app.sleevesight.store

import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * LRCLIB's answer for a track (#997) in the retrieval cache: under the track's key and length, each
 * answer, no match included, kept whatever *Keep what sources said about each artist* says.
 */
class StoreLibraryCacheTest {
    private val fixture = CacheFixture()
    private val track = fixture.normalizer.normalize("Nasnusa", "Sababa 5")

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `each answer is kept under the track's key and length, no match included`() =
        runBlocking {
            val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext, ArtistKeeping({ false }) {})
            assertNull(cache.libraryLyrics(track, 234.seconds))
            cache.keepLibraryLyrics(track, 234.seconds, LibraryLyrics.LYRICS)
            assertEquals(LibraryLyrics.LYRICS, cache.libraryLyrics(track, 234.seconds))
            assertNull(cache.libraryLyrics(track, 300.seconds))
            cache.keepLibraryLyrics(track, 300.seconds, LibraryLyrics.NONE)
            assertEquals(LibraryLyrics.NONE, cache.libraryLyrics(track, 300.seconds))
            cache.keepLibraryLyrics(track, 234.seconds, LibraryLyrics.INSTRUMENTAL)
            assertEquals(LibraryLyrics.INSTRUMENTAL, cache.libraryLyrics(track, 234.seconds))
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
