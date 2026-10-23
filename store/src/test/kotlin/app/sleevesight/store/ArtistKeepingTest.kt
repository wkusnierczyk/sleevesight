package app.sleevesight.store

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * *Keep what sources said about each artist* (#420) across the two stores that would keep it: the
 * artist-level cache and the retrieval cache, which keeps a track's result with what it says about
 * the artists.
 */
class ArtistKeepingTest {
    private val fixture = CacheFixture()
    private var keeps = false
    private var failing = false
    private val keeping =
        ArtistKeeping.over(
            { if (failing) error("the database could not be opened") else fixture.caches },
            EmptyCoroutineContext,
        ) { keeps }
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

    /** An adapter that answers [result] to every lookup, and counts them. */
    private class FixedSource(
        override val id: SourceId,
        private val result: SourceResult,
    ) : SourceAdapter {
        var requests = 0

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult = result.also { requests++ }
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
        source: SourceId = SourceId.LASTFM,
    ) = EvidenceDraft(source, scope, text, null, fixture.clock.now)

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
    fun `off, every kind of result about an artist is asked for again, on it is kept`() =
        runBlocking {
            for ((name, result) in ABOUT_ARTISTS) {
                for (on in listOf(false, true)) {
                    keeps = on
                    val adapter = FixedSource(result.second, result.first)
                    val track = fixture.normalizer.normalize("$name $on", "Gordon Lightfoot")

                    run(adapter, track)
                    run(adapter, track)

                    assertEquals(if (on) 1 else 2, adapter.requests, "$name, switch on: $on")
                }
            }
            assertEquals(ABOUT_ARTISTS.size.toLong(), stats().retrieval.entries, "only those kept while on")
        }

    @Test
    fun `off, a result with nothing about an artist is kept as before, a song's rejection too`() =
        runBlocking {
            for (result in listOf(SONG, SONG_REJECTED)) {
                val adapter = FixedSource(SourceId.WIKIPEDIA, result)
                val track = fixture.normalizer.normalize("${result.rejections.size}", "Gordon Lightfoot")

                run(adapter, track)
                assertEquals(result, run(adapter, track))
                assertEquals(1, adapter.requests)
            }
        }

    @Test
    fun `off, a result about an artist kept before is not read`() =
        runBlocking {
            keeps = true
            for ((name, result) in ABOUT_ARTISTS) {
                pipeline.keep(
                    FixedSource(result.second, result.first),
                    fixture.normalizer.normalize(name, "A"),
                    null,
                    result.first,
                )
            }

            keeps = false
            for ((name, result) in ABOUT_ARTISTS) {
                val track = fixture.normalizer.normalize(name, "A")
                assertNull(pipeline.result(FixedSource(result.second, result.first), track, null), name)
            }
            keeps = true
            val (name, first) = ABOUT_ARTISTS.entries.first()
            assertNotNull(
                pipeline.result(FixedSource(first.second, first.first), fixture.normalizer.normalize(name, "A"), null),
                "kept, only not read while off",
            )
        }

    @Test
    fun `clearing deletes the retrieval entries that say something about an artist, and only those`() =
        runBlocking {
            keeps = true
            for ((name, result) in ABOUT_ARTISTS) {
                pipeline.keep(
                    FixedSource(result.second, result.first),
                    fixture.normalizer.normalize(name, "A"),
                    null,
                    result.first,
                )
            }
            val song = FixedSource(SourceId.WIKIPEDIA, SONG)
            val rejected = FixedSource(SourceId.WIKIPEDIA, SONG_REJECTED)
            pipeline.keep(song, sundown, null, SONG)
            pipeline.keep(rejected, carefree, null, SONG_REJECTED)
            artists.keep(PART, "gordon lightfoot", "Bio.")

            keeps = false
            assertTrue(keeping.clear())

            keeps = true
            assertEquals(2, stats().retrieval.entries)
            assertEquals(SONG, pipeline.result(song, sundown, null), "a line quoting the markers is not one")
            assertEquals(SONG_REJECTED, pipeline.result(rejected, carefree, null))
            assertEquals(0, stats().artistResults.entries)
            fixture.assertTotals()
        }

    @Test
    fun `clearing deletes the retrieval entries of an older encoding, which cannot say what they hold`() {
        fixture.caches.queries.retrievalInsert(
            source = SourceId.LASTFM.tag,
            lookup_key = "old",
            sources = " ${SourceId.LASTFM.tag} ",
            format = (RetrievalCodec.FORMAT - 1).toLong(),
            extraction = 1,
            payload = "{}",
            stored_at = 0,
            used_at = 0,
            size_bytes = 2,
        )

        fixture.caches.clearArtists()

        assertEquals(0, stats().retrieval.entries)
    }

    @Test
    fun `a clear that failed is tried again by an artist-cache read while off`() =
        runBlocking {
            keeps = true
            artists.keep(PART, "gordon lightfoot", "Bio.")
            keeps = false
            failing = true

            assertFalse(keeping.clear())
            assertEquals(1, stats().artistResults.entries)
            failing = false
            assertNull(artists.get(PART, "anyone"))

            assertEquals(0, stats().artistResults.entries, "the read cleared what the failed clear left")
        }

    @Test
    fun `a clear that failed is tried again by a track's read alone while off`() =
        runBlocking {
            keeps = true
            artists.keep(PART, "gordon lightfoot", "Bio.")
            val musicBrainz = ABOUT_ARTISTS.getValue("musicbrainz ids")
            pipeline.keep(FixedSource(musicBrainz.second, musicBrainz.first), sundown, null, musicBrainz.first)
            keeps = false
            failing = true

            assertFalse(keeping.clear())
            failing = false
            assertNull(pipeline.result(FixedSource(SourceId.MUSICBRAINZ, SONG), carefree, null))

            assertEquals(0, stats().artistResults.entries)
            assertEquals(0, stats().retrieval.entries)
        }

    @Test
    fun `a track's result being kept when the switch turns off is cleared, never kept after the clear`() =
        runBlocking {
            keeps = true
            val adapter = BioSource(ArtistResults.None)
            // The switch turns off, and its clear is called, while the track's result is being
            // written: the clear runs at once up to its first suspension, which waits for the write.
            fixture.driver.before("INSERT INTO retrieval") {
                keeps = false
                launch(start = CoroutineStart.UNDISPATCHED) { keeping.clear() }
            }

            run(adapter, sundown)
            yield()

            assertEquals(0, stats().retrieval.entries)
        }

    @Test
    fun `the markers are what an encoded result about an artist holds, and nothing else's`() {
        val encoded = ABOUT_ARTISTS.mapValues { RetrievalCodec.encode(it.value.first) }
        val markers =
            listOf(
                RetrievalCodec.ARTIST_SCOPE_MARKER,
                RetrievalCodec.GENRES_MARKER,
                RetrievalCodec.LABEL_ARTISTS_MARKER,
            )

        for ((name, payload) in encoded) assertTrue(markers.any { it in payload }, name)
        for (other in listOf(SONG, SONG_REJECTED)) {
            val payload = RetrievalCodec.encode(other)
            assertFalse(markers.any { it in payload }, payload)
            assertFalse(RetrievalCodec.aboutArtists(other))
        }
        assertTrue(ABOUT_ARTISTS.values.all { RetrievalCodec.aboutArtists(it.first) })
    }

    @Test
    fun `a rejection's scope survives the encoding`() {
        val result = ABOUT_ARTISTS.getValue("wikipedia mixed").first
        assertEquals(result, RetrievalCodec.decode(RetrievalCodec.encode(result)))
        assertEquals(SONG_REJECTED, RetrievalCodec.decode(RetrievalCodec.encode(SONG_REJECTED)))
    }

    private companion object {
        val PART = ArtistPart("lastfm.artist", 1, 1, setOf(SourceId.LASTFM))
        val AT: Instant = Instant.parse("2026-10-14T12:00:00Z")

        // A song line whose text holds every marker, as quoted text.
        const val QUOTED = "It reads \"scope\":\"artist\", \"genres\":[ and \"labelArtists\":[ in its text."

        fun draft(
            source: SourceId,
            scope: Scope,
            text: String,
        ) = EvidenceDraft(source, scope, text, null, AT)

        val SONG =
            SourceResult(Outcome.SongArticle, listOf(draft(SourceId.WIKIPEDIA, Scope.SONG, QUOTED)), Duration.ZERO)

        // Wikipedia's song article found, after a song candidate was rejected: about the song only.
        val SONG_REJECTED =
            SONG.copy(rejections = listOf(Rejection("Sundown (album)", "an album, not a song", Scope.SONG)))

        /** The results that say something about an artist, by what they hold, with their source. */
        val ABOUT_ARTISTS: Map<String, Pair<SourceResult, SourceId>> =
            mapOf(
                "artist line" to
                    (
                        SourceResult(
                            Outcome.ArtistArticle,
                            listOf(draft(SourceId.LASTFM, Scope.ARTIST, "Bio.")),
                            Duration.ZERO,
                        ) to SourceId.LASTFM
                    ),
                "unverified artist line" to
                    (
                        SourceResult(
                            Outcome.None,
                            emptyList(),
                            Duration.ZERO,
                            unverified = listOf(draft(SourceId.LASTFM, Scope.ARTIST, "Bio.")),
                        ) to SourceId.LASTFM
                    ),
                "genres" to
                    (
                        SourceResult(
                            Outcome.None,
                            emptyList(),
                            Duration.ZERO,
                            genres = listOf(GenreLabel("folk", "folk", 1, "Folk", "folk")),
                        ) to SourceId.LASTFM
                    ),
                // Last.fm's artist is another MusicBrainz artist's: a rejection and nothing else.
                "lastfm rejection only" to
                    (
                        SourceResult(
                            Outcome.Rejected("a different MusicBrainz artist"),
                            emptyList(),
                            Duration.ZERO,
                            rejections = listOf(Rejection("Jessiah", "a different MusicBrainz artist", Scope.ARTIST)),
                        ) to SourceId.LASTFM
                    ),
                "musicbrainz ids" to
                    (
                        SourceResult(
                            Outcome.SongArticle,
                            listOf(draft(SourceId.MUSICBRAINZ, Scope.SONG, "Released in 1974.")),
                            Duration.ZERO,
                            labelArtists = listOf("mbid-gordon"),
                        ) to SourceId.MUSICBRAINZ
                    ),
                // The song article accepted, the artist article rejected.
                "wikipedia mixed" to
                    (
                        SONG.copy(
                            rejections =
                                listOf(
                                    Rejection("Sundown (album)", "an album, not a song", Scope.SONG),
                                    Rejection("Gordon Lightfoot", "another Gordon Lightfoot", Scope.ARTIST),
                                ),
                        ) to SourceId.WIKIPEDIA
                    ),
            )
    }
}
