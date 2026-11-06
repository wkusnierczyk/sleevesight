package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.DescribedIn
import app.sleevesight.core.genres.GenreArticles
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.genres.combineGenres
import app.sleevesight.core.genres.withDescribed
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * *Why is this …?* in the app for a genre only an artist's description names (#1000): the reason is
 * asked about the genre by the name its chip shows, not its identity.
 */
class AppGenreNotesDescribedTest {
    private val provider = CountingProvider().apply { reply = { "Its sound is Turkish psychedelia [MB1]." } }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(clock = MovedClock()),
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val articles =
        object : GenreArticles {
            override suspend fun definition(musicBrainzGenre: String): Lookup<GenreDefinition> =
                Lookup.Found(GenreDefinition("A genre.", "A", "https://example.org/a"))
        }

    @Test
    fun `a reason for a genre only an artist's description names asks about it by its name`() =
        runTest {
            val notes = AppGenreNotes(GenreDefinitions(articles, vocabulary), parts, settings, this)
            val turkish =
                vocabulary
                    .labelsFor(
                        listOf("turkish psychedelia"),
                        level = GenreLevel.ARTIST,
                        artist = "A",
                    ).single()
            val described = TrackLabel(turkish, emptyList(), describedIn = DescribedIn("MB1", "Turkish psychedelia."))
            val tags =
                combineGenres(
                    mapOf(LabelSource.LASTFM to vocabulary.labelsFor(listOf("rock"), artist = "A")),
                    listOf("A"),
                )
            val track =
                HomeFixtures.track().copy(
                    genres = tags.withDescribed(listOf(GenreRow(GenreLevel.ARTIST, "A", listOf(described)))),
                )
            val genre = turkish.identity
            assertEquals(GenreReason.Offered, notes.reason(track, genre).first())
            notes.explain(track, genre)
            advanceUntilIdle()
            val prompt = provider.prompts.single()
            assertTrue(prompt.contains("Turkish Psychedelia", ignoreCase = true))
            assertFalse(prompt.contains(genre))
        }
}
