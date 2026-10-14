package io.github.wkusnierczyk.sleevesight.store

import io.github.wkusnierczyk.sleevesight.core.evidence.EvidenceDraft
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import io.github.wkusnierczyk.sleevesight.core.sources.Rejection
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceAdapter
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

class RetrievalCacheTest {
    private val fixture = CacheFixture()
    private val retrieval = fixture.caches.retrieval
    private val normalizer = fixture.normalizer
    private val key = "gordon lightfoot|sundown"
    private val track = normalizer.normalize("Sundown", "Gordon Lightfoot")

    @AfterTest
    fun close() = fixture.close()

    private fun entries(): Long =
        fixture.caches
            .stats()
            .retrieval.entries

    @Test
    fun `a result comes back as it was kept`() {
        val result = sample(Outcome.Rejected("“Sundown” by someone else"))
        assertTrue(retrieval.put(SourceId.WIKIPEDIA, key, 1, result))

        val cached = assertNotNull(retrieval.get(SourceId.WIKIPEDIA, key, 1))
        assertEquals(result, cached.result)
        assertEquals(fixture.clock.now, cached.storedAt)
    }

    @Test
    fun `every outcome a source can give round-trips`() {
        val outcomes =
            listOf(
                Outcome.SongArticle,
                Outcome.ArtistArticle,
                Outcome.OriginalSong,
                Outcome.None,
                Outcome.Rejected("no"),
            )
        for (outcome in outcomes) {
            retrieval.put(SourceId.MUSICBRAINZ, key, 1, sample(outcome, SourceId.MUSICBRAINZ))
            assertEquals(outcome, retrieval.get(SourceId.MUSICBRAINZ, key, 1)?.result?.outcome)
        }
    }

    @Test
    fun `the key is the source and the lookup key`() {
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.SongArticle))

        assertNull(retrieval.get(SourceId.WIKIDATA, key, 1))
        assertNull(retrieval.get(SourceId.WIKIPEDIA, "gordon lightfoot|carefree highway", 1))
    }

    @Test
    fun `each source's entries expire after its own lifetime`() {
        for (source in listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.WEB)) {
            retrieval.put(source, key, 1, sample(Outcome.SongArticle, source))
        }

        fixture.clock.advance(1.days - 1.milliseconds)
        assertNotNull(retrieval.get(SourceId.WEB, key, 1))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(SourceId.WEB, key, 1), "web search: 1 day")
        assertNotNull(retrieval.get(SourceId.WIKIPEDIA, key, 1))

        fixture.clock.advance(6.days)
        assertNull(retrieval.get(SourceId.WIKIPEDIA, key, 1), "Wikipedia: 7 days")
        assertNotNull(retrieval.get(SourceId.MUSICBRAINZ, key, 1))

        fixture.clock.advance(23.days)
        assertNull(retrieval.get(SourceId.MUSICBRAINZ, key, 1), "MusicBrainz: 30 days")
    }

    @Test
    fun `a changed lifetime applies to entries already kept`() {
        retrieval.put(SourceId.MUSICBRAINZ, key, 1, sample(Outcome.SongArticle, SourceId.MUSICBRAINZ))
        fixture.clock.advance(3.days)

        val shorter = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.MUSICBRAINZ to 2.days)
        fixture.settings = Settings(cacheLifetimes = shorter)

        assertNull(retrieval.get(SourceId.MUSICBRAINZ, key, 1))
    }

    @Test
    fun `a result expires with the shortest lifetime of the sources it holds lines from`() {
        // The Wikipedia adapter's result holds Wikidata's lines too.
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WIKIDATA to 1.days))
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.SongArticle))
        retrieval.put(SourceId.LASTFM, key, 1, sample(Outcome.ArtistArticle, SourceId.LASTFM))

        fixture.clock.advance(1.days)

        assertNull(retrieval.get(SourceId.WIKIPEDIA, key, 1), "Wikidata's 1 day, not Wikipedia's 7")
        assertNotNull(retrieval.get(SourceId.LASTFM, key, 1))
    }

    @Test
    fun `purging uses the shortest lifetime too`() {
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WIKIDATA to 1.days))
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.SongArticle))
        fixture.clock.advance(1.days)

        assertEquals(1, fixture.caches.purgeExpired())
        assertEquals(0, entries())
        fixture.assertTotals()
    }

    @Test
    fun `errors, switched-off sources and the pseudo-sources are not kept`() {
        assertFalse(retrieval.put(SourceId.LASTFM, key, 1, sample(Outcome.Unreachable, SourceId.LASTFM)))
        assertFalse(retrieval.put(SourceId.LASTFM, key, 1, sample(Outcome.SourceOff, SourceId.LASTFM)))
        assertFalse(retrieval.put(SourceId.PLAYER, key, 1, sample(Outcome.SongArticle, SourceId.PLAYER)))
        assertFalse(retrieval.put(SourceId.MODEL, key, 1, sample(Outcome.SongArticle, SourceId.MODEL)))
        // A line from a source with no lifetime.
        assertFalse(retrieval.put(SourceId.WEB, key, 1, sample(Outcome.SongArticle, SourceId.PLAYER)))

        assertEquals(0, entries())
    }

    @Test
    fun `a result holding a source that lost its lifetime is not kept`() {
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES - SourceId.WIKIDATA)

        assertFalse(retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.SongArticle)))
    }

    @Test
    fun `a result kept under other extraction rules is a miss`() {
        val old = FakeAdapter(SourceId.LASTFM, extractionVersion = 1)
        val new = FakeAdapter(SourceId.LASTFM, extractionVersion = 2)
        retrieval.put(old, track, sample(Outcome.ArtistArticle, SourceId.LASTFM))
        assertNotNull(retrieval.get(old, track))

        assertNull(retrieval.get(new, track))
        assertEquals(0, entries(), "the stale entry is dropped")

        retrieval.put(new, track, sample(Outcome.None, SourceId.LASTFM))
        assertEquals(Outcome.None, retrieval.get(new, track)?.result?.outcome)
        fixture.assertTotals()
    }

    @Test
    fun `an adapter's default extraction version is 1`() {
        retrieval.put(FakeAdapter(SourceId.WEB), track, sample(Outcome.SongArticle, SourceId.WEB))

        assertNotNull(retrieval.get(SourceId.WEB, retrieval.keyOf(track), 1))
    }

    @Test
    fun `a payload it cannot read is a miss`() {
        val payload = RetrievalCodec.encode(sample(Outcome.SongArticle))

        assertNotNull(RetrievalCodec.decode(payload))
        assertNull(RetrievalCodec.decode(payload.replace("\"W\"", "\"XX\"")), "unknown source")
        assertNull(RetrievalCodec.decode(payload.replace("\"song\"", "\"chorus\"")), "unknown scope")
        assertNull(RetrievalCodec.decode("{}"))
        assertNull(RetrievalCodec.decode("not json"))
    }

    @Test
    fun `a cleanup after a stale read leaves an entry written meanwhile`() {
        retrieval.put(SourceId.WEB, key, 1, sample(Outcome.SongArticle, SourceId.WEB))
        fixture.clock.advance(2.days)
        // Between this read's select and its delete of the expired row, another call writes afresh.
        fixture.driver.before("DELETE FROM retrieval WHERE id") {
            retrieval.put(SourceId.WEB, key, 1, sample(Outcome.None, SourceId.WEB))
        }

        assertNull(retrieval.get(SourceId.WEB, key, 1))

        assertEquals(Outcome.None, retrieval.get(SourceId.WEB, key, 1)?.result?.outcome)
        fixture.assertTotals()
    }

    @Test
    fun `the track key covers the credited artists and the work`() {
        val plain = normalizer.normalize("Song", "A")
        val featuring = normalizer.normalize("Song (feat. B)", "A")
        val from = normalizer.normalize("Song (From The Film)", "A")
        assertEquals(plain.lookupKey, featuring.lookupKey)
        assertEquals(plain.lookupKey, from.lookupKey)

        val keys = listOf(plain, featuring, from).map { retrieval.keyOf(it) }
        assertEquals(3, keys.toSet().size)
        assertEquals(
            retrieval.keyOf(normalizer.normalize("Song", "A, B")),
            retrieval.keyOf(normalizer.normalize("Song", "a, b")),
        )
    }

    @Test
    fun `equivalent spellings of the artists and the work give one key`() {
        val nfc = normalizer.normalize("Song (From Déjà Vu)", "A, Beyoncé")
        val nfd = normalizer.normalize("Song (From De\u0301ja\u0300 Vu)", "a, Beyonce\u0301")

        assertEquals(retrieval.keyOf(nfc), retrieval.keyOf(nfd))
    }

    @Test
    fun `a rejection by an adapter that consults a shorter-lived source expires with it`() {
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WIKIDATA to 1.days))
        val adapter = FakeAdapter(SourceId.WIKIPEDIA, consults = setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA))
        val rejected = SourceResult(Outcome.Rejected("not a song"), emptyList(), 1.milliseconds)
        retrieval.put(adapter, track, rejected)

        fixture.clock.advance(1.days - 1.milliseconds)
        assertNotNull(retrieval.get(adapter, track))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(adapter, track), "Wikidata's 1 day, though it gave no line")
    }

    @Test
    fun `purging deletes the entries of a source the app no longer knows`() {
        retrieval.put(SourceId.WEB, key, 1, sample(Outcome.SongArticle, SourceId.WEB))
        fixture.caches.queries.retrievalInsert("XX", key, " XX ", RetrievalCodec.FORMAT.toLong(), 1, "{}", 0, 0, 10)

        assertEquals(1, fixture.caches.purgeExpired())
        assertEquals(1, entries())
        fixture.assertTotals()
    }

    @Test
    fun `purging deletes the entries holding lines of a source the app no longer knows`() {
        retrieval.put(SourceId.WEB, key, 1, sample(Outcome.SongArticle, SourceId.WEB))
        fixture.caches.queries.retrievalInsert("W", key, " W XX ", RetrievalCodec.FORMAT.toLong(), 1, "{}", 0, 0, 10)

        assertEquals(1, fixture.caches.purgeExpired())
        assertNotNull(retrieval.get(SourceId.WEB, key, 1))
        assertEquals(1, entries())
        fixture.assertTotals()
    }

    @Test
    fun `artists and works of symbols only get keys of their own`() {
        assertNotEquals(
            retrieval.keyOf(normalizer.normalize("Song", "A, !!!")),
            retrieval.keyOf(normalizer.normalize("Song", "A, †††")),
        )
        assertNotEquals(
            retrieval.keyOf(normalizer.normalize("Song (From !)", "A")),
            retrieval.keyOf(normalizer.normalize("Song (From ?)", "A")),
        )
    }

    @Test
    fun `tracks in other scripts get entries of their own`() {
        val adapter = FakeAdapter(SourceId.WIKIPEDIA)
        val utada = normalizer.normalize("First Love", "宇多田ヒカル")
        val sakamoto = normalizer.normalize("First Love", "坂本龍一")
        retrieval.put(adapter, utada, sample(Outcome.SongArticle))

        assertNotNull(retrieval.get(adapter, utada))
        assertNull(retrieval.get(adapter, sakamoto))

        val moi = normalizer.normalize("Мои", "Кино")
        retrieval.put(adapter, normalizer.normalize("Мой", "Кино"), sample(Outcome.SongArticle))
        assertNull(retrieval.get(adapter, moi))
    }

    @Test
    fun `a newer result replaces the kept one`() {
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.None))
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.SongArticle))

        assertEquals(Outcome.SongArticle, retrieval.get(SourceId.WIKIPEDIA, key, 1)?.result?.outcome)
        assertEquals(1, entries())
        fixture.assertTotals()
    }

    // Lines from [source]; the Wikipedia adapter's also hold a Wikidata line, as it makes them.
    private fun sample(
        outcome: Outcome,
        source: SourceId = SourceId.WIKIPEDIA,
    ): SourceResult {
        val at = Instant.parse("2026-10-14T11:59:58.123456789Z")
        val wikidata =
            if (source == SourceId.WIKIPEDIA) {
                listOf(EvidenceDraft(SourceId.WIKIDATA, Scope.SONG, "Genre: folk rock. Ünïcödé ✓", null, at))
            } else {
                emptyList()
            }
        return SourceResult(
            outcome = outcome,
            evidence =
                listOf(
                    EvidenceDraft(
                        source,
                        Scope.SONG,
                        "\"Sundown\" is a song by Canadian singer-songwriter Gordon Lightfoot.",
                        "https://en.wikipedia.org/wiki/Sundown_(Gordon_Lightfoot_song)",
                        at,
                    ),
                ) + wikidata,
            latency = 412.milliseconds + 7.nanoseconds,
            unverified = listOf(EvidenceDraft(source, Scope.ARTIST, "An unverified line.", null, at)),
            rejections = listOf(Rejection("Sundown", "a disambiguation page")),
        )
    }

    private class FakeAdapter(
        override val id: SourceId,
        override val extractionVersion: Int = 1,
        override val consults: Set<SourceId> = setOf(id),
    ) : SourceAdapter {
        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult = error("not looked up")
    }
}
