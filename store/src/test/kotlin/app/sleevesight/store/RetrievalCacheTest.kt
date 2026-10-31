package app.sleevesight.store

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.RecordingTags
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
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
import kotlin.time.Duration.Companion.seconds
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
    fun `a line of counts and lists keeps its mark`() {
        val at = Instant.parse("2026-10-27T12:00:00Z")
        val counted =
            EvidenceDraft(
                SourceId.LASTFM,
                Scope.ARTIST,
                "Last.fm counts 5 listeners for X.",
                null,
                at,
                aggregate = true,
            )
        val result = SourceResult(Outcome.ArtistArticle, listOf(counted), 10.milliseconds)
        assertTrue(retrieval.put(SourceId.LASTFM, key, 1, result))

        assertEquals(
            listOf(true),
            retrieval
                .get(SourceId.LASTFM, key, 1)
                ?.result
                ?.evidence
                ?.map { it.aggregate },
        )
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
        for (source in listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.DISCOGS)) {
            retrieval.put(source, key, 1, sample(Outcome.SongArticle, source))
        }

        fixture.clock.advance(1.days - 1.milliseconds)
        assertNotNull(retrieval.get(SourceId.DISCOGS, key, 1))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(SourceId.DISCOGS, key, 1), "a 1-day source")
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
        assertFalse(retrieval.put(SourceId.DISCOGS, key, 1, sample(Outcome.SongArticle, SourceId.PLAYER)))

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
        retrieval.put(old, track, null, sample(Outcome.ArtistArticle, SourceId.LASTFM))
        assertNotNull(retrieval.get(old, track, null))

        assertNull(retrieval.get(new, track, null))
        assertEquals(0, entries(), "the stale entry is dropped")

        retrieval.put(new, track, null, sample(Outcome.None, SourceId.LASTFM))
        assertEquals(Outcome.None, retrieval.get(new, track, null)?.result?.outcome)
        fixture.assertTotals()
    }

    @Test
    fun `an adapter's default extraction version is 1`() {
        retrieval.put(FakeAdapter(SourceId.DISCOGS), track, null, sample(Outcome.SongArticle, SourceId.DISCOGS))

        assertNotNull(retrieval.get(SourceId.DISCOGS, retrieval.keyOf(track, null), 1))
    }

    @Test
    fun `web search's results are kept for web search's lifetime`() {
        val web =
            SourceResult(
                Outcome.SongArticle,
                listOf(EvidenceDraft(SourceId.WEB, Scope.WEB, "A snippet.", null, fixture.clock.now)),
                1.milliseconds,
            )
        val adapter = FakeAdapter(SourceId.WEB)
        assertTrue(retrieval.put(adapter, track, null, web))

        fixture.clock.advance(1.days - 1.milliseconds)
        assertEquals(web, retrieval.get(adapter, track, null)?.result, "served within its lifetime")

        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(adapter, track, null), "expired after it")
        assertEquals(0, entries())
        fixture.assertTotals()
    }

    @Test
    fun `a result holding a web line lives no longer than web search's lifetime`() {
        val snippet = EvidenceDraft(SourceId.WEB, Scope.WEB, "A snippet.", null, fixture.clock.now)
        val withWeb = sample(Outcome.SongArticle).let { it.copy(evidence = it.evidence + snippet) }
        assertTrue(retrieval.put(SourceId.WIKIPEDIA, key, 1, withWeb))

        fixture.clock.advance(1.days - 1.milliseconds)
        assertNotNull(retrieval.get(SourceId.WIKIPEDIA, key, 1))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(SourceId.WIKIPEDIA, key, 1))
    }

    @Test
    fun `the player's length is part of the key, in whole seconds or none`() {
        val keys = listOf(180.seconds, 300.seconds, null).map { retrieval.keyOf(track, it) }
        assertEquals(3, keys.distinct().size)
        assertEquals(retrieval.keyOf(track, 180.seconds), retrieval.keyOf(track, 180.seconds + 400.milliseconds))

        retrieval.put(
            FakeAdapter(SourceId.MUSICBRAINZ),
            track,
            180.seconds,
            sample(Outcome.SongArticle, SourceId.MUSICBRAINZ),
        )
        assertNotNull(retrieval.get(FakeAdapter(SourceId.MUSICBRAINZ), track, 180.seconds))
        assertNull(retrieval.get(FakeAdapter(SourceId.MUSICBRAINZ), track, 300.seconds))
    }

    @Test
    fun `an entry kept before the first release's title and the original decodes without them`() {
        // As MusicBrainz's result was kept before #724: the first release's date and the releases' dates only.
        val old =
            """{"outcome":"song_article","latency":"PT0.4S","firstReleased":"1974-01",""" +
                """"releaseDates":[{"title":"Sundown","date":"1974-01","group":"rg-1"}]}"""
        val decoded = assertNotNull(RetrievalCodec.decode(old))
        assertEquals("1974-01", decoded.firstReleased)
        assertEquals(listOf(ReleaseDate("Sundown", "1974-01", "rg-1")), decoded.releaseDates)
        assertNull(decoded.firstRelease)
        assertNull(decoded.original)
        // And one with neither is written without them.
        val plain = sample(Outcome.SongArticle).copy(firstRelease = null, original = null)
        assertFalse("\"firstRelease\"" in RetrievalCodec.encode(plain))
        assertFalse("\"original\"" in RetrievalCodec.encode(plain))
    }

    @Test
    fun `the first release and the original, with its release or without, round-trip`() {
        val result = sample(Outcome.SongArticle)
        assertEquals(result, RetrievalCodec.decode(RetrievalCodec.encode(result)))
        val unreleased = result.copy(original = result.original?.copy(release = null))
        assertEquals(unreleased, RetrievalCodec.decode(RetrievalCodec.encode(unreleased)))
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
        retrieval.put(SourceId.DISCOGS, key, 1, sample(Outcome.SongArticle, SourceId.DISCOGS))
        fixture.clock.advance(2.days)
        // Between this read's select and its delete of the expired row, another call writes afresh.
        fixture.driver.before("DELETE FROM retrieval WHERE id") {
            retrieval.put(SourceId.DISCOGS, key, 1, sample(Outcome.None, SourceId.DISCOGS))
        }

        assertNull(retrieval.get(SourceId.DISCOGS, key, 1))

        assertEquals(Outcome.None, retrieval.get(SourceId.DISCOGS, key, 1)?.result?.outcome)
        fixture.assertTotals()
    }

    @Test
    fun `the track key covers the credited artists and the work`() {
        val plain = normalizer.normalize("Song", "A")
        val featuring = normalizer.normalize("Song (feat. B)", "A")
        val from = normalizer.normalize("Song (From The Film)", "A")
        assertEquals(plain.lookupKey, featuring.lookupKey)
        assertEquals(plain.lookupKey, from.lookupKey)

        val keys = listOf(plain, featuring, from).map { retrieval.keyOf(it, null) }
        assertEquals(3, keys.toSet().size)
        assertEquals(
            retrieval.keyOf(normalizer.normalize("Song", "A, B"), null),
            retrieval.keyOf(normalizer.normalize("Song", "a, b"), null),
        )
    }

    @Test
    fun `equivalent spellings of the artists and the work give one key`() {
        val nfc = normalizer.normalize("Song (From Déjà Vu)", "A, Beyoncé")
        val nfd = normalizer.normalize("Song (From De\u0301ja\u0300 Vu)", "a, Beyonce\u0301")

        assertEquals(retrieval.keyOf(nfc, null), retrieval.keyOf(nfd, null))
    }

    @Test
    fun `case, accents and punctuation between words fold to one key, letters run together do not`() {
        fun key(artist: String) = retrieval.keyOf(normalizer.normalize("Song", artist), null)

        // As `keyOf`'s KDoc says, and Last.fm's artist cache, keyed by the name as sent, does not.
        assertEquals(key("Björk"), key("bjork"))
        assertEquals(key("AC/DC"), key("AC DC"))
        assertNotEquals(key("M.I.A."), key("MIA"))
    }

    @Test
    fun `a rejection by an adapter that consults a shorter-lived source expires with it`() {
        fixture.settings = Settings(cacheLifetimes = Settings.DEFAULT_CACHE_LIFETIMES + (SourceId.WIKIDATA to 1.days))
        val adapter = FakeAdapter(SourceId.WIKIPEDIA, consults = setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA))
        val rejected = SourceResult(Outcome.Rejected("not a song"), emptyList(), 1.milliseconds)
        retrieval.put(adapter, track, null, rejected)

        fixture.clock.advance(1.days - 1.milliseconds)
        assertNotNull(retrieval.get(adapter, track, null))
        fixture.clock.advance(1.milliseconds)
        assertNull(retrieval.get(adapter, track, null), "Wikidata's 1 day, though it gave no line")
    }

    @Test
    fun `purging deletes the entries of a source the app no longer knows`() {
        retrieval.put(SourceId.DISCOGS, key, 1, sample(Outcome.SongArticle, SourceId.DISCOGS))
        fixture.caches.queries.retrievalInsert("XX", key, " XX ", RetrievalCodec.FORMAT.toLong(), 1, "{}", 0, 0, 10)

        assertEquals(1, fixture.caches.purgeExpired())
        assertEquals(1, entries())
        fixture.assertTotals()
    }

    @Test
    fun `purging deletes the entries holding lines of a source the app no longer knows`() {
        retrieval.put(SourceId.DISCOGS, key, 1, sample(Outcome.SongArticle, SourceId.DISCOGS))
        fixture.caches.queries.retrievalInsert("W", key, " W XX ", RetrievalCodec.FORMAT.toLong(), 1, "{}", 0, 0, 10)

        assertEquals(1, fixture.caches.purgeExpired())
        assertNotNull(retrieval.get(SourceId.DISCOGS, key, 1))
        assertEquals(1, entries())
        fixture.assertTotals()
    }

    @Test
    fun `artists and works of symbols only get keys of their own`() {
        assertNotEquals(
            retrieval.keyOf(normalizer.normalize("Song", "A, !!!"), null),
            retrieval.keyOf(normalizer.normalize("Song", "A, †††"), null),
        )
        assertNotEquals(
            retrieval.keyOf(normalizer.normalize("Song (From !)", "A"), null),
            retrieval.keyOf(normalizer.normalize("Song (From ?)", "A"), null),
        )
    }

    @Test
    fun `tracks in other scripts get entries of their own`() {
        val adapter = FakeAdapter(SourceId.WIKIPEDIA)
        val utada = normalizer.normalize("First Love", "宇多田ヒカル")
        val sakamoto = normalizer.normalize("First Love", "坂本龍一")
        retrieval.put(adapter, utada, null, sample(Outcome.SongArticle))

        assertNotNull(retrieval.get(adapter, utada, null))
        assertNull(retrieval.get(adapter, sakamoto, null))

        val moi = normalizer.normalize("Мои", "Кино")
        retrieval.put(adapter, normalizer.normalize("Мой", "Кино"), null, sample(Outcome.SongArticle))
        assertNull(retrieval.get(adapter, moi, null))
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
            rejections = listOf(Rejection("Sundown", "a disambiguation page", Scope.SONG)),
            genres =
                listOf(
                    GenreLabel(
                        "folk rock",
                        "folk",
                        "Folk Rock",
                        "folkrock",
                        "https://www.last.fm/music/Gordon+Lightfoot",
                        artist = "Gordon Lightfoot",
                    ),
                    GenreLabel("folk", "folk", "Folk", "folk"),
                ),
            labelArtists =
                listOf(
                    ResolvedArtist("Gordon Lightfoot", "mb-gl"),
                    ResolvedArtist("Terry Clements", "mb-other"),
                ),
            recordingTags = RecordingTags("mb-sundown", listOf("folk rock", "soft rock")),
            firstReleased = "1974-01-01",
            releaseDates =
                listOf(
                    ReleaseDate("Sundown", "1974-01-01", "rg-1"),
                    ReleaseDate("Gord's Gold", "1975", "rg-2"),
                    ReleaseDate("Undated", null, "rg-3"),
                ),
            firstRelease = FirstRelease("1974-01-01", "Sundown", "rg-1", "r-1", "Album"),
            original = original,
        )
    }

    // A cover's original, with the release it first came out on (#724).
    private val original =
        OriginalRecording(
            recordingMbid = "mb-original",
            title = "Sundown",
            artists = listOf(ResolvedArtist("Someone First", "mb-first")),
            artistCredit = "Someone First",
            date = "1970-05",
            release = FirstRelease("1970-06", "Sundown / B-side", null, "r-0", "Single"),
        )

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
