package app.sleevesight.store

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardEntity
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StorePipelineCacheTest {
    private val fixture = CacheFixture()
    private val cache = StorePipelineCache(fixture.caches, EmptyCoroutineContext, ArtistKeeping.ALWAYS)
    private val sundown = fixture.normalizer.normalize("Sundown", "Gordon Lightfoot")
    private val adapter =
        object : SourceAdapter {
            override val id: SourceId = SourceId.WIKIPEDIA

            override suspend fun lookup(
                track: NormalizedTrack,
                resolution: Deferred<Resolution>,
            ): SourceResult = error("not looked up")
        }

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `a source result is kept per adapter and track`() =
        runBlocking {
            val result =
                SourceResult(
                    Outcome.SongArticle,
                    listOf(EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "A line.", null, fixture.clock.now)),
                    12.milliseconds,
                )
            cache.keep(adapter, sundown, 213.seconds, result)

            assertEquals(result, cache.result(adapter, sundown, 213.seconds))
            assertNull(cache.result(adapter, sundown, null))
            assertNull(cache.result(adapter, fixture.normalizer.normalize("Sundown", "Someone Else"), 213.seconds))
        }

    @Test
    fun `a Last_fm result kept before its counts were marked is missed once the version is raised`() =
        runBlocking {
            // Last.fm's extraction version went from 3 to 4 with the aggregate mark (#617).
            fun lastFm(version: Int) =
                object : SourceAdapter {
                    override val id: SourceId = SourceId.LASTFM
                    override val extractionVersion: Int = version

                    override suspend fun lookup(
                        track: NormalizedTrack,
                        resolution: Deferred<Resolution>,
                    ): SourceResult = error("not looked up")
                }
            val unmarked =
                EvidenceDraft(
                    SourceId.LASTFM,
                    Scope.ARTIST,
                    "Last.fm counts 5 listeners for X.",
                    null,
                    fixture.clock.now,
                )
            cache.keep(
                lastFm(3),
                sundown,
                213.seconds,
                SourceResult(Outcome.ArtistArticle, listOf(unmarked), 12.milliseconds),
            )

            assertNull(cache.result(lastFm(4), sundown, 213.seconds))
        }

    @Test
    fun `web search's results are kept like any other source's`() =
        runBlocking {
            val web =
                object : SourceAdapter {
                    override val id: SourceId = SourceId.WEB

                    override suspend fun lookup(
                        track: NormalizedTrack,
                        resolution: Deferred<Resolution>,
                    ): SourceResult = error("not looked up")
                }
            val snippet = EvidenceDraft(SourceId.WEB, Scope.WEB, "A highlight.", "https://x.example", fixture.clock.now)
            val result = SourceResult(Outcome.SongArticle, listOf(snippet), 12.milliseconds)
            cache.keep(web, sundown, 213.seconds, result)

            assertEquals(result, cache.result(web, sundown, 213.seconds))
            fixture.clock.advance(1.days)
            assertNull(cache.result(web, sundown, 213.seconds))
        }

    @Test
    fun `a card is kept under the track's retrieval key`() =
        runBlocking {
            val key =
                CardKey(
                    kind = CardKind.INSIGHT,
                    entity = CardEntity.Track(sundown, 213.seconds),
                    evidenceFingerprint = "9f2c",
                    promptVersion = 1,
                    styleHash = "e3b0",
                    provider = ProviderId.ANTHROPIC,
                    model = "claude-haiku-5-5",
                    modelKnowledge = false,
                    effort = ReasoningEffort.LOW,
                )
            cache.keep(key, "It was a hit [W1].")

            assertEquals("It was a hit [W1].", cache.card(key))
            val stored =
                fixture.caches.synthesis.get(
                    SynthesisKey(
                        CardKind.INSIGHT,
                        fixture.caches.retrieval.entityKeyOf(sundown, 213.seconds),
                        "9f2c",
                        1,
                        "e3b0",
                        ProviderId.ANTHROPIC,
                        "claude-haiku-5-5",
                        false,
                        ReasoningEffort.LOW,
                    ),
                )
            assertEquals("It was a hit [W1].", stored?.text)
            assertNull(cache.card(key.copy(kind = CardKind.SONG)))

            // Kept with the time the card shown carries (#643), not one the cache reads later.
            val writtenAt = fixture.clock.now - 2.days
            cache.keep(key, "It was a hit [W1].", writtenAt)
            assertEquals(KeptCard("It was a hit [W1].", writtenAt), cache.keptCard(key))
        }

    @Test
    fun `each genre's reason is kept apart, under the track's key and the genre`() =
        runBlocking {
            val jazz =
                CardKey(
                    kind = CardKind.GENRE,
                    entity = CardEntity.Track(sundown, 213.seconds, subject = "jazz"),
                    evidenceFingerprint = "9f2c",
                    promptVersion = 1,
                    styleHash = "e3b0",
                    provider = ProviderId.ANTHROPIC,
                    model = "claude-haiku-5-5",
                    modelKnowledge = false,
                    effort = ReasoningEffort.LOW,
                )
            cache.keep(jazz, "It is jazz [W1].")
            cache.keep(jazz.about("folk"), "It is folk [W2].")

            assertEquals("It is jazz [W1].", cache.card(jazz))
            assertEquals(KeptCard("It is jazz [W1].", fixture.clock.now), cache.keptCard(jazz))
            assertEquals("It is folk [W2].", cache.card(jazz.about("folk")))
            assertNull(cache.card(jazz.about(null)))
            assertNull(cache.card(jazz.about("rock")))
        }

    @Test
    fun `an album's tracks share its artist and album cards, each written once`() =
        runBlocking {
            val group = ReleaseDate("Sundown", "1974-01", "rg-sundown", "Album")
            val lengths = listOf("Sundown" to 213, "Carefree Highway" to 225, "The Watchman's Gone" to 268)
            val tracks =
                lengths.map { (title, length) ->
                    Played(
                        fixture.normalizer.normalize(title, "Gordon Lightfoot"),
                        length.seconds,
                        // Each track's own evidence, so its own fingerprint; the artist's and album's are the same.
                        trackEvidence = "track-$title",
                        artist = ResolvedArtist("Gordon Lightfoot", "mb-gordon"),
                        // A track's recording is on other releases too: a compilation, and the title track
                        // its single, of the album's name.
                        dates =
                            listOf(group, ReleaseDate("Gord's Gold", "1975", "rg-gold-$title", "Album")) +
                                listOfNotNull(
                                    ReleaseDate("Sundown", "1974-04", "rg-sundown-single", "Single")
                                        .takeIf { title == "Sundown" },
                                ),
                    )
                }
            val writes = mutableListOf<CardKind>()

            for (played in tracks) {
                for (key in played.keys("Sundown")) {
                    if (cache.card(key) == null) {
                        writes += key.kind
                        cache.keep(key, "A ${key.kind.key} card [M1].")
                    }
                }
            }

            assertEquals(
                listOf(CardKind.INSIGHT, CardKind.ARTIST, CardKind.ALBUM, CardKind.INSIGHT, CardKind.INSIGHT),
                writes,
            )
            val albumKey = tracks.first().keys("Sundown").single { it.kind == CardKind.ALBUM }
            assertEquals(CardEntity.Album("Sundown", "Gordon Lightfoot", "rg-sundown"), albumKey.entity)
            // Rewritten only when the album's own evidence changes, not the track.
            assertNull(cache.card(albumKey.copy(evidenceFingerprint = "album-evidence-2")))
        }

    @Test
    fun `an artist or album MusicBrainz does not name is keyed by its folded name`() =
        runBlocking {
            val bjork = cardKey(CardKind.ARTIST, CardEntity.Artist("Björk", null))
            cache.keep(bjork, "An artist card [M1].")

            assertEquals("An artist card [M1].", cache.card(bjork.copy(entity = CardEntity.Artist("BJORK", null))))
            assertNull(cache.card(bjork.copy(entity = CardEntity.Artist("Björk", "mb-bjork"))))

            val debut = cardKey(CardKind.ALBUM, CardEntity.Album("Debut", "Björk", null))
            cache.keep(debut, "An album card [M1].")

            assertEquals(
                "An album card [M1].",
                cache.card(debut.copy(entity = CardEntity.Album("debut", "Bjork", null))),
            )
            // Two artists' albums of one name stay apart, and the parts never blur.
            assertNull(cache.card(debut.copy(entity = CardEntity.Album("Debut", "Someone Else", null))))
            assertNull(cache.card(debut.copy(entity = CardEntity.Album("Björk Debut", "", null))))
        }

    @Test
    fun `a card key's entity matches its kind`() {
        val track = CardEntity.Track(sundown, 213.seconds)
        val artist = CardEntity.Artist("Gordon Lightfoot", "mb-gordon")
        val album = CardEntity.Album("Sundown", "Gordon Lightfoot", "rg-sundown")

        assertFailsWith<IllegalArgumentException> { cardKey(CardKind.ARTIST, track) }
        assertFailsWith<IllegalArgumentException> { cardKey(CardKind.ALBUM, artist) }
        assertFailsWith<IllegalArgumentException> { cardKey(CardKind.INSIGHT, album) }
    }

    // A track of an album as played: its keys for the insight, the primary artist's card and the album's.
    private data class Played(
        val track: NormalizedTrack,
        val duration: Duration,
        val trackEvidence: String,
        val artist: ResolvedArtist,
        val dates: List<ReleaseDate>,
    ) {
        fun keys(album: String): List<CardKey> =
            listOf(
                cardKey(CardKind.INSIGHT, CardEntity.Track(track, duration), trackEvidence),
                cardKey(CardKind.ARTIST, CardEntity.Artist.of(artist), "artist-evidence"),
                cardKey(
                    CardKind.ALBUM,
                    CardEntity.Album.of(album, track.primary, dates, ALBUMS::albumKey),
                    "album-evidence",
                ),
            )
    }

    private companion object {
        val ALBUMS = NameMatcher(JvmAccentFolder)

        fun cardKey(
            kind: CardKind,
            entity: CardEntity,
            evidence: String = "9f2c",
        ): CardKey =
            CardKey(
                kind = kind,
                entity = entity,
                evidenceFingerprint = evidence,
                promptVersion = 1,
                styleHash = "e3b0",
                provider = ProviderId.ANTHROPIC,
                model = "claude-haiku-5-5",
                modelKnowledge = false,
                effort = ReasoningEffort.LOW,
            )
    }
}
