package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where Home cannot show an item for a service that is off, it names it as Settings does, then "See
 * Settings → Section.", a link to where it is switched on (#766): *Genres* with *Show genres* off,
 * with every genre source off, and with nothing found while some are off (not while genres show);
 * *Key insights* and the song's, the artist's and the album's panels with *Show insights* off, no AI
 * service, or the one switched on lacking its key; *Sources*' note of a source that needs its key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class ServicesOffScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private fun sourcesWithout(vararg off: SourceSwitch) = Settings.DEFAULT_SOURCES - off.toSet()

    private fun see(link: Int) = app.getString(R.string.home_off_see, text(link))

    private val opened = mutableListOf<SettingsTarget>()

    // No write taken up: a tap that opens a panel asks for none here, with no AI service ready.
    private val notes = FakeItemNotes().apply { takenUp = false }

    private val actions =
        NowPlayingActions(normalizer::normalize, openSettingsAt = { opened += it }, itemNotes = notes)

    // MusicBrainz's and Last.fm's lookups done, with [genres].
    private fun labelled(genres: TrackGenres = TrackGenres()): TrackState =
        track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done())).copy(genres = genres)

    private val folk =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.TRACK,
                    null,
                    listOf(
                        TrackLabel(
                            GenreLabel("folk", "Folk", "folk", level = GenreLevel.TRACK),
                            listOf(LabelCredit(LabelSource.MUSICBRAINZ, "https://example.org/folk")),
                        ),
                    ),
                ),
            ),
        )

    private fun state(
        track: TrackState = labelled(),
        settings: Settings = Settings(onePanel = false),
        view: PipelineView = PipelineView(track, aiReady = true),
        missing: Set<SecretId> = emptySet(),
    ) = NowPlayingState(
        view,
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
        keys = missing.associateWith { SecretState.ABSENT },
    )

    private fun show(
        state: NowPlayingState,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    // Taps the link in the line [line], and checks it opened [target].
    private fun follows(
        line: String,
        target: SettingsTarget,
    ) {
        opened.clear()
        compose.onAllNodesWithText(line)[0].performScrollTo().performFirstLinkClick {
            (it.item as? LinkAnnotation.Clickable)?.tag == target.linkTag
        }
        compose.waitForIdle()
        assertEquals(listOf(target), opened)
    }

    @Test
    fun withShowGenresOffTheButtonStaysAndItsPanelLinksToSettingsGenres() =
        show(state(settings = Settings(onePanel = false, showGenres = false))) {
            assertEquals("Show genres is off.", text(R.string.home_genres_hidden))
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithText("Show genres is off.").assertExists()
            follows("See Settings → Genres.", SettingsTarget.genres)
        }

    @Test
    fun withNothingFoundALastFmWithNoKeyIsNamedUnderNoneLinkingToSources() =
        // Last.fm switched off by the keys' gate, its key not saved, as Settings shows it.
        show(
            state(
                settings = Settings(onePanel = false, enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LASTFM),
                missing = setOf(SecretId.LASTFM),
            ),
        ) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithText(text(R.string.home_genres_none)).assertExists()
            compose.onNodeWithText("Last.fm needs your key.").assertExists()
            assertEquals(0, count("Last.fm is disabled."))
            follows(see(R.string.home_settings_sources_link), SettingsTarget.sources)
        }

    @Test
    fun withNothingFoundASourceSwitchedOffIsNamedUnderNone() =
        show(state(settings = Settings(onePanel = false, enabledSources = sourcesWithout(SourceSwitch.WIKIPEDIA)))) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithText(text(R.string.home_genres_none)).assertExists()
            compose.onNodeWithText("Wikipedia and Wikidata are disabled.").assertExists()
            follows(see(R.string.home_settings_sources_link), SettingsTarget.sources)
        }

    @Test
    fun whileGenresShowNothingIsSaidOfTheSourcesOff() =
        show(
            state(
                labelled(folk),
                Settings(onePanel = false, enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LASTFM),
                missing = setOf(SecretId.LASTFM),
            ),
        ) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithTag(GENRE_PREFIX + "track:folk").assertExists()
            assertEquals(0, count("Last.fm needs your key."))
            assertEquals(0, count(see(R.string.home_settings_sources_link)))
        }

    @Test
    fun aLastFmOnWithNoKeyItCanReadCountsAsOffNotAsStillAsked() {
        // MusicBrainz and Wikipedia off, Last.fm on but with no key the pipeline can read: every one off.
        val off =
            Settings(
                onePanel = false,
                enabledSources = sourcesWithout(SourceSwitch.MUSICBRAINZ, SourceSwitch.WIKIPEDIA),
            )
        val gathering = labelled().copy(genresAssembled = false)
        show(state(gathering, off, PipelineView(gathering, aiReady = true, needsKey = setOf(SourceId.LASTFM)))) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithText("MusicBrainz, Wikipedia and Wikidata are disabled.").assertExists()
            compose.onNodeWithText("Last.fm needs your key.").assertExists()
            assertEquals(0, compose.onAllNodesWithText("Looking", substring = true).fetchSemanticsNodes().size)
            follows(see(R.string.home_settings_sources_link), SettingsTarget.sources)
        }
    }

    @Test
    fun withShowInsightsOffKeyInsightsAndTheItemsLinkToSettingsInsights() {
        notes.shown.value = ROW_ITEMS.associateWith { ItemShown.Off }
        show(state(settings = Settings(onePanel = false, writeInsights = false))) {
            assertEquals("Show insights is off.", text(R.string.home_insight_off))
            compose.onNodeWithTag(INSIGHT_HEADING_TAG).assertExists()
            assertEquals(1, count("Show insights is off."))
            follows(see(R.string.home_settings_insights_link), SettingsTarget.insights)
            // The rows stay; a panel says the same, with its own link.
            tap(itemRowTag(InsightItem.SONG))
            assertEquals(2, count("Show insights is off."))
            assertEquals(2, count(see(R.string.home_settings_insights_link)))
        }
    }

    @Test
    fun withNoAiServiceKeyInsightsAndTheItemsLinkToSettingsAiServices() {
        notes.shown.value = ROW_ITEMS.associateWith { ItemShown.NoService }
        show(state(view = PipelineView(labelled(), aiReady = false))) {
            assertEquals("No AI service is enabled.", text(R.string.home_off_no_ai))
            assertEquals(1, count("No AI service is enabled."))
            follows(see(R.string.home_settings_ai_services_link), SettingsTarget.aiServices)
            tap(itemRowTag(InsightItem.SONG))
            assertEquals(2, count("No AI service is enabled."))
        }
    }

    @Test
    fun withTheAiServiceOnAndNoKeyItIsNamed() {
        notes.shown.value = ROW_ITEMS.associateWith { ItemShown.NoService }
        val anthropic = Settings(onePanel = false, aiProvider = ProviderId.ANTHROPIC)
        show(state(settings = anthropic, view = PipelineView(labelled(), aiReady = false))) {
            assertEquals(1, count("Anthropic needs your key."))
            assertEquals(0, count("No AI service is enabled."))
            follows(see(R.string.home_settings_ai_services_link), SettingsTarget.aiServices)
            tap(itemRowTag(InsightItem.SONG))
            assertEquals(2, count("Anthropic needs your key."))
        }
    }

    @Test
    fun aSourceThatNeedsItsKeyLinksToSettingsSources() {
        // Last.fm on with no key the pipeline can read: not looked up, so no state of its own.
        val track = track(sundown, sources = mapOf(SourceId.MUSICBRAINZ to done()))
        show(state(track, view = PipelineView(track, aiReady = true, needsKey = setOf(SourceId.LASTFM)))) {
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_KEY_NOTE_TAG).assertExists()
            assertTrue(count("Last.fm needs your key.") >= 1)
            follows(see(R.string.home_settings_sources_link), SettingsTarget.sources)
        }
    }

    // *Ask AI*'s genres, answered for the track: chips under the panel's lines (#773).
    private val aiGenres =
        object : AiSearchFeed {
            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> =
                flowOf(
                    if (kind == SearchKind.GENRES) {
                        val label = GenreLabel("folk rock", "Folk Rock", "folk rock", level = GenreLevel.TRACK)
                        AiSearch.Answered(SearchAnswer.Genres(listOf(label)), "Claude Haiku 5.5")
                    } else {
                        AiSearch.None
                    },
                )

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ): Boolean = false

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) = Unit
        }

    private val everySourceOff =
        Settings(
            onePanel = false,
            enabledSources = sourcesWithout(SourceSwitch.MUSICBRAINZ, SourceSwitch.LASTFM, SourceSwitch.WIKIPEDIA),
        )

    @Test
    fun whileAskAisGenresShowThePanelSaysNothingOfTheSourcesOff() {
        val withAi = NowPlayingActions(normalizer::normalize, openSettingsAt = { opened += it }, aiSearches = aiGenres)
        for (state in listOf(
            state(settings = everySourceOff),
            state(settings = Settings(onePanel = false, enabledSources = sourcesWithout(SourceSwitch.LASTFM))),
        )) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.setContent {
                        Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, withAi) }
                    }
                }
                compose.waitForIdle()
                tap(GENRES_CHIP_TAG)
                compose.onNodeWithText("Folk Rock", substring = true).assertExists()
                assertEquals(0, count(see(R.string.home_settings_sources_link)))
                assertEquals(0, compose.onAllNodesWithText("disabled.", substring = true).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun whileAskAisGenresShowTheGenresPageSaysNothingOfTheSourcesOff() {
        val track = labelled()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GenresPage(track, everySourceOff, search = PanelSearch(aiGenres, track, track.nowPlaying))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Folk Rock", substring = true).assertExists()
            assertEquals(0, count(see(R.string.home_settings_sources_link)))
            assertEquals(0, compose.onAllNodesWithText("disabled.", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theWordsAreThePicks() {
        assertEquals("See Settings → Sources.", see(R.string.home_settings_sources_link))
        assertEquals("See Settings → AI services.", see(R.string.home_settings_ai_services_link))
        assertEquals("See Settings → Insights.", see(R.string.home_settings_insights_link))
        assertEquals("Last.fm needs your key.", app.getString(R.string.home_off_key, "Last.fm"))
        assertEquals("MusicBrainz is disabled.", app.getString(R.string.home_off_one, "MusicBrainz"))
        assertEquals("A and B are disabled.", app.getString(R.string.home_off_many, "A and B"))
    }
}
