package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.emptyPanels
import app.sleevesight.app.ui.home.searchFacts
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * *Search with AI*, the setting (#773): with it on, a panel's search starts as soon as its source has
 * nothing, with no panel open (no screen at all here), once per panel and track: not while a source
 * is still looking, not for a panel hidden in *Appearance* or an instrumental's lyrics, not after
 * *Stop* until the track plays again, not without an AI service (and once one is ready), never again
 * for a search answered; and nothing with the setting off, as by default.
 */
class AutoSearchesTest {
    private val provider = CountingProvider().apply { reply = { "{}" } }
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true))
    private val ai = AiAccess(provider, Secret("key"))
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = ai,
                cache = CardCache(),
            ),
        )
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)

    // The searches, and the trigger on them, as `AppGraph` wires them.
    private fun TestScope.searches() = AppAiSearches(parts, settings, answers, this)

    private fun auto(searches: AppAiSearches) =
        AutoSearches<SearchKind>(::emptyPanels) { track, kind ->
            searches.ask(track, kind, searchFacts(track, track.nowPlaying))
        }

    // Sundown, every source done: MusicBrainz with [musicBrainz], Last.fm with nothing; no date, genre or lyrics page.
    private fun empty(
        musicBrainz: SourceState = done(Outcome.None),
        playing: app.sleevesight.core.model.NowPlaying = HomeFixtures.sundown,
    ): TrackState =
        HomeFixtures.track(
            playing,
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to musicBrainz,
                    SourceId.LASTFM to done(),
                ),
        )

    // The kinds asked, by their prompts' shapes.
    private fun asked(): List<SearchKind> =
        provider.prompts.map { prompt ->
            when {
                "\"album_date\"" in prompt -> SearchKind.DATES
                "\"genres\"" in prompt -> SearchKind.GENRES
                else -> SearchKind.LYRICS
            }
        }

    @Test
    fun `it is off by default, and then searches nothing`() =
        runTest {
            assertFalse(Settings().searchWithAi)
            settings.value = Settings(aiProvider = ProviderId.ANTHROPIC)
            auto(searches()).follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `with no panel open, each empty panel's search starts, once`() =
        runTest {
            val auto = auto(searches())
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(setOf(SearchKind.DATES, SearchKind.GENRES, SearchKind.LYRICS), asked().toSet())
            assertEquals(3, provider.requests)
            // The pipeline publishes the track again (a later label, a look again): nothing more.
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(3, provider.requests)
        }

    @Test
    fun `nothing starts while a source is still looking`() =
        runTest {
            val auto = auto(searches())
            auto.follow(empty(SourceState.Pending), settings.value)
            auto.follow(empty().copy(genresAssembled = false), settings.value)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            // Once it has answered, they start.
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(3, provider.requests)
        }

    @Test
    fun `a panel hidden in Appearance, or an instrumental's lyrics, is not searched`() =
        runTest {
            settings.value = settings.value?.copy(showGenres = false, lyricsLinks = false)
            auto(searches()).follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(listOf(SearchKind.DATES), asked())
            // An instrumental: dates and genres, no lyrics.
            settings.value = settings.value?.copy(showGenres = true, lyricsLinks = true)
            val instrumental =
                SourceState.Done(
                    SourceResult(
                        Outcome.SongArticle,
                        emptyList(),
                        10.milliseconds,
                        lyrics = WorkLyrics(instrumental = true),
                    ),
                    cached = false,
                )
            auto(searches()).follow(empty(instrumental, HomeFixtures.carefree), settings.value)
            advanceUntilIdle()
            assertEquals(listOf(SearchKind.DATES, SearchKind.DATES, SearchKind.GENRES), asked().sorted())
        }

    @Test
    fun `after Stop it does not start again until the track plays again`() =
        runTest {
            settings.value = settings.value?.copy(showGenres = false, lyricsLinks = false)
            provider.streaming = 10.seconds
            val searches = searches()
            val auto = auto(searches)
            auto.follow(empty(), settings.value)
            advanceTimeBy(1.seconds)
            searches.stop(empty(), SearchKind.DATES)
            advanceUntilIdle()
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertEquals(0, provider.completed)
            // Another track, then this one again: it starts again.
            provider.streaming = 0.seconds
            auto.follow(empty(playing = HomeFixtures.carefree), settings.value)
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(3, provider.requests)
        }

    @Test
    fun `a search answered is never asked again, the track playing again or not`() =
        runTest {
            settings.value = settings.value?.copy(showGenres = false, lyricsLinks = false)
            val auto = auto(searches())
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            auto.follow(empty(playing = HomeFixtures.carefree), settings.value)
            advanceUntilIdle()
            auto.follow(empty(), settings.value)
            advanceUntilIdle()
            // Sundown once, Carefree Highway once.
            assertEquals(2, provider.requests)
        }

    @Test
    fun `without an AI service nothing starts, and once one is ready it does`() =
        runTest {
            settings.value = settings.value?.copy(showGenres = false, lyricsLinks = false)
            val ready = parts.value
            parts.value =
                ready?.let { PipelineParts(it.normalizer, it.adapters, it.resolver, it.hasher, null, it.cache) }
            val searches = searches()
            val auto = auto(searches)
            val following = launch { auto.run(MutableStateFlow(empty()), settings, parts) }
            runCurrent()
            assertEquals(0, provider.requests)
            // A key saved: the parts are rebuilt with the AI service.
            parts.value = ready
            runCurrent()
            advanceUntilIdle()
            following.cancel()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `with Show insights off nothing starts`() =
        runTest {
            settings.value = settings.value?.copy(writeInsights = false)
            auto(searches()).follow(empty(), settings.value)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }
}
