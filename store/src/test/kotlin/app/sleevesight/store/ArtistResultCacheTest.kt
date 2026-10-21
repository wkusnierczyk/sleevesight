package app.sleevesight.store

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class ArtistResultCacheTest {
    private val fixture = CacheFixture()
    private val results = fixture.caches.artistResults

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `a value comes back as it was kept, by part and artist`() {
        results.put(LASTFM, "gordon lightfoot", "lastfm's")
        results.put(WIKIPEDIA, "gordon lightfoot", "wikipedia's")
        results.put(LASTFM, "reem", "")

        assertEquals("lastfm's", results.get(LASTFM, "gordon lightfoot")?.value)
        assertEquals("wikipedia's", results.get(WIKIPEDIA, "gordon lightfoot")?.value)
        assertEquals("", results.get(LASTFM, "reem")?.value)
        assertNull(results.get(LASTFM, "someone else"))
        assertNull(results.get(LASTFM.copy(name = "other.part"), "gordon lightfoot"))
    }

    @Test
    fun `a value replaces the one kept before`() {
        results.put(LASTFM, "a", "first")
        results.put(LASTFM, "a", "second")

        assertEquals("second", results.get(LASTFM, "a")?.value)
        assertEquals(
            1,
            fixture.caches
                .stats()
                .artistResults.entries,
        )
        fixture.assertTotals()
    }

    @Test
    fun `a value lives for its source's lifetime, read from the settings at each read`() {
        results.put(LASTFM, "a", "v")
        val lifetime = Settings.DEFAULT_CACHE_LIFETIMES.getValue(SourceId.LASTFM)

        fixture.clock.advance(lifetime - 1.milliseconds)
        assertNotNull(results.get(LASTFM, "a"))
        fixture.settings =
            Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.LASTFM to 1.days))
        assertNull(results.get(LASTFM, "a"), "a shortened lifetime applies at once")
        assertEquals(
            0,
            fixture.caches
                .stats()
                .artistResults.entries,
            "and the expired entry is deleted",
        )
    }

    @Test
    fun `a value says when it stops being fresh, under the lifetime at the read`() {
        results.put(LASTFM, "a", "v")
        val keptAt = fixture.clock.now

        assertEquals(keptAt + LASTFM_LIFETIME, results.get(LASTFM, "a")?.freshUntil)
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.LASTFM to 1.days))
        assertEquals(keptAt + 1.days, results.get(LASTFM, "a")?.freshUntil)
    }

    @Test
    fun `a track result built from an almost expired part expires when the part does, not a lifetime later`() {
        results.put(LASTFM, "gordon lightfoot", "v")
        fixture.clock.advance(LASTFM_LIFETIME - 1.hours)
        val part = checkNotNull(results.get(LASTFM, "gordon lightfoot"))
        val retrieval = fixture.caches.retrieval

        // Another track by the artist, built from the kept part an hour before it expires.
        assertTrue(retrieval.put(SourceId.LASTFM, "carefree", 2, track(part.freshUntil)))
        fixture.clock.advance(1.hours - 1.milliseconds)
        assertNotNull(retrieval.get(SourceId.LASTFM, "carefree", 2))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(SourceId.LASTFM, "carefree", 2), "gone with the part, not 7 days on")
    }

    @Test
    fun `a part that lives longer than the track result leaves the result its own lifetime`() {
        results.put(MUSICBRAINZ, "verdict", "OWNED")
        fixture.clock.advance(1.days)
        val part = checkNotNull(results.get(MUSICBRAINZ, "verdict"))
        val retrieval = fixture.caches.retrieval

        val consults = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)
        assertTrue(retrieval.put(SourceId.LASTFM, "carefree", 2, track(part.freshUntil), consults))
        fixture.clock.advance(LASTFM_LIFETIME - 1.milliseconds)
        assertNotNull(retrieval.get(SourceId.LASTFM, "carefree", 2))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(SourceId.LASTFM, "carefree", 2))
    }

    @Test
    fun `a track result whose reused part is no longer fresh is not kept`() {
        val freshUntil = fixture.clock.now

        assertFalse(fixture.caches.retrieval.put(SourceId.LASTFM, "carefree", 2, track(freshUntil)))
        assertNull(fixture.caches.retrieval.get(SourceId.LASTFM, "carefree", 2))
    }

    @Test
    fun `a value depending on two sources lives for the shorter lifetime`() {
        results.put(WIKIPEDIA, "a", "v")
        fixture.settings =
            Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WIKIDATA to 2.days))

        fixture.clock.advance(2.days - 1.milliseconds)
        assertNotNull(results.get(WIKIPEDIA, "a"))
        fixture.clock.advance(1.milliseconds)
        assertNull(results.get(WIKIPEDIA, "a"))
    }

    @Test
    fun `a value kept under another version of its part is a miss, and deleted`() {
        results.put(LASTFM, "a", "v")

        assertNull(results.get(LASTFM.copy(encoding = 2), "a"))
        assertEquals(
            0,
            fixture.caches
                .stats()
                .artistResults.entries,
        )
        fixture.assertTotals()
    }

    @Test
    fun `a part depending on a source without a lifetime is not kept`() {
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES - SourceId.WIKIDATA)

        assertFalse(results.put(WIKIPEDIA, "a", "v"))
        assertTrue(results.put(LASTFM, "a", "v"))
        assertNull(results.get(WIKIPEDIA, "a"))
    }

    @Test
    fun `a value whose source lost its lifetime since is a miss`() {
        results.put(WIKIPEDIA, "a", "v")
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES - SourceId.WIKIDATA)

        assertNull(results.get(WIKIPEDIA, "a"))
    }

    @Test
    fun `the size counts the part, the key, the sources and the value`() {
        results.put(WIKIPEDIA, "ä", "value")

        val bytes = "wikipedia.artist".length + 2 + " W WD ".length + "value".length
        assertEquals(TableStats(1, bytes.toLong()), fixture.caches.stats().artistResults)
        assertEquals(bytes.toLong(), fixture.caches.sizeBytes())
    }

    @Test
    fun `a value larger than the whole limit is not kept`() {
        fixture.settings = Settings(cacheSizeLimit = 100)

        assertFalse(results.put(LASTFM, "a", "x".repeat(100)))
        assertNull(results.get(LASTFM, "a"))
    }

    @Test
    fun `a stale read deletes only the row it saw`() {
        results.put(LASTFM, "a", "old")
        fixture.clock.advance(Settings.DEFAULT_CACHE_LIFETIMES.getValue(SourceId.LASTFM))
        // Another call writes a fresh value between this read's select and its delete.
        fixture.driver.before("DELETE FROM artist_result WHERE id") { results.put(LASTFM, "a", "new") }

        assertNull(results.get(LASTFM, "a"))
        assertEquals("new", results.get(LASTFM, "a")?.value)
    }

    @Test
    fun `the adapters' view reads and keeps through the caches`() =
        runBlocking {
            var opened = 0
            val store =
                StoreArtistResults(
                    {
                        opened++
                        fixture.caches
                    },
                    coroutineContext,
                )
            assertEquals(0, opened, "the caches are asked for only at the first call")

            store.keep(LASTFM, "a", "v")
            assertEquals("v", store.get(LASTFM, "a")?.value)
            assertEquals("v", results.get(LASTFM, "a")?.value)
            assertNull(store.get(WIKIPEDIA, "a"))
        }

    @Test
    fun `the adapters' view reads a failing database as a miss, and keeps nothing, without throwing`() =
        runBlocking {
            val store = StoreArtistResults({ error("the database could not be opened") }, coroutineContext)

            store.keep(LASTFM, "a", "v")
            assertNull(store.get(LASTFM, "a"))
        }

    private fun track(freshUntil: Instant) =
        SourceResult(Outcome.ArtistArticle, emptyList(), Duration.ZERO, freshUntil = freshUntil)

    private companion object {
        val LASTFM_LIFETIME = Settings.DEFAULT_CACHE_LIFETIMES.getValue(SourceId.LASTFM)
        val MUSICBRAINZ = ArtistPart("musicbrainz.tags", 1, 1, setOf(SourceId.MUSICBRAINZ))
        val LASTFM = ArtistPart("lastfm.artist", 1, 1, setOf(SourceId.LASTFM))
        val WIKIPEDIA = ArtistPart("wikipedia.artist", 1, 1, setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA))
    }
}
