package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
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
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pages a genre's reason found on the web, offered to *Sources* (#1205, `searchedPages`), so they
 * are numbered with the AI's genre list's and a reason's citation shows the id *Sources* lists its page
 * under: a reason written now, and one read from the cache, with its pages, once its genre is looked at.
 */
class AppGenreSearchedPagesTest {
    private val page = WebPage("https://example.org/loud-guitars", "Loud guitars", "Its loud guitars make it rock.")

    // Asked with the page, the reply cites it, the track's first web line; else the evidence cannot say.
    private val provider =
        CountingProvider().apply {
            reply = { _ -> if (page.text in prompts.last()) CITED else GroundingCheck.NO_EVIDENCE }
        }
    private val cache = CardCache(clock = MovedClock())
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                webSearch = { _, _ -> WebSearchOutcome.Found(listOf(page)) },
            ),
        )
    private val settings =
        MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC, aiWebSearch = AiWebSearch.ONCE))

    private val articles =
        GenreArticles {
            Lookup.Found(
                GenreDefinition("Rock music is a genre.", "Rock music", "https://example.org/rock"),
            )
        }
    private val definitions = GenreDefinitions(articles, GenreVocabulary.load(JvmResourceReader))

    private fun track(): TrackState {
        val rock = GenreLabel("rock", "Rock", "rock", null, GenreLevel.ARTIST, "A")
        val row =
            GenreRow(GenreLevel.ARTIST, "A", listOf(TrackLabel(rock, listOf(LabelCredit(LabelSource.LASTFM, null)))))
        return HomeFixtures.track().copy(genres = TrackGenres(listOf(row)))
    }

    private fun TestScope.notes() =
        AppGenreNotes(definitions, parts, settings, this, ClearEpoch(), clock = MovedClock())

    @Test
    fun `the pages a reason found on the web are offered to Sources, written now or read from the cache`() =
        runTest {
            val notes = notes()
            assertEquals(emptyList<WebPage>(), notes.searchedPages(track()).first())
            notes.explain(track(), "rock")
            advanceUntilIdle()
            val found = listOf(WebPage(page.url, null, page.text))
            assertEquals(found, notes.searchedPages(track()).first())
            val asked = provider.requests
            // A process later: nothing in memory; read from the cache once its genre is looked at.
            val later = notes()
            assertEquals(emptyList<WebPage>(), later.searchedPages(track()).first())
            later.reason(track(), "rock").first()
            advanceUntilIdle()
            assertEquals(found, later.searchedPages(track()).first())
            assertEquals(asked, provider.requests)
        }

    private companion object {
        const val CITED = "Its loud guitars are rock [S1]."
    }
}
