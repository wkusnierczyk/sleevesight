package app.sleevesight.store

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * *Keep what sources said about each artist* (#420) across the two stores that would keep it: the
 * artist-level cache and the retrieval cache, which keeps a track's result with its artist lines.
 */
class ArtistKeepingTest {
    private val fixture = CacheFixture()
    private var keeps = false
    private val keeping = ArtistKeeping { keeps }
    private val artists = StoreArtistResults({ fixture.caches }, EmptyCoroutineContext, keeping)
    private val pipeline = StorePipelineCache(fixture.caches, EmptyCoroutineContext, keeping)
    private val sundown = fixture.normalizer.normalize("Sundown", "Gordon Lightfoot")
    private val carefree = fixture.normalizer.normalize("Carefree Highway", "Gordon Lightfoot")

    @AfterTest
    fun close() = fixture.close()

    /**
     * An adapter as Last.fm's: the artist's bio from the artist cache, else from a request it
     * counts, as an artist-level line of the track's result.
     */
    private inner class BioSource(
        private val kept: ArtistResults,
    ) : SourceAdapter {
        override val id: SourceId = SourceId.LASTFM
        var requests = 0

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            val bio =
                kept.get(PART, "gordon lightfoot")?.value
                    ?: "A Canadian singer-songwriter.".also {
                        requests++
                        kept.keep(PART, "gordon lightfoot", it)
                    }
            return SourceResult(Outcome.ArtistArticle, listOf(line(Scope.ARTIST, bio)), Duration.ZERO)
        }
    }

    // A track's run as the pipeline makes it: the retrieval cache, else the adapter, then kept.
    private suspend fun run(
        adapter: SourceAdapter,
        track: NormalizedTrack,
    ): SourceResult =
        pipeline.result(adapter, track, null)
            ?: adapter
                .lookup(
                    track,
                    CompletableDeferred(Resolution.NONE),
                ).also { pipeline.keep(adapter, track, null, it) }

    private fun line(
        scope: Scope,
        text: String,
    ) = EvidenceDraft(SourceId.LASTFM, scope, text, null, fixture.clock.now)

    private fun stats() = fixture.caches.stats()

    @Test
    fun `off, a second track by the same artist asks again, and no track's result keeps the artist's lines`() =
        runBlocking {
            val source = BioSource(artists)

            run(source, sundown)
            run(source, carefree)
            run(source, sundown)

            assertEquals(3, source.requests, "the artist is asked about on every track, the same one too")
            assertEquals(0, stats().artistResults.entries)
            assertEquals(0, stats().retrieval.entries, "no retrieval entry holds the artist's line")
        }

    @Test
    fun `on, the artist is asked about once, and each track's result is kept`() =
        runBlocking {
            keeps = true
            val source = BioSource(artists)

            run(source, sundown)
            run(source, carefree)
            run(source, sundown)

            assertEquals(1, source.requests)
            assertEquals(1, stats().artistResults.entries)
            assertEquals(2, stats().retrieval.entries)
        }

    @Test
    fun `off, a result with nothing about an artist is kept as before`() =
        runBlocking {
            val song = SourceResult(Outcome.SongArticle, listOf(line(Scope.SONG, "A song.")), Duration.ZERO)
            val adapter = BioSource(artists)

            pipeline.keep(adapter, sundown, null, song)

            assertEquals(song, pipeline.result(adapter, sundown, null))
        }

    @Test
    fun `off, a result kept before is not read, unverified lines and genre labels counting too`() =
        runBlocking {
            keeps = true
            val adapter = BioSource(artists)
            val unverified =
                SourceResult(Outcome.None, emptyList(), Duration.ZERO, unverified = listOf(line(Scope.ARTIST, "Bio.")))
            val genres = SourceResult(Outcome.None, emptyList(), Duration.ZERO, genres = listOf(FOLK))
            pipeline.keep(adapter, sundown, null, unverified)
            pipeline.keep(adapter, carefree, null, genres)

            keeps = false
            assertNull(pipeline.result(adapter, sundown, null))
            assertNull(pipeline.result(adapter, carefree, null))
            keeps = true
            assertNotNull(pipeline.result(adapter, sundown, null), "kept, only not read while off")
        }

    @Test
    fun `clearing deletes the retrieval entries that say something about an artist, and only those`() =
        runBlocking {
            keeps = true
            val adapter = BioSource(artists)
            val song = SourceResult(Outcome.SongArticle, listOf(line(Scope.SONG, QUOTED)), Duration.ZERO)
            val bio = SourceResult(Outcome.ArtistArticle, listOf(line(Scope.ARTIST, "Bio.")), Duration.ZERO)
            val genres = SourceResult(Outcome.None, emptyList(), Duration.ZERO, genres = listOf(FOLK))
            val third = fixture.normalizer.normalize("If You Could Read My Mind", "Gordon Lightfoot")
            pipeline.keep(adapter, sundown, null, song)
            pipeline.keep(adapter, carefree, null, bio)
            pipeline.keep(adapter, third, null, genres)
            artists.keep(PART, "gordon lightfoot", "Bio.")

            keeps = false
            assertTrue(artists.clear())

            keeps = true
            assertEquals(1, stats().retrieval.entries)
            assertEquals(song, pipeline.result(adapter, sundown, null), "a line quoting the marker is not one")
            assertEquals(0, stats().artistResults.entries)
            fixture.assertTotals()
        }

    @Test
    fun `a clear that failed is tried again at the next read while off`() =
        runBlocking {
            keeps = true
            artists.keep(PART, "gordon lightfoot", "Bio.")
            keeps = false
            var failing = true
            val flaky =
                StoreArtistResults(
                    { if (failing) error("the database could not be opened") else fixture.caches },
                    EmptyCoroutineContext,
                    keeping,
                )

            assertFalse(flaky.clear())
            assertEquals(1, stats().artistResults.entries)
            failing = false
            assertNull(flaky.get(PART, "anyone"))

            assertEquals(0, stats().artistResults.entries, "the read cleared what the failed clear left")
        }

    @Test
    fun `the markers are what an encoded result about an artist holds`() {
        val bio = SourceResult(Outcome.ArtistArticle, listOf(line(Scope.ARTIST, "Bio.")), Duration.ZERO)
        val genres = SourceResult(Outcome.None, emptyList(), Duration.ZERO, genres = listOf(FOLK))
        val song = SourceResult(Outcome.SongArticle, listOf(line(Scope.SONG, QUOTED)), Duration.ZERO)

        assertTrue(RetrievalCodec.ARTIST_LINE_MARKER in RetrievalCodec.encode(bio))
        assertTrue(RetrievalCodec.GENRES_MARKER in RetrievalCodec.encode(genres))
        val encoded = RetrievalCodec.encode(song)
        assertFalse(RetrievalCodec.ARTIST_LINE_MARKER in encoded || RetrievalCodec.GENRES_MARKER in encoded)
        assertTrue(RetrievalCodec.aboutArtists(bio) && RetrievalCodec.aboutArtists(genres))
        assertFalse(RetrievalCodec.aboutArtists(song))
    }

    private companion object {
        val PART = ArtistPart("lastfm.artist", 1, 1, setOf(SourceId.LASTFM))
        val FOLK = GenreLabel("folk", "folk", 1, "Folk", "folk")

        // A song line whose text holds both markers, as quoted text.
        const val QUOTED = "It reads \"scope\":\"artist\" and \"genres\":[ in its text."
    }
}
