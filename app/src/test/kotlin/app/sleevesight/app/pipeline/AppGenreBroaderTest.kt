package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
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
import app.sleevesight.core.pipeline.ClearEpoch
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Why is this …?* for a broader genre opened in its narrower genre's place (#1127), in the app: a
 * broader genre reached only from MusicBrainz's tags makes no AI request, even where MusicBrainz also
 * gives it as the track's own genre, whose own panel still offers the AI's genre list as before (#762).
 */
class AppGenreBroaderTest {
    private val provider = CountingProvider().apply { reply = { "Its smooth vocals are R&B [MB1]." } }
    private val clock = MovedClock()
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

    // *AI service knowledge* on, so the AI's genre list could run (#762).
    private val settings =
        MutableStateFlow<Settings?>(
            Settings(aiProvider = ProviderId.ANTHROPIC).let {
                it.copy(enabledSources = it.enabledSources + SourceSwitch.MODEL)
            },
        )

    private val articles =
        object : GenreArticles {
            override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> = Lookup.NotFound
        }
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val definitions = GenreDefinitions(articles, vocabulary)
    private val narrower = checkNotNull(vocabulary.identity("contemporary r&b"))
    private val broader = checkNotNull(vocabulary.identity("r&b"))

    private fun TestScope.notes() = AppGenreNotes(definitions, parts, settings, this, ClearEpoch(), clock = clock)

    // The track's row: Contemporary R&B and R&B, each from [source].
    private fun track(source: LabelSource): TrackState {
        val labels =
            vocabulary.labelsFor(listOf("contemporary r&b", "r&b"), level = GenreLevel.TRACK).map {
                TrackLabel(it, listOf(LabelCredit(source, null)))
            }
        return HomeFixtures.track().copy(genres = TrackGenres(listOf(GenreRow(GenreLevel.TRACK, null, labels))))
    }

    @Test
    fun `the broader genre of a genre only MusicBrainz gives asks nothing, though MusicBrainz gives it too`() =
        runTest {
            val notes = notes()
            val track = track(LabelSource.MUSICBRAINZ)
            assertEquals(broader, vocabulary.broader(narrower)?.identity)
            assertEquals(GenreReason.None, notes.broaderReason(track, broader).first())
            // A tap, were one possible, asks nothing either: neither the AI's genre list nor the reason.
            notes.explainBroader(track, broader)
            advanceUntilIdle()
            assertEquals(GenreReason.None, notes.broaderReason(track, broader).first())
            assertEquals(0, provider.requests)
        }

    @Test
    fun `R&B opened as the track's own genre only MusicBrainz gives is offered through the AI's list, as before`() =
        runTest {
            val notes = notes()
            val track = track(LabelSource.MUSICBRAINZ)
            assertEquals(GenreReason.Offered, notes.reason(track, broader).first())
            notes.explain(track, broader)
            advanceUntilIdle()
            // The AI's genre list, naming no genre, asked first.
            assertTrue(provider.requests >= 1)
        }

    @Test
    fun `the broader genre of a genre Last-fm gives is offered and written`() =
        runTest {
            val notes = notes()
            val track = track(LabelSource.LASTFM)
            assertEquals(GenreReason.Offered, notes.broaderReason(track, broader).first())
            notes.explainBroader(track, broader)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(provider.prompts.single().contains("<genre>\nR&B\n</genre>"))
        }
}
