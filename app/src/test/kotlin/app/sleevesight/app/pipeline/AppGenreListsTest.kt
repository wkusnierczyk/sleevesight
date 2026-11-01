package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.ModelNames
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.KeptCard
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.GenreListPrompt
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
import kotlin.time.Instant

/**
 * *Why is this …?* for a genre only MusicBrainz gives, in the app (#762): the AI's genre list asked
 * first, naming no genre, then the reason when the genre is on it, two calls on a tap; the list kept
 * as a reason is, and joined to the track's genres; and the button disabled, with why, where neither
 * can run.
 */
class AppGenreListsTest {
    private val provider = CountingProvider().apply { reply = { "Its loud guitars are rock [MB1]." } }

    // A clock the tests move.
    private val clock = MovedClock()

    // A cache that says when each card was kept: the time given, or its own reading of [clock].
    private val cache = CardCache(clock = clock)
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    // No definition is looked up here: the tests are about the reasons.
    private val articles =
        object : GenreArticles {
            override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> = Lookup.NotFound
        }
    private val definitions = GenreDefinitions(articles, GenreVocabulary.load(JvmResourceReader))

    private fun label(
        tag: String,
        source: LabelSource = LabelSource.LASTFM,
    ) = TrackLabel(
        GenreLabel(tag, "rock", tag.replaceFirstChar(Char::uppercase), tag, null, GenreLevel.ARTIST, "A"),
        listOf(LabelCredit(source, null)),
    )

    private fun TestScope.notes(epoch: ClearEpoch = ClearEpoch()) =
        AppGenreNotes(definitions, parts, settings, this, epoch, clock = clock)

    // A track whose artist row has rock from Last.fm and punk from MusicBrainz alone.
    private fun mixed(): TrackState {
        val row = GenreRow(GenreLevel.ARTIST, "A", listOf(label("rock"), label("punk", LabelSource.MUSICBRAINZ)))
        return HomeFixtures.track().copy(genres = TrackGenres(listOf(row)))
    }

    // *AI service knowledge* on (off by default), so the AI's genre list can run (#762).
    private fun knowing() {
        val now = checkNotNull(settings.value)
        settings.value = now.copy(enabledSources = now.enabledSources + SourceSwitch.MODEL)
    }

    private val model: String get() = checkNotNull(settings.value?.model(ProviderId.ANTHROPIC))

    @Test
    fun `a genre only MusicBrainz gives is listed by the AI first, then written, two calls (#762)`() =
        runTest {
            knowing()
            provider.reply = { n -> if (n == 1) LIST else "Its loud guitars are punk [MB1]." }
            val notes = notes()
            val track = mixed()
            assertEquals(GenreReason.Offered, notes.reason(track, "punk").first())
            assertNull(notes.aiGenres(track).first())
            notes.explain(track, "punk")
            // A second tap while it is under way asks nothing more.
            notes.explain(track, "punk")
            advanceUntilIdle()
            val shown = notes.reason(track, "punk").first() as GenreReason.Shown
            assertTrue(shown.state is InsightState.Written)
            assertEquals(ModelNames.of(model) ?: model, shown.model)
            assertEquals(2, provider.requests)
            // The genres call names no genre; the reason's names the genre, now the AI's.
            assertTrue(GenreListPrompt.QUESTION in provider.prompts[0])
            assertFalse("punk" in provider.prompts[0].lowercase())
            assertFalse("rock" in provider.prompts[0].lowercase())
            assertTrue("\"Punk\"" in provider.prompts[1])
            // The list joins the genres; a tap on punk again, or on blues, the AI's, asks no list again.
            val list = checkNotNull(notes.aiGenres(track).first())
            assertEquals(listOf("punk", "blues"), list.genres.map { it.identity })
            assertEquals(ModelNames.of(model) ?: model, list.model)
            notes.explain(track, "punk")
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            val withList = track.withAi(list)
            assertEquals(GenreReason.Offered, notes.reason(withList, "blues").first())
            notes.explain(withList, "blues")
            advanceUntilIdle()
            assertEquals(3, provider.requests)
            assertTrue("\"Blues\"" in provider.prompts[2])
        }

    @Test
    fun `a genre the AI's list does not have says so, with no further call (#762)`() =
        runTest {
            knowing()
            provider.reply = { """{"genres": ["Jazz"]}""" }
            val notes = notes()
            val track = mixed()
            notes.explain(track, "punk")
            advanceUntilIdle()
            assertEquals(GenreReason.Unavailable(Unavailability.NOT_LISTED), notes.reason(track, "punk").first())
            notes.explain(track, "punk")
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            // Rock, which Last.fm gives, is offered as before.
            assertEquals(GenreReason.Offered, notes.reason(track, "rock").first())
        }

    @Test
    fun `with the model's knowledge off the list cannot run, and a tap neither calls nor reads the cache (#762)`() =
        runTest {
            val counted = CountedCache(cache)
            parts.value =
                PipelineParts(
                    normalizer = Normalizer(JvmAccentFolder),
                    adapters = emptyList(),
                    resolver = TrackResolver { _, _ -> Resolution.NONE },
                    hasher = JvmHasher,
                    ai = AiAccess(provider, Secret("key")),
                    cache = counted,
                )
            val notes = notes()
            val track = mixed()
            assertEquals(GenreReason.Unavailable(Unavailability.CANNOT_LIST), notes.reason(track, "punk").first())
            assertEquals(GenreReason.Offered, notes.reason(track, "rock").first())
            notes.explain(track, "punk")
            advanceUntilIdle()
            assertEquals(0, counted.writes)
            assertEquals(0, provider.requests)
            // Where it would not be offered anyway, nothing.
            settings.value = settings.value?.copy(writeInsights = false)
            assertEquals(GenreReason.None, notes.reason(track, "punk").first())
        }

    @Test
    fun `with no AI service ready the button says where to enable one, and a tap asks nothing (#762)`() =
        runTest {
            knowing()
            parts.value =
                parts.value?.let { made ->
                    PipelineParts(
                        made.normalizer,
                        made.adapters,
                        made.resolver,
                        made.hasher,
                        ai = null,
                        cache = made.cache,
                    )
                }
            val notes = notes()
            val track = mixed()
            for (genre in listOf("rock", "punk")) {
                assertEquals(GenreReason.Unavailable(Unavailability.NO_SERVICE), notes.reason(track, genre).first())
                notes.explain(track, genre)
            }
            advanceUntilIdle()
            assertEquals(0, provider.requests)
        }

    @Test
    fun `a failed genre list shows why, is not kept, and Retry asks again (#762)`() =
        runTest {
            knowing()
            provider.error = AiErrorKind.OFFLINE
            val notes = notes()
            val track = mixed()
            notes.explain(track, "punk")
            advanceUntilIdle()
            val failed = notes.reason(track, "punk").first() as GenreReason.Shown
            assertEquals(InsightState.Failed("offline"), failed.state)
            assertNull(notes.aiGenres(track).first())
            assertEquals(0, cache.held)
            provider.error = null
            provider.reply = { n -> if (n == 2) LIST else "Its loud guitars are punk [MB1]." }
            notes.explain(track, "punk")
            advanceUntilIdle()
            assertTrue((notes.reason(track, "punk").first() as GenreReason.Shown).state is InsightState.Written)
            assertEquals(3, provider.requests)
        }

    @Test
    fun `the AI's genre list is kept, and on a later play shows again with no call (#762)`() =
        runTest {
            knowing()
            provider.reply = { n -> if (n == 1) LIST else "Its loud guitars are punk [MB1]." }
            notes().explain(mixed(), "punk")
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            // A process later: nothing in memory, the cache keeps the list and the reason.
            val later = notes()
            val list = later.aiGenres(mixed())
            list.first()
            advanceUntilIdle()
            assertEquals(listOf("punk", "blues"), list.first()?.genres?.map { it.identity })
            val reason = later.reason(mixed(), "punk")
            reason.first()
            advanceUntilIdle()
            assertTrue(((reason.first() as GenreReason.Shown).state as InsightState.Written).cached)
            assertEquals(2, provider.requests)
            // *Clear history*: forgotten.
            later.forget()
            cache.clearCards()
            assertNull(later.aiGenres(mixed()).first())
        }

    // [cache], counting the reads and writes of cards.
    private class CountedCache(
        private val cache: PipelineCache,
    ) : PipelineCache by cache {
        var reads = 0
        var writes = 0

        override suspend fun card(key: CardKey): String? = cache.card(key).also { reads++ }

        override suspend fun keptCard(key: CardKey): KeptCard? = cache.keptCard(key).also { reads++ }

        override suspend fun keep(
            key: CardKey,
            text: String,
        ) = cache.keep(key, text).also { writes++ }

        override suspend fun keep(
            key: CardKey,
            text: String,
            writtenAt: Instant,
        ) = cache.keep(key, text, writtenAt).also { writes++ }
    }

    private companion object {
        /** The AI's genre list: punk, which MusicBrainz gives, and blues, which no source does. */
        const val LIST = """{"genres": ["Punk", "Blues"]}"""
    }
}
