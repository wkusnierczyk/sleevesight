package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/**
 * The *Lyrics* button under the album and its panel (#728): shown for every track while *Lyrics
 * links* is on; each page MusicBrainz links, one per site, opening in the browser; with none, why,
 * then the Genius search, or a line that it is off with a link to where it is switched on; nothing
 * but a line for an instrumental; *Retry* where MusicBrainz could not be reached or its lyrics links
 * are not known, handing the focus to *Close*; and what a screen reader hears. Which links are
 * chosen is `LyricsLinksTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class LyricsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    // [playing] with MusicBrainz's [musicBrainz] state, under [settings].
    private fun state(
        musicBrainz: SourceState?,
        settings: Settings = Settings(),
        playing: NowPlaying = sundown,
        view: (TrackState) -> PipelineView = { PipelineView(it, aiReady = true) },
    ): NowPlayingState {
        val track = track(playing, sources = musicBrainz?.let { mapOf(SourceId.MUSICBRAINZ to it) }.orEmpty())
        return NowPlayingState(view(track), playing, playback, settings = settings)
    }

    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)

    // MusicBrainz found the song, and says [lyrics] of it.
    private fun found(lyrics: WorkLyrics) =
        SourceState.Done(
            SourceResult(Outcome.SongArticle, emptyList(), 10.milliseconds, lyrics = lyrics),
            cached = false,
        )

    private val sundownLinks =
        found(
            WorkLyrics(
                listOf(
                    "https://www.musixmatch.com/lyrics/Gordon-Lightfoot/Sundown",
                    "https://genius.com/Someone-else-sundown-lyrics",
                    "https://genius.com/Gordon-lightfoot-sundown-lyrics",
                ),
            ),
        )

    private val current = mutableStateOf(state(sundownLinks))

    // Where the screen's taps went: *Retry*'s, the links to the settings, and the pages opened.
    private val retried = mutableListOf<Triple<RunKey, SourceId, NowPlaying>>()
    private val opened = mutableListOf<SettingsTarget>()
    private val browsed = mutableListOf<String>()

    private val actions =
        NowPlayingActions(
            normalizer::normalize,
            openSettingsAt = { opened += it },
            askAgain = { key, source, shown -> retried += Triple(key, source, shown) },
        )

    private val browser =
        object : UriHandler {
            override fun openUri(uri: String) {
                browsed += uri
            }
        }

    private fun show(
        initial: NowPlayingState = state(sundownLinks),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalUriHandler provides browser) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            NowPlayingContent(current.value, actions)
                        }
                    }
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

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun shows(line: String) = compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theButtonOpensThePanelInItsPlaceBesideDatesAndCloseFoldsItBack() =
        show {
            assertEquals(1, count(LYRICS_CHIP_TAG))
            assertEquals(1, count(DATES_CHIP_TAG))
            // After *Dates*, on its line.
            val dates = compose.onNodeWithTag(DATES_CHIP_TAG).fetchSemanticsNode().boundsInRoot
            val lyrics = compose.onNodeWithTag(LYRICS_CHIP_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(lyrics.left >= dates.right)
            assertEquals(dates.center.y, lyrics.center.y, 1f)
            tap(LYRICS_CHIP_TAG)
            assertEquals(0, count(LYRICS_CHIP_TAG))
            assertEquals(1, count(LYRICS_PANEL_TAG))
            compose
                .onNodeWithTag(LYRICS_PANEL_HEADING_TAG)
                .assertTextEquals(text(R.string.home_lyrics))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose
                .onNodeWithTag(LYRICS_PANEL_CLOSE_TAG)
                .assertTextEquals(text(R.string.page_close))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            // *Dates* stays a button beside it, and opens too: both panels at once.
            tap(DATES_CHIP_TAG)
            assertEquals(1, count(LYRICS_PANEL_TAG))
            assertEquals(1, count(DATES_PANEL_TAG))
            tap(LYRICS_PANEL_CLOSE_TAG)
            assertEquals(1, count(LYRICS_CHIP_TAG))
            assertEquals(0, count(LYRICS_PANEL_TAG))
        }

    @Test
    fun withLyricsLinksOffThereIsNoButton() =
        show(state(sundownLinks, Settings(lyricsLinks = false))) {
            assertEquals(0, count(LYRICS_CHIP_TAG))
            assertEquals(1, count(DATES_CHIP_TAG))
        }

    @Test
    fun aScreenReaderReadsTheButtonAsLyricsAButtonToShowLyricsLinks() =
        show {
            assertEquals("Lyrics", text(R.string.home_lyrics))
            assertEquals("show lyrics links", text(R.string.home_lyrics_show))
            compose
                .onNodeWithTag(LYRICS_CHIP_TAG)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_lyrics)),
                    ),
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assert(
                    SemanticsMatcher("click labelled \"show lyrics links\"") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label == text(R.string.home_lyrics_show)
                    },
                ).assertTouchHeightIsEqualTo(48.dp)
        }

    @Test
    fun eachSitesPageShowsOncePlayingArtistsFirstGeniusFirstAndOpensInTheBrowser() =
        show {
            tap(LYRICS_CHIP_TAG)
            assertEquals("Lyrics on Genius", app.getString(R.string.home_lyrics_page, "Genius"))
            assertEquals("The song’s page, linked from MusicBrainz.", text(R.string.home_lyrics_page_note))
            assertEquals("Opens in your browser.", text(R.string.home_lyrics_browser))
            val genius = compose.onNodeWithText("Lyrics on Genius").fetchSemanticsNode().boundsInRoot
            val musixmatch = compose.onNodeWithText("Lyrics on Musixmatch").fetchSemanticsNode().boundsInRoot
            assertTrue(musixmatch.top > genius.bottom)
            assertEquals(2, compose.onAllNodesWithText(text(R.string.home_lyrics_page_note)).fetchSemanticsNodes().size)
            assertTrue(shows(text(R.string.home_lyrics_browser)))
            // No search beside a page, and no reason.
            assertEquals(0, count(LYRICS_SEARCH_TAG))
            assertTrue(!shows(text(R.string.home_lyrics_none)))
            compose.onNodeWithText("Lyrics on Genius").performScrollTo().performFirstLinkClick()
            compose.waitForIdle()
            assertEquals(listOf("https://genius.com/Gordon-lightfoot-sundown-lyrics"), browsed)
        }

    @Test
    fun withNoPageThePanelSaysSoThenTheGeniusSearchForTheTitleAndArtist() =
        show(state(found(WorkLyrics()))) {
            tap(LYRICS_CHIP_TAG)
            assertEquals("MusicBrainz links to no lyrics page for this song.", text(R.string.home_lyrics_none))
            assertEquals("Search Genius for the lyrics", app.getString(R.string.home_lyrics_search, "Genius"))
            assertEquals(
                "A search for the title and artist; it may find another song of the same name.",
                text(R.string.home_lyrics_search_note),
            )
            assertTrue(shows(text(R.string.home_lyrics_none)))
            assertTrue(shows(text(R.string.home_lyrics_search_note)))
            assertTrue(shows(text(R.string.home_lyrics_browser)))
            val reason = compose.onNodeWithText(text(R.string.home_lyrics_none)).fetchSemanticsNode().boundsInRoot
            val search = compose.onNodeWithText("Search Genius for the lyrics").fetchSemanticsNode().boundsInRoot
            assertTrue(search.top > reason.bottom)
            compose.onNodeWithText("Search Genius for the lyrics").performScrollTo().performFirstLinkClick()
            compose.waitForIdle()
            assertEquals(listOf("https://genius.com/search?q=Sundown%20Gordon%20Lightfoot"), browsed)
        }

    @Test
    fun withTheSearchOffThePanelSaysSoAndLinksToAppearance() =
        show(state(found(WorkLyrics()), Settings(lyricsSearch = false))) {
            tap(LYRICS_CHIP_TAG)
            assertEquals("Genius search is disabled.", text(R.string.home_lyrics_search_off))
            assertEquals("Settings → Appearance", text(R.string.home_settings_appearance_link))
            assertTrue(shows(text(R.string.home_lyrics_none)))
            assertTrue(shows(text(R.string.home_lyrics_search_off)))
            assertEquals(0, count(LYRICS_SEARCH_TAG))
            assertTrue(!shows(text(R.string.home_lyrics_browser)))
            val see = app.getString(R.string.home_lyrics_search_off_see, text(R.string.home_settings_appearance_link))
            assertEquals("See Settings → Appearance.", see)
            compose.onNodeWithText(see).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.lyricsSearch.linkTag
            }
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.lyricsSearch), opened)
        }

    @Test
    fun anInstrumentalSaysSoWithNoSearch() =
        show(state(found(WorkLyrics(instrumental = true)))) {
            tap(LYRICS_CHIP_TAG)
            assertEquals(
                "MusicBrainz lists this track as instrumental, without lyrics.",
                text(R.string.home_lyrics_instrumental),
            )
            assertTrue(shows(text(R.string.home_lyrics_instrumental)))
            assertEquals(0, count(LYRICS_SEARCH_TAG))
            assertTrue(!shows(text(R.string.home_lyrics_search_off)))
            assertTrue(!shows(text(R.string.home_lyrics_browser)))
        }

    @Test
    fun whileMusicBrainzIsAskedThePanelSaysItIsLookingWithNoSearch() {
        // MusicBrainz being asked, and the pipeline not at the track yet.
        for (initial in listOf(state(SourceState.Pending), NowPlayingState(PipelineView(), sundown, playback))) {
            show(initial) {
                tap(LYRICS_CHIP_TAG)
                assertTrue(shows(text(R.string.home_musicbrainz_looking)))
                assertEquals(0, count(LYRICS_SEARCH_TAG))
                assertTrue(!shows(text(R.string.home_lyrics_search_off)))
            }
        }
    }

    @Test
    fun whenMusicBrainzDidNotFindTheSongThePanelSaysSoThenTheSearch() {
        for (outcome in listOf(Outcome.None, Outcome.Rejected("“Sundown” by someone else"))) {
            show(state(done(outcome))) {
                tap(LYRICS_CHIP_TAG)
                assertTrue(shows(text(R.string.home_musicbrainz_not_found)))
                assertEquals(1, count(LYRICS_SEARCH_TAG))
            }
        }
    }

    @Test
    fun withMusicBrainzOffThePanelSaysSoLinksToTheSourcesThenTheSearch() =
        show(state(done(Outcome.SourceOff))) {
            tap(LYRICS_CHIP_TAG)
            assertTrue(shows(text(R.string.home_musicbrainz_off)))
            assertEquals(1, count(LYRICS_SEARCH_TAG))
            val see = app.getString(R.string.home_musicbrainz_off_see, text(R.string.home_settings_sources_link))
            compose.onNodeWithText(see).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.sources.linkTag
            }
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.sources), opened)
        }

    // [musicBrainz]'s state, *Retry* offered for MusicBrainz in the run.
    private fun retryable(musicBrainz: SourceState): NowPlayingState {
        val key = RunKey.of(normalizer, sundown)
        return state(musicBrainz) {
            PipelineView(it, aiReady = true, askAgainKey = key, askable = setOf(SourceId.MUSICBRAINZ))
        }
    }

    @Test
    fun unreachableOrWithItsLinksNotKnownRetryOnTheSameLineAsksMusicBrainzAgainThenTheSearch() {
        for (musicBrainz in listOf(done(Outcome.Unreachable), found(WorkLyrics(links = null)))) {
            retried.clear()
            show(retryable(musicBrainz)) {
                tap(LYRICS_CHIP_TAG)
                assertTrue(shows(text(R.string.home_musicbrainz_unreachable)))
                assertTrue(!shows(text(R.string.home_lyrics_none)))
                assertEquals(1, count(LYRICS_SEARCH_TAG))
                val message = compose.onNodeWithText(text(R.string.home_musicbrainz_unreachable)).fetchSemanticsNode()
                val retry = compose.onNodeWithTag(RETRY_TAG).fetchSemanticsNode()
                assertTrue(retry.boundsInRoot.left >= message.boundsInRoot.right)
                assertEquals(message.boundsInRoot.center.y, retry.boundsInRoot.center.y, 1f)
                tap(RETRY_TAG)
                assertEquals(listOf(Triple(RunKey.of(normalizer, sundown), SourceId.MUSICBRAINZ, sundown)), retried)
            }
        }
    }

    @Test
    fun aTapOnRetryHandsTheFocusToCloseAsTheLineGivesWayToLooking() =
        show(retryable(found(WorkLyrics(links = null)))) {
            tap(LYRICS_CHIP_TAG)
            compose.onNodeWithTag(RETRY_TAG).requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(RETRY_TAG).assertIsFocused()
            tap(RETRY_TAG)
            current.value = state(SourceState.Pending)
            compose.waitForIdle()
            assertEquals(0, count(RETRY_TAG))
            assertTrue(shows(text(R.string.home_musicbrainz_looking)))
            compose.onNodeWithTag(LYRICS_PANEL_CLOSE_TAG).assertIsFocused()
        }

    @Test
    fun aNewTrackClosesThePanel() =
        show {
            tap(LYRICS_CHIP_TAG)
            assertEquals(1, count(LYRICS_PANEL_TAG))
            current.value = state(sundownLinks, playing = carefree)
            compose.waitForIdle()
            assertEquals(0, count(LYRICS_PANEL_TAG))
            assertEquals(1, count(LYRICS_CHIP_TAG))
        }
}
