package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.SleevesightApplication
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

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
                    focus = LocalFocusManager.current
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                // A button above the track, as Home's header has (the player row was
                                // one until it went into the header's player menu, #1209).
                                Box(Modifier.size(48.dp).testTag(aboveTag).clickable {})
                                NowPlayingContent(current.value, actions)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for a recreated activity (a rotation).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var focus: FocusManager? = null

    private val aboveTag = "above-the-track"

    // Saves the screen, then composes it again from what was saved, playing [then].
    private fun restore(then: NowPlayingState) {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        current.value = then
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private val accessibility get() = shadowOf(app.getSystemService(AccessibilityManager::class.java))

    // Puts the screen reader's focus on [tag]'s node, as TalkBack does when it moves there.
    private fun accessibilityFocus(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        compose.runOnUiThread {
            val host = node.root as View
            val moved =
                host.accessibilityNodeProvider?.performAction(
                    node.id,
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            assertEquals(true, moved)
        }
        compose.waitForIdle()
    }

    // Whether the screen reader's focus is on [tag]'s node, as the node provider tells a screen reader.
    private fun accessibilityFocused(tag: String): Boolean {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        var focused = false
        compose.runOnUiThread {
            val host = node.root as View
            focused =
                host.accessibilityNodeProvider?.createAccessibilityNodeInfo(node.id)?.isAccessibilityFocused == true
        }
        return focused
    }

    @After
    fun close() {
        // The accessibility manager outlives the test: leave no screen reader on for the next one.
        accessibility.setTouchExplorationEnabled(false)
        accessibility.setEnabledAccessibilityServiceList(emptyList())
        accessibility.setEnabled(false)
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
    fun closeAndOpeningAgainStartsEachTabAfresh() =
        show {
            tap(GENRES_CHIP_TAG)
            val folk = GENRE_PREFIX + "track:folk"
            tap(folk)
            tap(GENRES_PANEL_CLOSE_TAG)
            tap(GENRES_CHIP_TAG)
            // On Ballad, the first, as the panel opens on a track.
            compose.onNodeWithTag(GENRE_PREFIX + "track:ballad").assertIsSelected()
            compose.onNodeWithTag(folk).assertIsNotSelected()
        }

    @Test
    fun aTabsStateSavedWithTheScreenIsNotRestoredOntoAnotherTrack() =
        show {
            // Folk chosen in *Genres*, then *Dates* shown: Genres' state is kept aside.
            tap(GENRES_CHIP_TAG)
            val folk = GENRE_PREFIX + "track:folk"
            tap(folk)
            tap(dates)
            // The screen restored on another track with the same genres: the panel folded.
            restore(state(labelled(carefree)))
            onlyOpen("none")
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithTag(GENRE_PREFIX + "track:ballad").assertIsSelected()
            compose.onNodeWithTag(folk).assertIsNotSelected()
        }

    @Test
    fun aTabsStateSavedWithTheScreenIsRestoredOnTheSameTrack() =
        show {
            tap(GENRES_CHIP_TAG)
            val folk = GENRE_PREFIX + "track:folk"
            tap(folk)
            tap(dates)
            restore(state())
            onlyOpen(DATES_PANEL_TAG)
            tap(genres)
            compose.onNodeWithTag(folk).assertIsSelected()
        }

    // A screen reader exploring by touch, as TalkBack does, for Compose to report to.
    private fun startScreenReader() {
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        accessibility.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    // The clock stopped, then [action] and frames after it, each laid out as a phone lays out each
    // frame. Robolectric's clock run without a layout in each frame (`waitForIdle`) drops the screen
    // reader's focus a few frames after it is placed, which a phone, laying out every frame, does not.
    private fun framed(action: () -> Unit) {
        compose.mainClock.autoAdvance = false
        action()
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(IN_PLACE_TABS_TAG).fetchSemanticsNodes()
        }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun withAScreenReaderOpeningPutsItsFocusOnTheTabAndCloseOnTheButton() {
        startScreenReader()
        show {
            framed { click(GENRES_CHIP_TAG) }
            assertTrue("on the Genres tab", accessibilityFocused(genres))
            framed { click(GENRES_PANEL_CLOSE_TAG) }
            assertTrue("on the Genres button", accessibilityFocused(GENRES_CHIP_TAG))
        }
    }

    @Test
    fun aNewTrackHandsAScreenReadersFocusInThePanelToTheButtonWithNoInputFocusThere() {
        startScreenReader()
        show {
            framed { click(DATES_CHIP_TAG) }
            // The input focus gone from the panel, and the screen reader's on another tab in it.
            framed { focus!!.clearFocus(force = true) }
            accessibilityFocus(genres)
            compose.onNodeWithTag(dates).assertIsNotFocused()
            framed { current.value = state(labelled(carefree)) }
            onlyOpen("none")
            assertTrue("on the Dates button", accessibilityFocused(DATES_CHIP_TAG))
            compose.onNodeWithTag(DATES_CHIP_TAG).assertIsFocused()
        }
    }

    @Test
    fun aNewTrackDoesNotPullAScreenReadersFocusFromOutsideThePanel() {
        startScreenReader()
        show {
            framed { click(DATES_CHIP_TAG) }
            // The screen reader moved on, out of the panel, to the button above the track.
            framed { focus!!.clearFocus(force = true) }
            accessibilityFocus(aboveTag)
            framed { current.value = state(labelled(carefree)) }
            onlyOpen("none")
            // Not checked: that it stays on that button. A new track may drop the screen reader's focus
            // from it with no panel open at all, so that says nothing of this line.
            assertFalse("not moved to the Dates button", accessibilityFocused(DATES_CHIP_TAG))
            compose.onNodeWithTag(DATES_CHIP_TAG).assertIsNotFocused()
        }
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
    fun theSwitchTurnedOffShowsOnlyThePanelShownNotThoseShownBefore() =
        show {
            tap(DATES_CHIP_TAG)
            tap(genres)
            tap(lyrics)
            current.value = state(settings = Settings(onePanel = false))
            compose.waitForIdle()
            onlyOpen(LYRICS_PANEL_TAG)
            compose.onNodeWithTag(LYRICS_PANEL_HEADING_TAG).assertExists()
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

    private companion object {
        // Frames enough for the focus to be handed on: the move runs a frame or two after it is asked for.
        const val FRAMES = 10
    }
}
