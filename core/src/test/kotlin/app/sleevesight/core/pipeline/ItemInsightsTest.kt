package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.ItemPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/** The song's, the artist's and the album's insights under *Key insights* (#522), each its own call and card. */
class ItemInsightsTest {
    private val provider =
        FakeProvider { request ->
            val prompt = request.prompt
            when {
                "the artist's insight" in prompt -> FakeProvider.done("Gordon Lightfoot is a Canadian singer [L1].")
                "the album's insight" in prompt -> FakeProvider.done("Sundown is his tenth album [MB1].")
                else -> FakeProvider.done("It was recorded in Toronto [MB1].")
            }
        }
    private val cache = MemoryCache()
    private val names = NameMatcher(JvmAccentFolder)
    private val albums = FakeAlbums()
    private val items = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, albums = albums)
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    // The album's lines, as `AlbumSource` gives them, the same whichever track asks.
    private class FakeAlbums(
        var lines: List<EvidenceDraft> =
            listOf(
                line(SourceId.MUSICBRAINZ, "“Sundown” is an album by Gordon Lightfoot.", Scope.RELEASE),
                line(SourceId.WIKIPEDIA, "Sundown is the tenth album by Gordon Lightfoot.", Scope.RELEASE),
            ),
    ) : AlbumLookup {
        val asked = mutableListOf<AlbumQuery>()

        override suspend fun lookup(query: AlbumQuery): AlbumFound {
            asked += query
            return AlbumFound(lines)
        }
    }

    // A track of Sundown: its own song lines, then the artist's, as the sources give them.
    private fun track(
        title: String,
        songLines: List<EvidenceDraft>,
        artists: String = "Gordon Lightfoot",
        resolution: Resolution? = sundown(title),
        extraArtistLines: List<EvidenceDraft> = emptyList(),
    ): TrackState {
        val playing =
            NowPlaying(title = title, artists = artists, album = "Sundown", player = "p", duration = 200.seconds)
        val drafts = songLines + ARTIST_LINES + extraArtistLines
        return TrackState(
            nowPlaying = playing,
            normalized = Normalizer(JvmAccentFolder).normalize(playing),
            sources =
                mapOf(
                    SourceId.WIKIPEDIA to SourceState.Done(SourceResult(Outcome.SongArticle, drafts, ZERO), false),
                ),
            resolution = resolution,
            evidence = EvidenceSet.of(drafts, JvmHasher),
        )
    }

    private fun sundown(title: String) =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "rec-$title",
            artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
            releaseDates = listOf(ReleaseDate("Sundown", "1974-01", "rg-sundown", "Album")),
        )

    // Each item of [track] written once, unless kept; the kinds written.
    private suspend fun play(track: TrackState): List<CardKind> {
        val written = mutableListOf<CardKind>()
        for (item in InsightItem.entries) {
            val evidence = checkNotNull(items.evidence(item, track, settings))
            val key = checkNotNull(items.key(item, track, evidence, settings))
            if (items.kept(key, evidence) == null) {
                assertIs<InsightState.Written>(items.write(key, item, track, evidence) { _, _ -> })
                written += item.kind
            }
        }
        return written
    }

    @Test
    fun `several tracks of one album write its artist's and album's insights once, the song's once per track`() =
        runTest {
            val tracks =
                listOf(
                    // The title track has its own Wikipedia article, so the artist's lines come later there.
                    track(
                        "Sundown",
                        listOf(
                            line(SourceId.MUSICBRAINZ, "Sundown was produced by Lenny Waronker."),
                            line(SourceId.WIKIPEDIA, "Sundown is a song."),
                        ),
                    ),
                    track(
                        "Carefree Highway",
                        listOf(line(SourceId.MUSICBRAINZ, "Carefree Highway was written by Lightfoot.")),
                    ),
                    track("Seven Island Suite", listOf(line(SourceId.MUSICBRAINZ, "It runs over six minutes."))),
                )

            val writes = tracks.flatMap { play(it) }

            assertEquals(
                listOf(CardKind.SONG, CardKind.ARTIST, CardKind.ALBUM, CardKind.SONG, CardKind.SONG),
                writes,
            )
            assertEquals(5, provider.requests.size)
            val artistKeys =
                tracks.map { t ->
                    checkNotNull(
                        items.key(
                            InsightItem.ARTIST,
                            t,
                            checkNotNull(items.evidence(InsightItem.ARTIST, t, settings)),
                            settings,
                        ),
                    )
                }
            assertEquals(1, artistKeys.distinct().size)
            assertEquals(CardEntity.Artist("Gordon Lightfoot", "mb-gordon"), artistKeys.first().entity)
            val albumKeys =
                tracks.map { t ->
                    checkNotNull(
                        items.key(
                            InsightItem.ALBUM,
                            t,
                            checkNotNull(items.evidence(InsightItem.ALBUM, t, settings)),
                            settings,
                        ),
                    )
                }
            assertEquals(1, albumKeys.distinct().size)
            assertEquals(CardEntity.Album("Sundown", "Gordon Lightfoot", "rg-sundown"), albumKeys.first().entity)
            assertEquals(AlbumQuery("Sundown", "Gordon Lightfoot", "rg-sundown"), albums.asked.first())
        }

    @Test
    fun `the artist's lines are numbered on their own, each mapped to the track's id`() =
        runTest {
            val title = track("Sundown", listOf(line(SourceId.WIKIPEDIA, "Sundown is a song.")))
            val evidence = checkNotNull(items.evidence(InsightItem.ARTIST, title, settings))
            assertEquals(listOf("W1", "W2", "L1"), evidence.evidence.ids)
            // On the track, Wikipedia's song line comes first: the artist's lines are W2 and W3 there.
            assertEquals(mapOf("W1" to "W2", "W2" to "W3", "L1" to "L1"), evidence.trackIds)
        }

    @Test
    fun `the song's insight is written from the track's evidence without its artist lines, keeping their ids`() =
        runTest {
            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "Sundown was produced by Lenny Waronker.")))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track, settings))
            assertEquals(listOf("MB1"), evidence.evidence.ids)
            assertEquals(mapOf("MB1" to "MB1"), evidence.trackIds)
            val key = checkNotNull(items.key(InsightItem.SONG, track, evidence, settings))
            assertEquals(CardEntity.Track(track.normalized, 200.seconds), key.entity)
            assertEquals(ItemPrompt.VERSION, key.promptVersion)
            items.write(key, InsightItem.SONG, track, evidence) { _, _ -> }
            assertTrue("the song's own story" in provider.requests.single().prompt)
            assertTrue("was a Canadian singer-songwriter" !in provider.requests.single().prompt)
        }

    @Test
    fun `the artist's insight takes the lines about its artist, the same on a solo track and a collaboration`() =
        runTest {
            val other =
                about(
                    line(SourceId.LASTFM, "Last.fm counts 900 listeners for Anne Murray.", Scope.ARTIST),
                    "Anne Murray",
                )
            val duet =
                track(
                    "Sundown",
                    listOf(line(SourceId.MUSICBRAINZ, "A duet.")),
                    artists = "Gordon Lightfoot, Anne Murray",
                    extraArtistLines = listOf(other),
                )
            val solo = track("Carefree Highway", listOf(line(SourceId.MUSICBRAINZ, "A solo.")))
            val together = checkNotNull(items.evidence(InsightItem.ARTIST, duet, settings))
            val alone = checkNotNull(items.evidence(InsightItem.ARTIST, solo, settings))
            // The lead's fuller name, "Gordon Meredith Lightfoot Jr.", is his all the same.
            assertEquals(ARTIST_LINES.map { it.text }, together.evidence.lines.map { it.text })
            assertEquals(alone.evidence.fingerprint, together.evidence.fingerprint)
            assertEquals(
                items.key(InsightItem.ARTIST, solo, alone, settings),
                items.key(InsightItem.ARTIST, duet, together, settings),
            )
        }

    @Test
    fun `a replay served whole from the cache keys the artist and album as the first play did`() =
        runTest {
            val first = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "Sundown was produced by Lenny Waronker.")))
            assertEquals(3, play(first).size)
            // Every source from the retrieval cache: no resolution, MusicBrainz's kept result holds the ids.
            val kept =
                SourceResult(
                    Outcome.SongArticle,
                    emptyList(),
                    ZERO,
                    labelArtists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
                    releaseDates = listOf(ReleaseDate("Sundown", "1974-01", "rg-sundown", "Album")),
                )
            val replay =
                first.copy(
                    resolution = null,
                    sources = first.sources + (SourceId.MUSICBRAINZ to SourceState.Done(kept, cached = true)),
                )
            // The song's is kept for the track; the artist's and the album's under the same keys.
            assertEquals(emptyList<CardKind>(), play(replay))
            assertEquals(3, provider.requests.size)
        }

    @Test
    fun `a reply that revises itself is cut to what follows, and one with nothing left is asked once more`() =
        runTest {
            val revising = FakeProvider { FakeProvider.done(REVISED) }
            val writer = ItemInsights(AiAccess(revising, Secret("k")), cache, JvmHasher, names)
            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "Sundown is a song.")))
            val evidence = checkNotNull(writer.evidence(InsightItem.SONG, track, settings))
            val key = checkNotNull(writer.key(InsightItem.SONG, track, evidence, settings))
            val written =
                assertIs<InsightState.Written>(writer.write(key, InsightItem.SONG, track, evidence) { _, _ -> })
            assertEquals("It is a song [MB1].", written.grounded.text)
            assertEquals(1, revising.requests.size)
            // Kept as written, and cut again when served.
            assertEquals(
                "It is a song [MB1].",
                assertIs<InsightState.Written>(writer.kept(key, evidence)?.state).grounded.text,
            )

            revising.reply = { FakeProvider.done(ENDS) }
            cache.cards.clear()
            assertEquals(
                InsightState.Failed(ReplyFailures.SELF_REVISED),
                writer.write(key, InsightItem.SONG, track, evidence) { _, _ -> },
            )
            assertEquals(3, revising.requests.size)
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `no call is made once the gate has closed`() =
        runTest {
            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "Sundown is a song.")))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track, settings))
            val key = checkNotNull(items.key(InsightItem.SONG, track, evidence, settings))
            val shown = mutableListOf<InsightState>()
            assertNull(
                items.write(key, InsightItem.SONG, track, evidence, mayCall = { false }) { state, _ ->
                    shown +=
                        state
                },
            )
            assertTrue(provider.requests.isEmpty())
            assertTrue(shown.isEmpty())
        }

    @Test
    fun `with MusicBrainz not reached, the artist's and album's insights are kept by the names the player gives`() =
        runTest {
            val offline =
                track(
                    "Sundown",
                    listOf(line(SourceId.WIKIPEDIA, "Sundown is a song.")),
                    resolution = Resolution.unreachable("io"),
                )
            val artist = checkNotNull(items.evidence(InsightItem.ARTIST, offline, settings))
            assertEquals(
                CardEntity.Artist("Gordon Lightfoot", null),
                checkNotNull(items.key(InsightItem.ARTIST, offline, artist, settings)).entity,
            )
            val album = checkNotNull(items.evidence(InsightItem.ALBUM, offline, settings))
            val key = checkNotNull(items.key(InsightItem.ALBUM, offline, album, settings))
            assertEquals(CardEntity.Album("Sundown", "Gordon Lightfoot", null), key.entity)
            assertNull(albums.asked.single().group)
            // Written from what the other sources found.
            assertIs<InsightState.Written>(items.write(key, InsightItem.ALBUM, offline, album) { _, _ -> })
        }

    @Test
    fun `an album no source found anything about is not written, and no call is made`() =
        runTest {
            albums.lines = emptyList()
            val track = track("Sundown", listOf(line(SourceId.WIKIPEDIA, "Sundown is a song.")))
            val evidence = checkNotNull(items.evidence(InsightItem.ALBUM, track, settings))
            val key = checkNotNull(items.key(InsightItem.ALBUM, track, evidence, settings))
            assertEquals(InsightState.NoEvidence, items.write(key, InsightItem.ALBUM, track, evidence) { _, _ -> })
            assertTrue(provider.requests.isEmpty())
            assertTrue(cache.cards.isEmpty())
        }

    @Test
    fun `nothing is offered with insights off, without an AI service, or before the evidence is in`() =
        runTest {
            val track = track("Sundown", listOf(line(SourceId.WIKIPEDIA, "Sundown is a song.")))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track, settings))
            assertNull(items.key(InsightItem.SONG, track, evidence, settings.copy(writeInsights = false)))
            assertNull(ItemInsights(null, cache, JvmHasher, names).key(InsightItem.SONG, track, evidence, settings))
            assertNull(items.evidence(InsightItem.SONG, track.copy(evidence = null), settings))
            val noAlbum = track.copy(nowPlaying = track.nowPlaying.copy(album = null))
            assertNull(items.name(InsightItem.ALBUM, noAlbum))
            assertNull(items.evidence(InsightItem.ALBUM, noAlbum, settings))
        }

    @Test
    fun `each item is its own kind of card, apart from the key insight`() =
        runTest {
            val track = track("Sundown", listOf(line(SourceId.WIKIPEDIA, "Sundown is a song.")))
            val kinds =
                InsightItem.entries.map { item ->
                    checkNotNull(
                        items.key(item, track, checkNotNull(items.evidence(item, track, settings)), settings),
                    ).kind
                }
            assertEquals(listOf(CardKind.SONG, CardKind.ARTIST, CardKind.ALBUM), kinds)
            assertNotEquals(CardKind.INSIGHT, kinds.first())
            assertNotNull(items.name(InsightItem.ALBUM, track))
        }

    private companion object {
        fun about(
            draft: EvidenceDraft,
            artist: String,
        ) = draft.copy(about = artist)

        val ARTIST_LINES =
            listOf(
                about(
                    line(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer-songwriter.", Scope.ARTIST),
                    "Gordon Lightfoot",
                ),
                about(
                    line(SourceId.WIKIPEDIA, "Gordon Meredith Lightfoot Jr. was a folk singer.", Scope.ARTIST),
                    "Gordon Lightfoot",
                ),
                about(
                    line(
                        SourceId.LASTFM,
                        "The Last.fm biography of Gordon Lightfoot says: \"A folk icon.\"",
                        Scope.ARTIST,
                    ),
                    "Gordon Lightfoot",
                ),
            )

        // A draft, a comment that cites a line, and the insight again (as `PipelineSelfRevisionTest`'s).
        const val REVISED =
            "It is a song from nothing in particular [MB1].\n\n" +
                "Wait, I need to correct that: the line says it is a song [MB1], so I'll keep only that.\n\n" +
                "It is a song [MB1]."

        // A draft, then a correction that ends the reply: nothing usable after it.
        const val ENDS =
            "It is a song from nothing in particular [MB1].\n\n" +
                "Wait, I need to correct that, so I'll keep only what the line says [MB1]."
    }
}
