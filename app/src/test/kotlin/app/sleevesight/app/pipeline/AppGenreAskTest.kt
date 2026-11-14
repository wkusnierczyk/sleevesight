package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
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
import app.sleevesight.core.pipeline.InsightState
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
import app.sleevesight.core.synthesis.GenreReasonPrompt
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Is this …?* (#1117): for a genre only MusicBrainz gives that the AI's genre list leaves out, the
 * tap that finds it left out offers the ask, and only the ask's own tap asks the AI service, with the
 * ask's prompt; its answer, its failure and its could-not state show under the question, and it is
 * kept as a reason is.
 */
class AppGenreAskTest {
    private val provider = CountingProvider()
    private val cache = CardCache()
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

    // *AI service knowledge* on, so the AI's genre list can run (#762).
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(
                aiProvider = ProviderId.ANTHROPIC,
                enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL,
            ),
        )

    // No definition is looked up here: the tests are about the ask.
    private val articles =
        object : GenreArticles {
            override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> = Lookup.NotFound
        }
    private val definitions = GenreDefinitions(articles, GenreVocabulary.load(JvmResourceReader))

    private fun TestScope.notes() = AppGenreNotes(definitions, parts, settings, this)

    // A track whose artist row has punk and blues, each from MusicBrainz alone, on [play].
    private fun track(play: Long = 1): TrackState {
        val labels =
            listOf("punk", "blues").map { tag ->
                TrackLabel(
                    GenreLabel(tag, tag.replaceFirstChar(Char::uppercase), tag, null, GenreLevel.ARTIST, "A"),
                    listOf(LabelCredit(LabelSource.MUSICBRAINZ, null)),
                )
            }
        val track = HomeFixtures.track()
        return track.copy(
            nowPlaying = track.nowPlaying.copy(play = play),
            genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "A", labels))),
        )
    }

    // The first tap on [genre]: the AI's list, which leaves it out; *Is this …?* offered.
    private suspend fun TestScope.leftOut(
        notes: AppGenreNotes,
        on: TrackState = track(),
        genre: String = "punk",
    ) {
        notes.explain(on, genre)
        advanceUntilIdle()
        assertEquals(GenreReason.Ask, notes.reason(on, genre).first())
    }

    @Test
    fun `the ask is asked with the ask's prompt, and an answer that it does not fit is shown as written`() =
        runTest {
            provider.reply = { n -> if (n == 1) JAZZ_ONLY else "Its loud guitars are not punk, but rock [MB1]." }
            val notes = notes()
            leftOut(notes)
            assertEquals(1, provider.requests)
            notes.explain(track(), "punk")
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            assertTrue(GenreReasonPrompt.MODEL_ASK_RULE in checkNotNull(provider.systems[1]))
            assertFalse(GenreReasonPrompt.MODEL_LABEL_RULE in checkNotNull(provider.systems[1]))
            // The genre's name alone, not blues, the track's other genre only MusicBrainz gives.
            assertTrue("<genre>\nPunk\n</genre>" in provider.prompts[1])
            assertFalse("blues" in provider.prompts[1].lowercase())
            val shown = notes.reason(track(), "punk").first() as GenreReason.Shown
            assertTrue(shown.asks)
            // Not read as a dispute (#853): the ask may be answered either way.
            val written = shown.state as InsightState.Written
            assertTrue("not punk" in written.grounded.text)
        }

    @Test
    fun `a failed ask shows why under the question, is not kept, and Retry asks again`() =
        runTest {
            provider.reply = { n -> if (n == 1) JAZZ_ONLY else "Its loud guitars fit punk [MB1]." }
            val notes = notes()
            leftOut(notes)
            provider.error = AiErrorKind.OFFLINE
            notes.explain(track(), "punk")
            advanceUntilIdle()
            val failed = notes.reason(track(), "punk").first() as GenreReason.Shown
            assertEquals(InsightState.Failed("offline"), failed.state)
            assertTrue(failed.asks)
            // *Retry*.
            provider.error = null
            notes.explain(track(), "punk")
            advanceUntilIdle()
            assertEquals(3, provider.requests)
            val shown = notes.reason(track(), "punk").first() as GenreReason.Shown
            assertTrue(shown.state is InsightState.Written)
        }

    @Test
    fun `an ask nothing bears on shows the could-not state under the question`() =
        runTest {
            provider.reply = { n -> if (n == 1) JAZZ_ONLY else GroundingCheck.NO_EVIDENCE }
            val notes = notes()
            leftOut(notes)
            notes.explain(track(), "punk")
            advanceUntilIdle()
            val shown = notes.reason(track(), "punk").first() as GenreReason.Shown
            assertEquals(InsightState.NoEvidence, shown.state)
            assertTrue(shown.asks)
        }

    @Test
    fun `the ask is never asked by itself, and its answer kept shows on a later play with no call`() =
        runTest {
            provider.reply = { n -> if (n == 1) JAZZ_ONLY else "Its loud guitars fit punk [MB1]." }
            val notes = notes()
            leftOut(notes)
            // The panel open on blues, left out too, and on punk: nothing asked without the ask's tap.
            notes.reason(track(), "blues").first()
            notes.reason(track(), "punk").first()
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            notes.explain(track(), "punk")
            advanceUntilIdle()
            assertEquals(2, provider.requests)
            // A process later, the next play: the answer read from the cache, the list too, no call.
            val later = notes()
            val next = track(play = 2)
            later.aiGenres(next).first()
            advanceUntilIdle()
            val reason = later.reason(next, "punk")
            reason.first()
            advanceUntilIdle()
            val shown = reason.first() as GenreReason.Shown
            assertTrue(shown.asks)
            assertTrue((shown.state as InsightState.Written).cached)
            // Blues, not asked, is offered as *Why is this …?* until its own tap.
            assertEquals(GenreReason.Offered, later.reason(next, "blues").first())
            assertEquals(2, provider.requests)
        }

    private companion object {
        /** The AI's genre list, which leaves out both of the track's genres. */
        const val JAZZ_ONLY = """{"genres": ["Jazz"]}"""
    }
}
