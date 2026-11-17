package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.FakeMusicBrainz
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbRecording
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.resolution.credit
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import app.sleevesight.core.synthesis.SpellingPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The spelling check (#1293): asked from the web only, by the track as the player names it; a
 * spelling that is the player's own is no other spelling; another is asked of MusicBrainz once, and
 * kept only when MusicBrainz accepts a recording crediting one of the track's artists; a run with it
 * asks the sources with it, keeps the player's report, and uses its resolution.
 */
class SpellingCheckTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val names = NameMatcher(JvmAccentFolder)
    private val artists =
        "Giacomo Puccini, Giuseppe Adami, Carlo Simoni, Luciano Pavarotti, Leone Magiera, " +
            "Orchestra Da Camera Di Bologna"
    private val playing = nowPlaying("Nessun doma - Turandot", artists, "Vincerò!").copy(duration = 171.seconds)
    private val played = normalizer.normalize(playing)
    private val track =
        TrackState(
            playing,
            played,
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to
                        SourceState.Done(SourceResult(Outcome.None, emptyList(), Duration.ZERO), cached = false),
                ),
        )
    private val page =
        WebPage("https://en.wikipedia.org/wiki/Nessun_dorma", "Nessun dorma", "“Nessun dorma” is an aria.")
    private val accepted =
        Resolution(
            Outcome.SongArticle,
            recordingMbid = "4cf239b6",
            title = "Turandot: “Nessun dorma” (Vincerò)",
            artists =
                listOf(
                    ResolvedArtist("Luciano Pavarotti", "a1"),
                    ResolvedArtist("Orchestra da camera di Bologna", "a2"),
                ),
        )

    // A resolver that records the track and length it was asked with.
    private class Recording(
        var resolution: Resolution,
    ) : TrackResolver {
        val asked = mutableListOf<Pair<NormalizedTrack, Duration?>>()

        override suspend fun resolve(
            track: NormalizedTrack,
            duration: Duration?,
        ): Resolution = resolution.also { asked += track to duration }
    }

    private fun spelling(
        title: String = "Nessun dorma",
        artists: List<String> = emptyList(),
    ) = SearchAnswer.Spelling(title, artists, page.url)

    @Test
    fun `MusicBrainz is asked once with the spelling and the length, a recording crediting a performer kept`() =
        runTest {
            val resolver = Recording(accepted)
            val check = SpellingCheck(resolver, normalizer, names)
            val found = check.confirm(playing, played, spelling(), "Claude Haiku 5.5")
            assertNotNull(found)
            assertEquals(FoundAs("Nessun dorma", artists, played, "Claude Haiku 5.5", accepted), found)
            val (asked, length) = resolver.asked.single()
            assertEquals("Nessun dorma", asked.title)
            assertEquals(171.seconds, length)
        }

    @Test
    fun `a recording of other performers, or none accepted, is not kept`() =
        runTest {
            val other = accepted.copy(artists = listOf(ResolvedArtist("Plácido Domingo", "d")))
            val rejected = accepted.copy(outcome = Outcome.Rejected("no"))
            for (resolution in listOf(other, Resolution(Outcome.None), rejected)) {
                val check = SpellingCheck(Recording(resolution), normalizer, names)
                assertNull(check.confirm(playing, played, spelling(), "m"))
            }
        }

    @Test
    fun `another song of the same performer is never asked of MusicBrainz`() =
        runTest {
            val resolver = Recording(accepted)
            val check = SpellingCheck(resolver, normalizer, names)
            assertNull(check.confirm(playing, played, spelling("Vesti la giubba"), "m"))
            assertTrue(resolver.asked.isEmpty())
            // The player's extra wording may go; a letter respelled is a respelling.
            assertTrue(check.respelledTitle("Nessun dorma", playing.title, played))
            assertTrue(check.respelledTitle("Turandot", playing.title, played))
        }

    @Test
    fun `an artist MusicBrainz names without its id, not credited by the recording, does not count`() {
        val check = SpellingCheck(Recording(accepted), normalizer, names)
        val unconfirmed = accepted.copy(artists = played.artists.map { ResolvedArtist(it, null) })
        assertEquals(false, check.agrees(unconfirmed, played))
    }

    // MusicBrainz as the resolver asks it, holding [recordings] for any search.
    private fun real(vararg recordings: MbRecording): TrackResolver {
        val resolver = RecordingResolver(FakeMusicBrainz(Lookup.Found(recordings.toList())), names)
        return TrackResolver { track, duration -> resolver.resolve(track, duration) }
    }

    private fun mbRecording(credits: List<String>) =
        MbRecording(
            mbid = "4cf239b6",
            title = "Nessun dorma",
            lengthMs = 171_000,
            credits = credits.map { credit(it) },
            firstReleaseDate = "1990",
            releases = emptyList(),
            score = 100,
        )

    @Test
    fun `with MusicBrainz's resolver, a recording crediting the performer is kept`() =
        runTest {
            val check = SpellingCheck(real(mbRecording(listOf("Luciano Pavarotti"))), normalizer, names)
            val found = check.confirm(playing, played, spelling(), "m")
            assertEquals("4cf239b6", found?.resolution?.recordingMbid)
        }

    @Test
    fun `with MusicBrainz's resolver, a featured performer in the spelled title is not taken in`() =
        runTest {
            val recording = mbRecording(listOf("Luciano Pavarotti", "Plácido Domingo"))
            val check = SpellingCheck(real(recording), normalizer, names)
            assertNull(check.confirm(playing, played, spelling("Nessun dorma (feat. Plácido Domingo)"), "m"))
        }

    @Test
    fun `a spelling naming another number, a work's or a movement's, is never asked`() =
        runTest {
            val symphony =
                nowPlaying(
                    "Symphony No. 5 in C minor, Op. 67: II. Andantte con moto",
                    "Ludwig van Beethoven, Berliner Philharmoniker",
                ).copy(duration = 600.seconds)
            val normalized = normalizer.normalize(symphony)
            val resolver = Recording(accepted)
            val check = SpellingCheck(resolver, normalizer, names)
            for (title in listOf(
                "Symphony No. 6 in C minor, Op. 67: II. Andante con moto",
                "Symphony No. 5 in C minor, Op. 68: II. Andante con moto",
                "Symphony No. 5 in C minor, Op. 67: III. Andante con moto",
            )) {
                kotlin.test.assertNull(check.confirm(symphony, normalized, spelling(title), "m"), title)
            }
            assertTrue(resolver.asked.isEmpty())
            assertTrue(
                check.respelledTitle(
                    "Symphony No. 5 in C minor, Op. 67: II. Andante con moto",
                    symphony.title,
                    normalized,
                ),
            )
        }

    @Test
    fun `a spelling dropping a number is no respelling, while one dropping the work's name is`() {
        val check = SpellingCheck(Recording(accepted), normalizer, names)
        val symphony = nowPlaying("Symphony No. 5 in C minor: Allegro con brio", "Ludwig van Beethoven")
        val normalized = normalizer.normalize(symphony)
        assertEquals(
            false,
            check.respelledTitle("Symphony in C minor: Allegro con brio", symphony.title, normalized),
        )
        assertTrue(check.respelledTitle("Nessun dorma", playing.title, played))
    }

    @Test
    fun `a recording crediting only a near name of the player's artist is not kept, whatever the AI's artists`() =
        runTest {
            val theme = nowPlaying("Star Wars Main Thme", "John Williams").copy(duration = 330.seconds)
            val normalized = normalizer.normalize(theme)
            val near =
                Resolution(
                    Outcome.SongArticle,
                    recordingMbid = "sw",
                    artists = listOf(ResolvedArtist("John Williamson", "jw")),
                )
            val check = SpellingCheck(Recording(near), normalizer, names)
            val found =
                check.confirm(
                    theme,
                    normalized,
                    spelling("Star Wars Main Theme", listOf("John Williamson")),
                    "m",
                )
            assertNull(found)
        }

    @Test
    fun `the AI's artists are never used, the player's are asked`() =
        runTest {
            val resolver = Recording(accepted)
            val wrong = played.artists.map { if (it == "Luciano Pavarotti") "Plácido Domingo" else it }
            val check = SpellingCheck(resolver, normalizer, names)
            val found = check.confirm(playing, played, spelling(artists = wrong), "m")
            assertEquals(artists, found?.artists)
            assertEquals(
                played.artists,
                resolver.asked
                    .single()
                    .first.artists,
            )
        }

    // *Ask AI*'s machinery, the spelling check asked as its kind.
    private var searched = mutableListOf<String>()
    private var reply = """{"title": "Nessun dorma", "source": "${page.url}"}"""
    private val provider = FakeProvider { FakeProvider.done(reply) }
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
    private val searches =
        AiSearches(AiAccess(provider, Secret("k")), MemoryCache(), JvmHasher, answers, web = { query, _ ->
            searched += query
            WebSearchOutcome.Found(listOf(page))
        })
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val facts = SearchFacts(playing.title, played.artists, playing.album)
    private val once = AiSearches.Asking(AiWebSearch.ONCE)

    @Test
    fun `the check is asked from the web only, by the track as the player names it`() {
        assertNull(searches.key(track, SearchKind.SPELLING, settings.copy(aiWebSearch = AiWebSearch.OFF)))
        val key = assertNotNull(searches.key(track, SearchKind.SPELLING, settings))
        assertEquals(SpellingPrompt.VERSION, key.promptVersion)
        // A run in another spelling keeps the player's key.
        val respelled = normalizer.normalize(playing.copy(title = "Nessun dorma"))
        val found = track.copy(normalized = respelled, foundAs = FoundAs("Nessun dorma", artists, played, "m"))
        assertEquals(key, searches.key(found, SearchKind.SPELLING, settings))
    }

    @Test
    fun `Once searches the player's title and artists, and the answer is the spelling with its page`() =
        runTest {
            val key = assertNotNull(searches.key(track, SearchKind.SPELLING, settings))
            val last =
                searches.search(key, SearchKind.SPELLING, track, facts, { _, _ -> }, asking = once)
            assertIs<SearchState.Answered>(last)
            assertEquals(SearchAnswer.Spelling("Nessun dorma", emptyList(), page.url), last.answer)
            assertEquals(QuestionCost(searches = 1, calls = 1), last.way.cost)
            assertEquals(listOf("Nessun doma - Turandot by ${played.artists.joinToString(", ")}"), searched)
            val prompt = provider.requests.single().prompt
            assertTrue(provider.requests.single().json)
            assertTrue("Title: Nessun doma - Turandot" in prompt)
            assertTrue(SpellingPrompt.QUESTION in prompt)
        }

    @Test
    fun `the player's own spelling, or no page about it, is no other spelling`() =
        runTest {
            for (answer in listOf("""{"title": "Nessun Doma – Turandot", "source": "${page.url}"}""", "{}")) {
                reply = answer
                val cache = MemoryCache()
                val made =
                    AiSearches(AiAccess(provider, Secret("k")), cache, JvmHasher, answers, web = { _, _ ->
                        WebSearchOutcome.Found(listOf(page))
                    })
                val key = assertNotNull(made.key(track, SearchKind.SPELLING, settings))
                val last = made.search(key, SearchKind.SPELLING, track, facts, { _, _ -> }, asking = once)
                assertIs<SearchState.NothingFound>(last)
                // Kept, as "no other spelling".
                assertIs<SearchState.NothingFound>(made.kept(key, SearchKind.SPELLING, track)?.state)
            }
        }

    @Test
    fun `a run with the spelling asks the sources with it, keeps the player's report, and uses its resolution`() =
        runTest {
            val resolver = FakeResolver()
            val musicBrainz = FakeAdapter(SourceId.MUSICBRAINZ)
            val pipeline = Pipeline(PipelineParts(normalizer, listOf(musicBrainz), resolver, JvmHasher)) { Settings() }
            val found = FoundAs("Nessun dorma", artists, played, "m", accepted)
            val control = Granted(write = false).apply { grants.values.forEach { it.complete(Unit) } }
            val state = pipeline.run(playing, control, spelling = found)
            assertEquals(playing, state.nowPlaying)
            assertEquals("Nessun dorma", state.normalized.title)
            assertEquals(found, state.foundAs)
            assertEquals(played, state.played)
            assertEquals(accepted, state.resolution)
            assertEquals(0, resolver.calls)
            assertEquals(listOf(state.normalized.lookupKey), musicBrainz.asked)
        }
}
