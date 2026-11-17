package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Dates*, *Genres* and *Lyrics* in one panel (#1128, *One panel for Dates, Genres and Lyrics*, on
 * by default): a button opens the panel on its content, with the buttons at its top as its tabs, in
 * their order, the one shown selected; another tab switches it, keeping a tab's state while open;
 * *Close* folds it back to the buttons; a new track folds it; a button whose setting is off is no
 * tab; under *Separate pages*, *Genres* opens in it; the focus handed to the tab tapped and back to
 * its button; what a screen reader hears; at a large font the tabs wrap and *Close* stays top right.
 * With the switch off, a panel each, as `DatesScreenTest`, `GenresButtonScreenTest` and
 * `LyricsScreenTest` check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class OnePanelScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun label(tag: String): TrackLabel {
        val genre = GenreLabel(tag, tag.replaceFirstChar(Char::uppercase), tag, level = GenreLevel.TRACK)
        return TrackLabel(genre, listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")))
    }

    private fun labelled(playing: NowPlaying = sundown): TrackState =
        track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done())).copy(
            genres = TrackGenres(listOf(GenreRow(GenreLevel.TRACK, null, listOf(label("folk"), label("ballad"))))),
        )

    private fun state(
        track: TrackState = labelled(),
        settings: Settings = Settings(),
    ) = NowPlayingState(
        PipelineView(track, aiReady = true),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    private val current = mutableStateOf(state())

    private val actions = NowPlayingActions(normalizer::normalize)

    private fun show(
        initial: NowPlayingState = state(),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
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

    // Enter on [tag], focused, as a keyboard does.
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().requestFocus()
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val dates = inPlaceTabTag(InPlace.DATES)
    private val genres = inPlaceTabTag(InPlace.GENRES)
    private val lyrics = inPlaceTabTag(InPlace.LYRICS)
    private val buttons = listOf(DATES_CHIP_TAG, GENRES_CHIP_TAG, LYRICS_CHIP_TAG)
    private val panels = listOf(DATES_PANEL_TAG, GENRES_PANEL_TAG, LYRICS_PANEL_TAG)

    // Which panel is open, by its tag, with every other closed.
    private fun onlyOpen(panel: String) {
        for (tag in panels) assertEquals(tag, if (tag == panel) 1 else 0, count(tag))
    }

    @Test
    fun aButtonOpensThePanelOnItsContentWithTheThreeAsItsTabsInTheirOrder() =
        show {
            tap(GENRES_CHIP_TAG)
            onlyOpen(GENRES_PANEL_TAG)
            // The buttons are now the panel's tabs, not a line of their own above it; no title.
            for (tag in buttons) assertEquals(tag, 0, count(tag))
            assertEquals(0, count(GENRES_PANEL_HEADING_TAG))
            compose.onNodeWithTag(GENRE_PREFIX + "track:folk").assertExists()
            val (d, g, l) = listOf(dates, genres, lyrics).map(::bounds)
            assertTrue("Dates, Genres, Lyrics", d.right <= g.left && g.right <= l.left)
            assertEquals(d.center.y, l.center.y, 1f)
            compose.onNodeWithTag(genres).assertIsSelected()
            compose.onNodeWithTag(dates).assertIsNotSelected()
            compose.onNodeWithTag(lyrics).assertIsNotSelected()
            // *Close* at the top right, on the tabs' line.
            val close = bounds(GENRES_PANEL_CLOSE_TAG)
            assertTrue(close.left >= l.right)
            assertEquals(d.center.y, close.center.y, 1f)
        }

    @Test
    fun aTapOnAnotherTabSwitchesThePanelToIt() =
        show {
            tap(DATES_CHIP_TAG)
            onlyOpen(DATES_PANEL_TAG)
            tap(lyrics)
            onlyOpen(LYRICS_PANEL_TAG)
            compose.onNodeWithTag(lyrics).assertIsSelected()
            compose.onNodeWithTag(dates).assertIsNotSelected()
            tap(genres)
            onlyOpen(GENRES_PANEL_TAG)
            // The tab shown, tapped again: nothing changes.
            tap(genres)
            onlyOpen(GENRES_PANEL_TAG)
        }

    @Test
    fun closeFoldsThePanelBackToTheButtons() =
        show {
            tap(DATES_CHIP_TAG)
            tap(genres)
            tap(GENRES_PANEL_CLOSE_TAG)
            onlyOpen("none")
            for (tag in buttons) assertEquals(tag, 1, count(tag))
            assertEquals(0, count(IN_PLACE_TABS_TAG))
        }

    @Test
    fun aGenreOpenInGenresIsStillOpenAfterAnotherTabWasShown() =
        show {
            tap(GENRES_CHIP_TAG)
            // The panel opens on Ballad, the first: Folk tapped instead.
            val folk = GENRE_PREFIX + "track:folk"
            tap(folk)
            compose.onNodeWithTag(folk).assertIsSelected()
            tap(dates)
            tap(genres)
            compose.onNodeWithTag(folk).assertIsSelected()
        }

    @Test
    fun aNewTrackFoldsThePanelAndHandsTheFocusToItsButton() =
        show {
            tap(DATES_CHIP_TAG)
            press(lyrics)
            onlyOpen(LYRICS_PANEL_TAG)
            current.value = state(labelled(carefree))
            compose.waitForIdle()
            onlyOpen("none")
            compose.onNodeWithTag(LYRICS_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun theFocusMovesToTheTabTappedOnOpeningAndBackToTheButtonShownOnClosing() =
        show {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithTag(genres).assertIsFocused()
            press(dates)
            compose.onNodeWithTag(dates).assertIsFocused()
            tap(DATES_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(DATES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun aScreenReaderReadsEachTabAsATabSelectedOrNotWithItsPlaceAmongThem() =
        show {
            tap(DATES_CHIP_TAG)
            val group = compose.onNodeWithTag(IN_PLACE_TABS_TAG).fetchSemanticsNode().config
            assertTrue(SemanticsProperties.SelectableGroup in group)
            assertEquals(3, group[SemanticsProperties.CollectionInfo].columnCount)
            listOf(dates to "Dates", genres to "Genres", lyrics to "Lyrics").forEachIndexed { i, (tag, name) ->
                compose
                    .onNodeWithTag(tag)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, i == 0))
                    .assert(
                        SemanticsMatcher("named $name") { it.config[SemanticsProperties.Text].joinToString() == name },
                    )
                val item = compose.onNodeWithTag(tag).fetchSemanticsNode().config
                assertEquals(i, item[SemanticsProperties.CollectionItemInfo].columnIndex)
            }
        }

    @Test
    fun aButtonWhoseSettingIsOffIsNoTab() =
        show(state(settings = Settings(lyricsLinks = false))) {
            tap(DATES_CHIP_TAG)
            assertEquals(0, count(lyrics))
            assertEquals(
                2,
                compose
                    .onNodeWithTag(IN_PLACE_TABS_TAG)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.CollectionInfo]
                    .columnCount,
            )
            tap(genres)
            onlyOpen(GENRES_PANEL_TAG)
        }

    @Test
    fun underSeparatePagesGenresOpensInThePanelAsTheOthersDo() =
        show(state(settings = Settings(layout = Layout.SEPARATE_PAGES))) {
            assertEquals(0, count(GENRES_PAGE_LINK_TAG))
            tap(GENRES_CHIP_TAG)
            onlyOpen(GENRES_PANEL_TAG)
            compose.onNodeWithTag(GENRE_PREFIX + "track:folk").assertExists()
        }

    @Test
    fun withTheSwitchOffEachButtonOpensAPanelOfItsOwn() =
        show(state(settings = Settings(onePanel = false))) {
            tap(DATES_CHIP_TAG)
            tap(GENRES_CHIP_TAG)
            assertEquals(1, count(DATES_PANEL_TAG))
            assertEquals(1, count(GENRES_PANEL_TAG))
            assertEquals(1, count(LYRICS_CHIP_TAG))
            assertEquals(0, count(IN_PLACE_TABS_TAG))
            compose.onNodeWithTag(DATES_PANEL_HEADING_TAG).assertExists()
        }

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi", fontScale = 2f)
    // Text measured as on the phone: the legacy graphics give every glyph a token width.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun atALargeFontTheTabsWrapAndCloseStaysTopRight() =
        show {
            tap(DATES_CHIP_TAG)
            val first = bounds(dates)
            val last = bounds(lyrics)
            assertTrue("the tabs take two lines", last.top >= first.bottom)
            val close = bounds(DATES_PANEL_CLOSE_TAG)
            // On the first line's middle, within 3 dp: the two scale their heights by different curves.
            assertEquals(first.center.y, close.center.y, 8f)
            assertTrue(close.left >= bounds(IN_PLACE_TABS_TAG).right - 1f)
        }
}
