package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.VocalsFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiQuestion
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.VocalsChecks
import app.sleevesight.core.pipeline.VocalsState
import app.sleevesight.core.pipeline.withLibrary
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check of a track's vocals in the app (#998): started by itself with *Ask AI automatically* on,
 * by its owner (`AutoSearches`, as `AppGraph` wires it), which follows the genres once settled and
 * starts it again once the app is back on screen; or by a tap on *Why is this instrumental?*, held
 * until the free steps are done, then the reason, none after vocals; its searches and calls counted
 * under *AI web search*; its answer kept per track, so a replay asks nothing.
 */
class AppGenreVocalsTest {
    private val clock = MovedClock()
    private val cache = CardCache(clock = clock)
    private val queries = mutableListOf<String>()

    // What the check answers.
    private var said = "vocals"

    // The page found and cited, which says the answer in words (#1053): the fixture's instrumental
    // page, or one that says it is sung.
    private val sung =
        WebPage("https://bandcamp.example/nasnusa-sung", "Nasnusa", "Nasnusa, sung by Yurika Hanashima.")

    private fun cited(): WebPage = if (said == "instrumental") VocalsFixtures.page else sung

    // While set, a search waits for it: the app may leave meanwhile.
    private var searching: CompletableDeferred<Unit>? = null

    // The check answers [said], citing the page found; any call after it is a reason.
    private val provider =
        CountingProvider().apply {
            reply = { n ->
                if (n == 1) {
                    """{"answer": "$said", "source": "${cited().url}"}"""
                } else {
                    "It is tagged so [MB1]."
                }
            }
        }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                webSearch = { query, _ ->
                    queries += query
                    searching?.await()
                    WebSearchOutcome.Found(listOf(cited()))
                },
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, searchWithAi = true))

    private val definitions =
        GenreDefinitions(
            object : GenreArticles {
                override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> = Lookup.NotFound
            },
            VocalsFixtures.vocabulary,
        )

    private val paid = mutableListOf<QuestionCost>()
    private val events = mutableListOf<PipelineEvent>()

    // The check's owner, as `AppGraph` wires it, and the notes it starts the check through.
    private lateinit var notes: AppGenreNotes
    private val auto =
        AutoSearches<Unit>(
            { track, now -> if (vocalsWanted(track, now)) setOf(Unit) else emptySet() },
            { track, _ -> Triple(track.normalized, track.nowPlaying.duration, VocalsChecks.subject(track)) },
        ) { track, _ -> notes.checkVocals(track) }

    private fun TestScope.notes(): AppGenreNotes =
        AppGenreNotes(
            definitions,
            parts,
            settings,
            this,
            clock = clock,
            paid = { paid += it },
            events = { events += it },
            automaticOpen = { auto.open() },
            releaseAutomatic = { auto.release(it, Unit) },
            playing = { playing },
        ).also { notes = it }

    private val track = VocalsFixtures.nasnusa

    // The playing track, as the pipeline holds it.
    private var playing: TrackState? = track

    // The genres settled (none told when [genres] is null), followed by the owner as `AppGraph` has it
    // follow them: only those of the play the pipeline holds; on screen or not.
    private fun follow(
        genres: TrackState? = track,
        onScreen: Boolean = true,
    ) {
        genres?.let { notes.genresShown(it, settled = true) }
        val now = playing
        val shown = notes.settledTracks.value?.takeIf { now != null && samePlay(it, now) }
        auto.follow(shown, settings.value, onScreen)
    }

    @Test
    fun `with Ask AI automatically on, the check runs by itself, once, counted under AI web search`() =
        runTest {
            notes()
            follow()
            follow()
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertEquals(1, queries.size)
            assertTrue("instrumental" in queries.single() && "vocals" in queries.single())
            val shown = notes.vocals(track).first { it != null }
            assertEquals(VocalsAnswer.VOCALS, (shown?.state as VocalsState.Checked).answer)
            assertFalse(shown.tapped)
            assertEquals(listOf(QuestionCost.SEARCH, QuestionCost.CALL), paid)
            val asked = events.filterIsInstance<PipelineEvent.AiAsked>().single()
            assertEquals(AiQuestion.Vocals, asked.question)
            assertTrue(asked.automatic)
        }

    @Test
    fun `it does not run by itself with the setting off, off screen, with AI web search off, or no sign`() =
        runTest {
            notes()
            settings.value = settings.value?.copy(searchWithAi = false)
            follow()
            settings.value = settings.value?.copy(searchWithAi = true)
            follow(onScreen = false)
            follow(VocalsFixtures.biteNoSign)
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            follow()
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertTrue(queries.isEmpty())
            // Composers alone call for it.
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.ONCE)
            assertTrue(vocalsWanted(VocalsFixtures.bite, checkNotNull(settings.value)))
        }

    @Test
    fun `leaving during the search stops it before its call, and coming back on the same play asks again`() =
        runTest {
            notes()
            searching = CompletableDeferred()
            follow()
            advanceUntilIdle()
            assertEquals(1, queries.size)
            // The app leaves while Exa answers: the call is not made.
            follow(onScreen = false)
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertNull(notes.vocals(track).first())
            // Back on screen, the same play: the check starts again, and answers.
            searching = null
            follow()
            advanceUntilIdle()
            assertEquals(2, queries.size)
            assertEquals(1, provider.requests)
            val shown = notes.vocals(track).first { it != null }
            assertEquals(VocalsAnswer.VOCALS, (shown?.state as VocalsState.Checked).answer)
        }

    @Test
    fun `switching the AI service off while Exa answers makes no call`() =
        runTest {
            notes()
            searching = CompletableDeferred()
            follow()
            advanceUntilIdle()
            settings.value = settings.value?.copy(aiProvider = null)
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(1, queries.size)
            assertEquals(0, provider.requests)
        }

    @Test
    fun `lyrics Find the lyrics finds while Exa answers settle it, and no call is made`() =
        runTest {
            notes()
            searching = CompletableDeferred()
            follow()
            advanceUntilIdle()
            notes.genresShown(track.copy(genres = track.genres.withLyricsFound()), settled = true)
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(1, queries.size)
            assertEquals(0, provider.requests)
        }

    @Test
    fun `MusicBrainz's lyrics in the run's genres while Exa answers settle it too`() =
        runTest {
            notes()
            searching = CompletableDeferred()
            follow()
            advanceUntilIdle()
            playing = track.copy(genres = track.genres.copy(singing = track.genres.singing.copy(lyrics = true)))
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `left during A's search, back after B began, A is not asked again, nor in Settings`() =
        runTest {
            notes()
            searching = CompletableDeferred()
            follow()
            advanceUntilIdle()
            follow(genres = null, onScreen = false)
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            // B plays while the app is away; back on screen (Home, or Settings, which tells nothing),
            // A's genres, told last, start nothing.
            searching = null
            playing = VocalsFixtures.biteNoSign
            follow(genres = null)
            advanceUntilIdle()
            assertEquals(1, queries.size)
            assertEquals(0, provider.requests)
            assertFalse(notes.checkVocals(track))
        }

    @Test
    fun `a track change while Exa answers makes no call for the track before`() =
        runTest {
            notes()
            searching = CompletableDeferred()
            follow()
            advanceUntilIdle()
            playing = VocalsFixtures.biteNoSign
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `the same recording from another album starts its own check`() =
        runTest {
            notes()
            follow()
            advanceUntilIdle()
            val deluxe = track.copy(nowPlaying = track.nowPlaying.copy(album = "Kokoro (Deluxe)"))
            playing = deluxe
            follow(deluxe)
            advanceUntilIdle()
            assertEquals(2, queries.size)
            assertEquals(2, provider.requests)
        }

    @Test
    fun `a tap runs the check first, then the reason, and the answer is the tap's on this play`() =
        runTest {
            said = "instrumental"
            settings.value = settings.value?.copy(searchWithAi = false)
            notes()
            notes.genresShown(track, settled = true)
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            assertTrue("Does this track have vocals?" in provider.prompts[0])
            assertFalse("Does this track have vocals?" in provider.prompts[1])
            val shown = notes.vocals(track).first { it != null }
            assertTrue(shown?.tapped == true)
            assertEquals(AiQuestion.Vocals, events.filterIsInstance<PipelineEvent.AiAsked>().first().question)
            assertFalse(events.filterIsInstance<PipelineEvent.AiAsked>().first().automatic)
        }

    @Test
    fun `a tap whose check finds vocals writes no reason, then or on a second tap`() =
        runTest {
            settings.value = settings.value?.copy(searchWithAi = false)
            notes()
            notes.genresShown(track, settled = true)
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(notes.vocals(track).first { it != null }?.tapped == true)
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(events.filterIsInstance<PipelineEvent.AiAsked>().none { it.question != AiQuestion.Vocals })
        }

    @Test
    fun `a tap while LRCLIB is held back waits, and its lyrics then settle it with nothing paid`() =
        runTest {
            settings.value = settings.value?.copy(searchWithAi = false)
            notes()
            val before = VocalsFixtures.nasnusaBeforeLibrary
            notes.genresShown(before, settled = false)
            notes.explain(before, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertTrue(queries.isEmpty())
            // Held, it shows as the check.
            assertEquals(VocalsState.Checking, notes.vocals(before).first { it != null }?.state)
            // LRCLIB answers with lyrics: Instrumental is gone, nothing to check or explain.
            notes.genresShown(before.copy(genres = before.genres.withLibrary(LibraryLyrics.LYRICS)), settled = true)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertTrue(queries.isEmpty())
        }

    @Test
    fun `with no check possible a tap writes the reason at once, settled or not`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF, searchWithAi = false)
            notes()
            val before = VocalsFixtures.nasnusaBeforeLibrary
            notes.genresShown(before, settled = false)
            notes.explain(before, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(queries.isEmpty())
            assertFalse("Does this track have vocals?" in provider.prompts.single())
        }

    @Test
    fun `a tapped check stopped by lyrics Find the lyrics found writes no reason`() =
        runTest {
            settings.value = settings.value?.copy(searchWithAi = false)
            notes()
            searching = CompletableDeferred()
            notes.genresShown(track, settled = true)
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(1, queries.size)
            notes.genresShown(track.copy(genres = track.genres.withLyricsFound()), settled = true)
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `a tapped check stopped by AI web search switched off writes the reason after all`() =
        runTest {
            settings.value = settings.value?.copy(searchWithAi = false)
            notes()
            searching = CompletableDeferred()
            notes.genresShown(track, settled = true)
            notes.explain(track, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            checkNotNull(searching).complete(Unit)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertFalse("Does this track have vocals?" in provider.prompts.single())
        }

    @Test
    fun `a tap while LRCLIB is held back, which then finds nothing, checks then`() =
        runTest {
            settings.value = settings.value?.copy(searchWithAi = false)
            notes()
            val before = VocalsFixtures.nasnusaBeforeLibrary
            notes.genresShown(before, settled = false)
            notes.explain(before, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            notes.genresShown(before.copy(genres = before.genres.withLibrary(LibraryLyrics.NONE)), settled = true)
            advanceUntilIdle()
            assertEquals(1, queries.size)
            assertEquals(1, provider.requests)
        }

    @Test
    fun `the answer is kept per track, so a replay asks nothing`() =
        runTest {
            notes()
            follow()
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            // The next play, in a new process: the answer is read from the cache, not asked.
            val replay = track.copy(nowPlaying = track.nowPlaying.copy(play = 2))
            playing = replay
            val again = notes()
            val shown = again.vocals(replay).first { it != null }
            assertEquals(VocalsAnswer.VOCALS, (shown?.state as VocalsState.Checked).answer)
            assertFalse(shown.tapped)
            follow(replay)
            again.explain(replay, TrackGenres.INSTRUMENTAL)
            advanceUntilIdle()
            // Nothing: the check is kept, and a track found sung gets no reason.
            assertEquals(1, provider.requests)
            assertEquals(1, queries.size)
        }

    @Test
    fun `with AI web search off there is no check`() =
        runTest {
            settings.value = settings.value?.copy(aiWebSearch = AiWebSearch.OFF)
            notes()
            assertFalse(notes.checkVocals(track))
            advanceUntilIdle()
            assertEquals(0, provider.requests)
            assertNull(notes.vocals(track).first())
        }
}
