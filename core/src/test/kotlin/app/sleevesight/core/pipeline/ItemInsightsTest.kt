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
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.LanguageSubject
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.testing.MemoryLanguageLines
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // The album's lines, as `AlbumSource` gives them, the same whichever track asks; with [web], a
    // web search for it, its lines given by a lookup once a search kept them (#885).
    private class FakeAlbums(
        var lines: List<EvidenceDraft> =
            listOf(
                line(SourceId.MUSICBRAINZ, "“Sundown” is an album by Gordon Lightfoot.", Scope.RELEASE),
                line(SourceId.WIKIPEDIA, "Sundown is the tenth album by Gordon Lightfoot.", Scope.RELEASE),
            ),
        val web: List<EvidenceDraft>? = null,
    ) : AlbumLookup {
        val asked = mutableListOf<AlbumQuery>()
        var searches = 0
        val renewed = mutableListOf<AlbumQuery>()
        private var kept: List<EvidenceDraft>? = null

        override suspend fun renewWeb(query: AlbumQuery) {
            renewed += query
        }

        override suspend fun lookup(query: AlbumQuery): AlbumFound {
            asked += query
            val searched = kept?.takeIf { query.web }
            return AlbumFound(lines + searched.orEmpty(), webDue = web != null && query.web && searched == null)
        }

        override val searchesWeb: Boolean get() = web != null

        // As `AlbumSource`'s: the gate asked right before the request, the lines given before they are kept.
        override suspend fun searchWeb(
            query: AlbumQuery,
            mayPay: () -> Boolean,
            keeping: (List<EvidenceDraft>) -> Unit,
            sent: () -> Unit,
        ): List<EvidenceDraft>? =
            when {
                web == null || !query.web -> null
                kept != null -> kept
                !mayPay() -> null
                else ->
                    web.also {
                        searches++
                        sent()
                        keeping(it)
                        kept = it
                    }
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
            // As the run assembles it: the player's release line, then the sources'.
            evidence = EvidenceSet.of(playerLines(playing, RETRIEVED) + drafts, JvmHasher),
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
            assertEquals(listOf("MB1", "P1"), evidence.evidence.ids)
            assertEquals(mapOf("MB1" to "MB1", "P1" to "P1"), evidence.trackIds)
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
    fun `an item is written from the run's own evidence under the switches now, not an earlier card's`() =
        runTest {
            val run = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "Sundown was produced by Lenny Waronker.")))
            // As Home shows the track beside an earlier card (`PipelineHost.withCard`): its evidence,
            // with a line the run does not have.
            val card =
                EvidenceSet.of(
                    listOf(line(SourceId.WIKIPEDIA, "An old line about Sundown.")) + ARTIST_LINES,
                    JvmHasher,
                )
            val shown = run.copy(evidence = card)
            // Wikipedia, which the card cited, switched off since.
            val off = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WIKIPEDIA)

            val song = checkNotNull(items.evidence(InsightItem.SONG, shown, off))
            assertEquals(
                listOf("Sundown was produced by Lenny Waronker.", "This recording is on the release \"Sundown\"."),
                song.evidence.lines.map { it.text },
            )
            val artist = checkNotNull(items.evidence(InsightItem.ARTIST, shown, off))
            assertTrue(artist.evidence.lines.none { it.source == SourceId.WIKIPEDIA })
            val key = checkNotNull(items.key(InsightItem.SONG, shown, song, off))
            items.write(key, InsightItem.SONG, shown, song) { _, _ -> }
            assertTrue("An old line" !in provider.requests.single().prompt)
            assertTrue("Gordon Lightfoot was a Canadian" !in provider.requests.single().prompt)
        }

    @Test
    fun `the artist keys the same whatever Last fm's counts are on each lookup`() =
        runTest {
            fun counted(listeners: Int) =
                line(SourceId.LASTFM, "Last.fm counts $listeners listeners for Gordon Lightfoot.", Scope.ARTIST)
                    .copy(aggregate = true, about = "Gordon Lightfoot")
            val first =
                track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "One.")), extraArtistLines = listOf(counted(683942)))
            val next =
                track(
                    "Carefree Highway",
                    listOf(line(SourceId.MUSICBRAINZ, "Two.")),
                    extraArtistLines = listOf(counted(684001)),
                )
            val keys =
                listOf(first, next).map { t ->
                    checkNotNull(
                        items.key(
                            InsightItem.ARTIST,
                            t,
                            checkNotNull(items.evidence(InsightItem.ARTIST, t, settings)),
                            settings,
                        ),
                    )
                }
            assertEquals(keys.first(), keys.last())
        }

    @Test
    fun `no key while the selected AI service is not the one the parts were built with`() =
        runTest {
            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "Sundown is a song.")))
            val evidence = checkNotNull(items.evidence(InsightItem.SONG, track, settings))
            assertNull(items.key(InsightItem.SONG, track, evidence, settings.copy(aiProvider = ProviderId.OPENAI)))
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

    @Test
    fun `only the album's card keeps its web lines again, and only once kept`() =
        runTest {
            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "It runs three minutes.")))
            for (item in listOf(InsightItem.SONG, InsightItem.ARTIST)) {
                val evidence = checkNotNull(items.evidence(item, track, settings))
                items.write(checkNotNull(items.key(item, track, evidence, settings)), item, track, evidence) { _, _ -> }
            }
            assertTrue(albums.renewed.isEmpty())
            val evidence = checkNotNull(items.evidence(InsightItem.ALBUM, track, settings))
            items.write(
                checkNotNull(items.key(InsightItem.ALBUM, track, evidence, settings)),
                InsightItem.ALBUM,
                track,
                evidence,
            ) { _, _ -> }
            assertEquals(1, albums.renewed.size)
        }

    // An artist-level line about Gordon Lightfoot found in the track's [language] (#902).
    private fun inLanguage(
        text: String,
        language: String,
    ): EvidenceDraft = about(line(SourceId.WIKIPEDIA, text, Scope.ARTIST), "Gordon Lightfoot").copy(language = language)

    @Test
    fun `the artist's lines in the track's language are kept per artist, the first found in each language`() =
        runTest {
            val kept = MemoryLanguageLines()
            val items = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, languageLines = kept)
            val gordon = LanguageSubject.Artist("Gordon Lightfoot", "mb-gordon")
            val songLines = listOf(line(SourceId.MUSICBRAINZ, "It runs three minutes."))
            val korean = track("Sundown", songLines, extraArtistLines = listOf(inLanguage("고든 라이트풋은 가수이다.", "ko")))

            fun texts(evidence: ItemEvidence?) = checkNotNull(evidence).evidence.lines.map { it.text }

            assertTrue("고든 라이트풋은 가수이다." in texts(items.evidence(InsightItem.ARTIST, korean, settings)))
            assertEquals(listOf("고든 라이트풋은 가수이다."), kept.kept(gordon).map { it.text })

            // Another Korean line does not replace the first; a Japanese one is kept beside it.
            val more =
                listOf(inLanguage("고든 라이트풋은 캐나다의 가수이다.", "ko"), inLanguage("ゴードン・ライトフットは歌手である。", "ja"))
            val both = track("Carefree Highway", songLines, extraArtistLines = more)
            val bothLines = texts(items.evidence(InsightItem.ARTIST, both, settings))
            assertEquals(
                listOf("ゴードン・ライトフットは歌手である。", "고든 라이트풋은 가수이다."),
                kept.kept(gordon).map { it.text },
            )

            // A track searched in no language takes what was kept, after its source's English lines; so
            // does one that found other lines in a language kept: the same lines on every track.
            val plain = texts(items.evidence(InsightItem.ARTIST, track("Rainy Day People", songLines), settings))
            val (wikipedia, lastFm) = ARTIST_LINES.partition { it.source == SourceId.WIKIPEDIA }
            assertEquals((wikipedia + kept.kept(gordon) + lastFm).map { it.text }, plain)
            assertEquals(plain, bothLines)

            // Off: none, and nothing more is kept.
            val off = settings.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
            val other =
                track(
                    "Sundown",
                    songLines,
                    extraArtistLines = listOf(inLanguage("Gordon Lightfoot est un chanteur.", "fr")),
                )
            assertEquals(ARTIST_LINES.map { it.text }, texts(items.evidence(InsightItem.ARTIST, other, off)))
            assertEquals(2, kept.kept(gordon).size)
        }

    @Test
    fun `a card written from lines in the track's language keeps them again, and only then`() =
        runTest {
            val kept = MemoryLanguageLines()
            val items =
                ItemInsights(
                    AiAccess(provider, Secret("k")),
                    cache,
                    JvmHasher,
                    names,
                    albums = albums,
                    languageLines = kept,
                )
            val gordon = LanguageSubject.Artist("Gordon Lightfoot", "mb-gordon")
            val sundown = LanguageSubject.Album("Sundown", "Gordon Lightfoot", "rg-sundown")
            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "It runs three minutes.")))

            suspend fun writeAll(settings: Settings) {
                for (item in InsightItem.entries) {
                    val evidence = checkNotNull(items.evidence(item, track, settings))
                    val key = checkNotNull(items.key(item, track, evidence, settings))
                    items.write(key, item, track, evidence) { _, _ -> }
                }
            }

            // No line in a language in the cards' evidence: nothing kept again.
            writeAll(settings)
            assertTrue(kept.renewed.isEmpty())

            kept.keep(gordon, "ko", listOf(inLanguage("고든 라이트풋은 가수이다.", "ko")))
            val release = line(SourceId.WIKIPEDIA, "선다운은 앨범이다.", Scope.RELEASE).copy(about = "Sundown", language = "ko")
            kept.keep(sundown, "ko", listOf(release))
            // Under Off, or with their source switched off, the cards hold none: nothing kept again.
            writeAll(settings.copy(trackLanguageSearch = TrackLanguageSearch.OFF))
            writeAll(settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WIKIPEDIA))
            assertTrue(kept.renewed.isEmpty())

            writeAll(settings)
            assertEquals(listOf<LanguageSubject>(gordon, sundown), kept.renewed)
        }

    @Test
    fun `what the search in the language found is kept when the track's evidence is in, no insight opened`() =
        runTest {
            val kept = MemoryLanguageLines()
            val items = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, languageLines = kept)
            val release = line(SourceId.WIKIPEDIA, "선다운은 앨범이다.", Scope.RELEASE).copy(about = "Sundown", language = "ko")
            val songLines = listOf(line(SourceId.MUSICBRAINZ, "It runs three minutes."), release)
            val artist = inLanguage("고든 라이트풋은 가수이다.", "ko")
            val searched = track("Sundown", songLines, extraArtistLines = listOf(artist))

            items.keepInLanguage(searched, settings.copy(trackLanguageSearch = TrackLanguageSearch.OFF))
            assertTrue(kept.kept(LanguageSubject.Artist("Gordon Lightfoot", "mb-gordon")).isEmpty())

            items.keepInLanguage(searched, settings)
            assertEquals(
                listOf(artist.text),
                kept.kept(LanguageSubject.Artist("Gordon Lightfoot", "mb-gordon")).map { it.text },
            )
            val sundown = LanguageSubject.Album("Sundown", "Gordon Lightfoot", "rg-sundown")
            assertEquals(listOf(release.text), kept.kept(sundown).map { it.text })

            // Another track, not searched, takes them.
            val plain = track("Carefree Highway", listOf(line(SourceId.MUSICBRAINZ, "It runs four minutes.")))
            val album = checkNotNull(items.evidence(InsightItem.ALBUM, plain, settings)).evidence.lines.map { it.text }
            assertTrue(release.text in album)
            val artistLines =
                checkNotNull(
                    items.evidence(InsightItem.ARTIST, plain, settings),
                ).evidence.lines.map { it.text }
            assertTrue(artist.text in artistLines)
        }

    // [settings] with web search switched on, through an engine.
    private val webOn =
        settings.copy(
            enabledSources = settings.enabledSources + SourceSwitch.WEB,
            webEngines = setOf(WebEngine.EXA),
        )

    private val webLine =
        EvidenceDraft(
            SourceId.WEB,
            Scope.WEB,
            "Sundown, Gordon Lightfoot's 1974 album, topped the Billboard 200.",
            "https://example.com/sundown",
            RETRIEVED,
        )

    @Test
    fun `the album's web search is made only for a write, and its insight is found again once it is kept`() =
        runTest {
            val web = FakeAlbums(web = listOf(webLine))
            val items = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, albums = web)
            val first = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "It runs three minutes.")))
            // Gathered for the row, its key looked up: no search.
            val gathered = checkNotNull(items.evidence(InsightItem.ALBUM, first, webOn))
            assertTrue(gathered.webDue)
            assertTrue(gathered.evidence.lines.none { it.source == SourceId.WEB })
            val before = checkNotNull(items.key(InsightItem.ALBUM, first, gathered, webOn))
            assertNull(items.kept(before, gathered))
            assertEquals(0, web.searches)

            // Web search switched off since the evidence was gathered: no search.
            val off = webOn.copy(enabledSources = webOn.enabledSources - SourceSwitch.WEB)
            assertNull(items.searched(InsightItem.ALBUM, first, off, gathered))
            assertEquals(0, web.searches)

            // The write's search: the web's lines join the album's, under another key, its request told.
            var sent = 0
            val searched = checkNotNull(items.searched(InsightItem.ALBUM, first, webOn, gathered) { sent++ })
            assertEquals(1, web.searches)
            assertEquals(1, sent)
            assertEquals(listOf("MB1", "W1", "S1"), searched.evidence.ids)
            val key = checkNotNull(items.key(InsightItem.ALBUM, first, searched, webOn))
            assertNotEquals(before, key)
            assertIs<InsightState.Written>(items.write(key, InsightItem.ALBUM, first, searched) { _, _ -> })
            assertTrue(webLine.text in provider.requests.single().prompt)
            // Its web lines kept again with the card written from them.
            assertEquals(listOf("Sundown"), web.renewed.map { it.title })

            // The next track of the album gathers the kept lines, with no search, and finds the insight.
            val next = track("Carefree Highway", listOf(line(SourceId.MUSICBRAINZ, "It was a hit.")))
            val again = checkNotNull(items.evidence(InsightItem.ALBUM, next, webOn))
            assertFalse(again.webDue)
            assertEquals(key, items.key(InsightItem.ALBUM, next, again, webOn))
            assertNotNull(items.kept(key, again))
            assertNull(items.searched(InsightItem.ALBUM, next, webOn, again))
            assertEquals(1, web.searches)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `the album's search is refused when its gate closes, and the key its lines give is reserved first`() =
        runTest {
            val web = FakeAlbums(web = listOf(webLine))
            val items = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, albums = web)
            val off = webOn.copy(enabledSources = webOn.enabledSources - SourceSwitch.WEB)
            // Eligibility, in what the evidence is gathered for: web search on and a lookup that can search.
            assertTrue(items.searchesWeb(InsightItem.ALBUM, webOn))
            assertFalse(items.searchesWeb(InsightItem.ALBUM, off))
            val noEngine = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, albums = FakeAlbums())
            assertFalse(noEngine.searchesWeb(InsightItem.ALBUM, webOn))

            val track = track("Sundown", listOf(line(SourceId.MUSICBRAINZ, "It runs three minutes.")))
            val gathered = checkNotNull(items.evidence(InsightItem.ALBUM, track, webOn))
            assertSame(ItemSearch.Refused, items.search(InsightItem.ALBUM, track, webOn, gathered, mayPay = { false }))
            assertEquals(0, web.searches)

            val reserved = mutableListOf<CardKey>()
            val found =
                assertIs<ItemSearch.Found>(
                    items.search(InsightItem.ALBUM, track, webOn, gathered, reserve = { key ->
                        reserved += key
                        true
                    }),
                )
            assertEquals(1, web.searches)
            assertEquals(listOf(items.key(InsightItem.ALBUM, track, found.evidence, webOn)), reserved)
        }

    @Test
    fun `no web search for the album while web search is off`() =
        runTest {
            val web = FakeAlbums(web = listOf(webLine))
            val items = ItemInsights(AiAccess(provider, Secret("k")), cache, JvmHasher, names, albums = web)
            val track = track("Sundown", emptyList())
            val off = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.WEB)
            val evidence = checkNotNull(items.evidence(InsightItem.ALBUM, track, off))
            assertFalse(evidence.webDue)
            assertFalse(web.asked.single().web)
            assertNull(items.searched(InsightItem.ALBUM, track, off, evidence))
            assertEquals(0, web.searches)
        }

    @Test
    fun `the album asks Last fm and the web as Sources has them switched`() =
        runTest {
            val track = track("Sundown", emptyList())
            items.evidence(InsightItem.ALBUM, track, webOn)
            val off = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.LASTFM - SourceSwitch.WEB)
            items.evidence(InsightItem.ALBUM, track, off)
            assertEquals(listOf(true to true, false to false), albums.asked.map { it.lastFm to it.web })
        }

    @Test
    fun `the album's key is the same whatever the counts of what many listeners did`() =
        runTest {
            val track = track("Sundown", emptyList())

            fun counted(listeners: Int) =
                line(SourceId.LASTFM, "Last.fm counts $listeners listeners for Sundown.", Scope.RELEASE)
                    .copy(aggregate = true)
            val keys =
                listOf(1000, 1001).map { listeners ->
                    albums.lines = albums.lines.filterNot { it.aggregate } + counted(listeners)
                    val evidence = checkNotNull(items.evidence(InsightItem.ALBUM, track, settings))
                    assertTrue(evidence.evidence.lines.none { it.aggregate })
                    checkNotNull(items.key(InsightItem.ALBUM, track, evidence, settings))
                }
            assertEquals(1, keys.distinct().size)
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
