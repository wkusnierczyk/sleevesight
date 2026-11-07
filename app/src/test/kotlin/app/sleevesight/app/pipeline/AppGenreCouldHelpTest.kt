package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
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
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.GenreListMode
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the panel offers to switch on when nothing explains a genre (#695): what is off in the
 * settings and could explain it, *AI service knowledge* and *AI web search* as *Settings* shows it
 * (*Off*, set so or with Exa web search off); not *AI web search* used but with no web search engine
 * to run it, which nothing in the panel's link would change.
 */
class AppGenreCouldHelpTest {
    private val provider = CountingProvider().apply { reply = { GroundingCheck.NO_EVIDENCE } }
    private val clock = MovedClock()

    // With no web search engine ([PipelineParts.webSearch] null): *AI web search* cannot search.
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(clock = clock),
            ),
        )
    private val base = Settings(aiProvider = ProviderId.ANTHROPIC)
    private val knowledge = base.copy(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)
    private val settings = MutableStateFlow<Settings?>(base)
    private val definitions =
        GenreDefinitions({ Lookup.NotFound }, GenreVocabulary.load(JvmResourceReader))

    private val track: TrackState =
        HomeFixtures.track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.ARTIST,
                            "A",
                            listOf(
                                TrackLabel(
                                    GenreLabel("rock", "Rock", "rock", null, GenreLevel.ARTIST, "A"),
                                    listOf(LabelCredit(LabelSource.LASTFM, null)),
                                ),
                            ),
                        ),
                    ),
                ),
        )

    // What the panel offers after a tap whose reason nothing explains, under [with].
    private suspend fun TestScope.couldHelp(with: Settings): List<GenreListMode> {
        settings.value = with
        val notes = AppGenreNotes(definitions, parts, settings, this, ClearEpoch(), clock = clock)
        notes.explain(track, "rock")
        advanceUntilIdle()
        val shown = notes.reason(track, "rock").first() as GenreReason.Shown
        assertEquals(InsightState.NoEvidence, shown.state)
        return shown.couldHelp
    }

    @Test
    fun `with both on but no web search engine, it names nothing`() =
        runTest {
            assertEquals(emptyList<GenreListMode>(), couldHelp(knowledge.copy(aiWebSearch = AiWebSearch.ONCE)))
        }

    @Test
    fun `with Exa web search off, AI web search shows as Off, and is named`() =
        runTest {
            val noExa = base.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.WEB)
            assertEquals(listOf(GenreListMode.KNOWLEDGE, GenreListMode.WEB), couldHelp(noExa))
            val knowledgeNoExa = noExa.copy(enabledSources = noExa.enabledSources + SourceSwitch.MODEL)
            assertEquals(listOf(GenreListMode.WEB), couldHelp(knowledgeNoExa))
        }

    @Test
    fun `it names what is switched off`() =
        runTest {
            assertEquals(
                listOf(GenreListMode.KNOWLEDGE, GenreListMode.WEB),
                couldHelp(base.copy(aiWebSearch = AiWebSearch.OFF)),
            )
        }

    @Test
    fun `with the knowledge on and web search off, it names web search`() =
        runTest {
            assertEquals(listOf(GenreListMode.WEB), couldHelp(knowledge.copy(aiWebSearch = AiWebSearch.OFF)))
        }
}
