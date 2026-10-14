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
    private val key = "gordon lightfoot|sundown"

    @AfterTest
    fun close() = fixture.close()

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
            retrieval.put(SourceId.MUSICBRAINZ, key, 1, sample(outcome))
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
            retrieval.put(source, key, 1, sample(Outcome.SongArticle))
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
        retrieval.put(SourceId.MUSICBRAINZ, key, 1, sample(Outcome.SongArticle))
        fixture.clock.advance(3.days)

        val shorter = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.MUSICBRAINZ to 2.days)
        fixture.settings = Settings(cacheLifetimes = shorter)

        assertNull(retrieval.get(SourceId.MUSICBRAINZ, key, 1))
    }

    @Test
    fun `errors, switched-off sources and the pseudo-sources are not kept`() {
        assertFalse(retrieval.put(SourceId.LASTFM, key, 1, sample(Outcome.Unreachable)))
        assertFalse(retrieval.put(SourceId.LASTFM, key, 1, sample(Outcome.SourceOff)))
        assertFalse(retrieval.put(SourceId.PLAYER, key, 1, sample(Outcome.SongArticle)))
        assertFalse(retrieval.put(SourceId.MODEL, key, 1, sample(Outcome.SongArticle)))

        assertEquals(
            0,
            fixture.caches
                .stats()
                .retrieval.entries,
        )
    }

    @Test
    fun `a result kept under other extraction rules is a miss`() {
        val old = FakeAdapter(SourceId.LASTFM, extractionVersion = 1)
        val new = FakeAdapter(SourceId.LASTFM, extractionVersion = 2)
        retrieval.put(old, key, sample(Outcome.ArtistArticle))
        assertNotNull(retrieval.get(old, key))

        assertNull(retrieval.get(new, key))
        assertEquals(
            0,
            fixture.caches
                .stats()
                .retrieval.entries,
            "the stale entry is dropped",
        )

        retrieval.put(new, key, sample(Outcome.None))
        assertEquals(Outcome.None, retrieval.get(new, key)?.result?.outcome)
    }

    @Test
    fun `an adapter's default extraction version is 1`() {
        retrieval.put(FakeAdapter(SourceId.WEB), key, sample(Outcome.SongArticle))

        assertNotNull(retrieval.get(SourceId.WEB, key, 1))
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
    fun `a newer result replaces the kept one`() {
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.None))
        retrieval.put(SourceId.WIKIPEDIA, key, 1, sample(Outcome.SongArticle))

        assertEquals(Outcome.SongArticle, retrieval.get(SourceId.WIKIPEDIA, key, 1)?.result?.outcome)
        assertEquals(
            1,
            fixture.caches
                .stats()
                .retrieval.entries,
        )
    }

    private fun sample(outcome: Outcome): SourceResult {
        val at = Instant.parse("2026-10-14T11:59:58.123456789Z")
        return SourceResult(
            outcome = outcome,
            evidence =
                listOf(
                    EvidenceDraft(
                        SourceId.WIKIPEDIA,
                        Scope.SONG,
                        "\"Sundown\" is a song by Canadian singer-songwriter Gordon Lightfoot.",
                        "https://en.wikipedia.org/wiki/Sundown_(Gordon_Lightfoot_song)",
                        at,
                    ),
                    EvidenceDraft(SourceId.WIKIDATA, Scope.SONG, "Genre: folk rock. Ünïcödé ✓", null, at),
                ),
            latency = 412.milliseconds + 7.nanoseconds,
            unverified = listOf(EvidenceDraft(SourceId.WIKIPEDIA, Scope.ARTIST, "An unverified line.", null, at)),
            rejections = listOf(Rejection("Sundown", "a disambiguation page")),
        )
    }

    private class FakeAdapter(
        override val id: SourceId,
        override val extractionVersion: Int = 1,
    ) : SourceAdapter {
        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult = error("not looked up")
    }
}
