package app.sleevesight.store

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CachesTest {
    private val fixture = CacheFixture()
    private val caches = fixture.caches

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `the default size limit is 50 MB`() {
        assertEquals(50_000_000L, Settings().cacheSizeLimit)
    }

    @Test
    fun `purging deletes the expired entries and keeps the rest`() {
        caches.retrieval.put(SourceId.DISCOGS, "a|x", 1, result())
        caches.retrieval.put(SourceId.MUSICBRAINZ, "a|x", 1, result())
        caches.artists.put("a", ArtistResolution.None)
        caches.synthesis.put(key("a|x"), "text")

        fixture.clock.advance(2.days)
        assertEquals(1, caches.purgeExpired())
        assertEquals(1, caches.stats().retrieval.entries)

        fixture.clock.advance(Settings.DEFAULT_ARTIST_CACHE_LIFETIME)
        assertEquals(2, caches.purgeExpired(), "MusicBrainz and the artist")
        assertEquals(
            CacheStats(TableStats(0, 0), TableStats(0, 0), caches.stats().synthesis, TableStats(0, 0)),
            caches.stats(),
        )
        assertEquals(1, caches.stats().synthesis.entries)
    }

    @Test
    fun `purging deletes the artist-level results past the shortest lifetime of their sources`() {
        caches.artistResults.put(LASTFM_PART, "a", "v")
        caches.artistResults.put(BOTH_PART, "a", "v")
        caches.artistResults.put(MUSICBRAINZ_PART, "a", "v")

        fixture.clock.advance(Settings.DEFAULT_CACHE_LIFETIMES.getValue(SourceId.LASTFM))
        assertEquals(2, caches.purgeExpired(), "Last.fm's, and the one depending on Last.fm and MusicBrainz")
        assertEquals(TableStats(1, caches.stats().artistResults.bytes), caches.stats().artistResults)
        assertNotNull(caches.artistResults.get(MUSICBRAINZ_PART, "a"))

        fixture.clock.advance(Settings.DEFAULT_CACHE_LIFETIMES.getValue(SourceId.MUSICBRAINZ))
        assertEquals(1, caches.purgeExpired())
        assertEquals(TableStats(0, 0), caches.stats().artistResults)
    }

    @Test
    fun `purging deletes the artist-level results of a source that is no longer cached`() {
        caches.artistResults.put(LASTFM_PART, "a", "v")
        caches.artistResults.put(MUSICBRAINZ_PART, "a", "v")
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES - SourceId.LASTFM)

        assertEquals(1, caches.purgeExpired())
        assertNotNull(caches.artistResults.get(MUSICBRAINZ_PART, "a"))
    }

    @Test
    fun `purging deletes the artist-level results of a source the app no longer knows`() {
        caches.artistResults.put(LASTFM_PART, "a", "v")
        caches.artistResults.put(MUSICBRAINZ_PART, "a", "v")
        // As a later version would have written it, with a source this one does not know.
        val now = fixture.clock.now.toEpochMilliseconds()
        caches.queries.artistResultInsert("future.part", "a", " MB ZZ ", 1, "v", now, now, 1)

        assertEquals(1, caches.purgeExpired())
        assertEquals(2, caches.stats().artistResults.entries)
        fixture.assertTotals()
    }

    @Test
    fun `purging deletes the entries of a source that is no longer cached`() {
        caches.retrieval.put(SourceId.LASTFM, "a|x", 1, result())
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES - SourceId.LASTFM)

        assertEquals(1, caches.purgeExpired())
    }

    @Test
    fun `the size is the bytes of the keys and values`() {
        caches.synthesis.put(key("k"), "ab")
        caches.artists.put("ä", ArtistResolution.None)

        val synthesisBytes =
            "insight".length + "k".length + "fp".length + "style".length + "ANTHROPIC".length +
                "model".length + "default".length + 2
        val artistBytes = 2 + "none".length
        assertEquals(TableStats(1, synthesisBytes.toLong()), caches.stats().synthesis)
        assertEquals(TableStats(1, artistBytes.toLong()), caches.stats().artists)
        assertEquals(synthesisBytes.toLong() + artistBytes, caches.sizeBytes())
    }

    @Test
    fun `past the limit the least recently used entries go first`() {
        for (name in listOf("a", "b", "c")) {
            caches.synthesis.put(key(name), "text")
            fixture.clock.advance(1.minutes)
        }
        fixture.settings = Settings(cacheSizeLimit = caches.sizeBytes())
        assertNotNull(caches.synthesis.get(key("a")))
        fixture.clock.advance(1.minutes)

        caches.synthesis.put(key("d"), "text")

        assertNull(caches.synthesis.get(key("b")), "the least recently used")
        for (name in listOf("a", "c", "d")) assertNotNull(caches.synthesis.get(key(name)), name)
        assertTrue(caches.sizeBytes() <= fixture.settings.cacheSizeLimit)
    }

    @Test
    fun `retrieval and synthesis entries are evicted in one order`() {
        caches.retrieval.put(SourceId.MUSICBRAINZ, "old|x", 1, result())
        fixture.clock.advance(1.minutes)
        caches.synthesis.put(key("newer"), "text")
        fixture.clock.advance(1.minutes)
        fixture.settings = Settings(cacheSizeLimit = caches.sizeBytes())

        caches.synthesis.put(key("newest"), "text")

        assertNull(caches.retrieval.get(SourceId.MUSICBRAINZ, "old|x", 1))
        assertNotNull(caches.synthesis.get(key("newer")))
        assertNotNull(caches.synthesis.get(key("newest")))
    }

    @Test
    fun `artist-level results are evicted in one order with retrieval and synthesis entries`() {
        // Larger than the card written below, so evicting it alone makes room.
        caches.artistResults.put(MUSICBRAINZ_PART, "old", "v".repeat(100))
        fixture.clock.advance(1.minutes)
        caches.retrieval.put(SourceId.MUSICBRAINZ, "newer|x", 1, result())
        fixture.clock.advance(1.minutes)
        caches.artistResults.put(MUSICBRAINZ_PART, "newer", "v")
        fixture.clock.advance(1.minutes)
        caches.artists.put("resolution", ArtistResolution.None)
        fixture.clock.advance(1.minutes)
        fixture.settings = Settings(cacheSizeLimit = caches.sizeBytes())

        caches.synthesis.put(key("newest"), "text")

        assertNull(caches.artistResults.get(MUSICBRAINZ_PART, "old"), "the least recently used")
        assertNotNull(caches.retrieval.get(SourceId.MUSICBRAINZ, "newer|x", 1))
        assertNotNull(caches.artistResults.get(MUSICBRAINZ_PART, "newer"))
        assertNotNull(caches.artists.get("resolution"), "an artist resolution goes only after the others")
        fixture.assertTotals()
    }

    @Test
    fun `artist entries are evicted only after the others`() {
        caches.artists.put("oldest", ArtistResolution.None)
        fixture.clock.advance(1.minutes)
        caches.synthesis.put(key("a"), "text")
        fixture.clock.advance(1.minutes)
        fixture.settings = Settings(cacheSizeLimit = caches.sizeBytes())

        caches.synthesis.put(key("b"), "text")

        assertNull(caches.synthesis.get(key("a")))
        assertNotNull(caches.artists.get("oldest"))

        fixture.settings = Settings(cacheSizeLimit = 0)
        caches.purgeExpired()
        assertEquals(0, caches.stats().entries, "with nothing else left, artists go too")
    }

    @Test
    fun `a lowered limit applies when purging`() {
        for (name in listOf("a", "b", "c", "d")) {
            caches.synthesis.put(key(name), "text")
            fixture.clock.advance(1.milliseconds)
        }
        val each = caches.sizeBytes() / 4

        fixture.settings = Settings(cacheSizeLimit = 2 * each)
        assertEquals(2, caches.purgeExpired())

        assertNull(caches.synthesis.get(key("a")))
        assertNull(caches.synthesis.get(key("b")))
        assertNotNull(caches.synthesis.get(key("c")))
        assertNotNull(caches.synthesis.get(key("d")))
    }

    @Test
    fun `an entry larger than the limit is not kept and evicts nothing`() {
        caches.synthesis.put(key("a"), "text")
        caches.artists.put("b", ArtistResolution.None)
        caches.retrieval.put(SourceId.DISCOGS, "c|x", 1, result())
        val before = caches.stats()
        fixture.settings = Settings(cacheSizeLimit = before.bytes + 10)

        val tooLong = "x".repeat(fixture.settings.cacheSizeLimit.toInt())
        assertFalse(caches.synthesis.put(key("big"), tooLong))
        assertFalse(caches.artists.put(tooLong, ArtistResolution.None))
        val big =
            SourceResult(
                Outcome.None,
                emptyList(),
                1.milliseconds,
                rejections = listOf(Rejection(tooLong, "y", Scope.SONG)),
            )
        assertFalse(caches.retrieval.put(SourceId.DISCOGS, "d|x", 1, big))

        assertEquals(before, caches.stats())
        assertNotNull(caches.synthesis.get(key("a")))
    }

    @Test
    fun `the running totals stay exact through every kind of change`() {
        caches.synthesis.put(key("a"), "text")
        caches.synthesis.put(key("a"), "a longer text, replacing")
        caches.artists.put("b", ArtistResolution.None)
        caches.artists.put("b", ArtistResolution.Ambiguous(listOf("x", "y")))
        caches.retrieval.put(SourceId.DISCOGS, "c|x", 1, result())
        caches.retrieval.put(SourceId.DISCOGS, "c|x", 1, result())
        caches.retrieval.put(SourceId.MUSICBRAINZ, "c|x", 1, result())
        caches.artistResults.put(LASTFM_PART, "d", "v")
        caches.artistResults.put(LASTFM_PART, "d", "a longer value, replacing")
        caches.artistResults.put(MUSICBRAINZ_PART, "d", "v")
        fixture.assertTotals()
        assertEquals(2, caches.stats().retrieval.entries)
        assertEquals(2, caches.stats().artistResults.entries)

        assertNull(caches.retrieval.get(SourceId.MUSICBRAINZ, "c|x", 2), "invalidated by version")
        assertNull(caches.artistResults.get(MUSICBRAINZ_PART.copy(encoding = 2), "d"), "invalidated by version")
        fixture.assertTotals()

        fixture.clock.advance(2.days)
        caches.purgeExpired()
        fixture.assertTotals()

        for (name in listOf("e", "f", "g")) caches.synthesis.put(key(name), "text")
        fixture.settings = Settings(cacheSizeLimit = caches.sizeBytes() / 2)
        caches.purgeExpired()
        fixture.assertTotals()
        assertTrue(caches.sizeBytes() <= fixture.settings.cacheSizeLimit)
    }

    private fun result(): SourceResult = SourceResult(Outcome.None, emptyList(), 100.milliseconds)

    private companion object {
        val LASTFM_PART = ArtistPart("test.lastfm", 1, 1, setOf(SourceId.LASTFM))
        val MUSICBRAINZ_PART = ArtistPart("test.musicbrainz", 1, 1, setOf(SourceId.MUSICBRAINZ))
        val BOTH_PART = ArtistPart("test.both", 1, 1, setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ))
    }

    private fun key(entity: String): SynthesisKey =
        SynthesisKey(CardKind.INSIGHT, entity, "fp", 1, "style", ProviderId.ANTHROPIC, "model", false, null)
}
