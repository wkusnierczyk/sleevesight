package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityRecord
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * *Sources* on Home as a chip that opens its panel (#623): the chip drawn and read as a genre chip
 * is, the panel with its heading and *Close*, the focus moved between them, and the filter chips
 * inside drawn as genre chips, shown or hidden. What unfolds the list and how far is
 * `SourcesFoldScreenTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SourcesPanelScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private var inputModes: InputModeManager? = null

    @After
    fun stopScreenReader() {
        // The accessibility manager outlives the test: leave no screen reader on for the next one.
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setTouchExplorationEnabled(false)
        manager.setEnabledAccessibilityServiceList(emptyList())
        manager.setEnabled(false)
    }

    private fun text(id: Int) = app.getString(id)

    /** A genre chip on Home: the track's *Folk*. */
    private val folk =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.TRACK,
                    null,
                    listOf(
                        TrackLabel(
                            GenreLabel("folk", "family", "Folk", "folk", level = GenreLevel.TRACK, artist = null),
                            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/folk")),
                        ),
                    ),
                ),
            ),
        )

    private fun state(playing: TrackState) =
        NowPlayingState(
            PipelineView(playing, aiReady = true),
            playing.nowPlaying,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = Settings(),
        )

    // What Home shows: [show] starts it, and a test changes it as the pipeline would (a new track).
    private val current = mutableStateOf(state(track()))

    // A control above Home that can take the focus while touched, to hold it elsewhere.
    private val elsewhere = FocusRequester()

    private fun show(
        playing: TrackState = track(),
        touch: Boolean = false,
        block: () -> Unit,
    ) {
        current.value = state(playing)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // As on a phone being touched, where Compose's clickable controls take no focus of
                // their own; the test's input is otherwise taken as a keyboard's.
                if (touch) enterTouchMode(activity.window.decorView)
                activity.setContent {
                    inputModes = LocalInputModeManager.current
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .testTag(ELSEWHERE_TAG)
                                .focusRequester(elsewhere)
                                .focusable(),
                        )
                        NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            compose.waitForIdle()
            if (touch) compose.runOnUiThread { assertEquals(InputMode.Touch, inputModes!!.inputMode) }
            block()
        }
    }

    /** Puts [decor]'s window in touch mode, as a touch on the screen does. */
    private fun enterTouchMode(decor: View) {
        val root = decor.parent
        val ensure = root.javaClass.getDeclaredMethod("ensureTouchModeLocally", Boolean::class.javaPrimitiveType)
        ensure.isAccessible = true
        ensure.invoke(root, true)
        assertTrue(decor.isInTouchMode)
    }

    /** Turns on a screen reader that explores by touch, as TalkBack does, for Compose to report to. */
    private fun startScreenReader() {
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setEnabled(true)
        manager.setTouchExplorationEnabled(true)
        manager.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun focused() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().size

    /** The node, a semantics node's id, that the screen reader's focus was moved to, after the first [from] events. */
    private fun movedTo(from: Int): List<Int> =
        shadowOf(app.getSystemService(AccessibilityManager::class.java))
            .sentAccessibilityEvents
            .drop(from)
            .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED }
            .map(::virtualSource)

    /** The virtual view, a semantics node's id, that [event] comes from (`AccessibilityRecord`'s hidden source id). */
    private fun virtualSource(event: AccessibilityEvent): Int {
        val source = AccessibilityRecord::class.java.getMethod("getSourceNodeId").invoke(event) as Long
        return (source shr VIRTUAL_ID_SHIFT).toInt()
    }

    private fun sentSoFar() =
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).sentAccessibilityEvents.size

    private fun idOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().id

    // The chip as drawn, its rounded shape and no more of its 48 dp target.
    private fun picture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    private fun px(dp: Float) = (dp * app.resources.displayMetrics.density).toInt()

    // A chip's own colour, inside its rounded end at mid-height, before its text: its fill.
    private fun fillOf(chip: Bitmap) = chip.getPixel(px(INSIDE), chip.height / 2)

    // What a chip is drawn on: its top left corner, outside its rounded end.
    private fun groundOf(chip: Bitmap) = chip.getPixel(1, 1)

    // A chip's left edge at mid-height: its outline, if it has one.
    private fun edgeOf(chip: Bitmap) = chip.getPixel(1, chip.height / 2)

    private fun near(
        a: Int,
        b: Int,
    ) = abs(Color.red(a) - Color.red(b)) <= TOLERANCE &&
        abs(Color.green(a) - Color.green(b)) <= TOLERANCE &&
        abs(Color.blue(a) - Color.blue(b)) <= TOLERANCE

    private fun hex(colour: Int) = "#%08X".format(colour)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theChipIsDrawnAsAGenreChip() =
        show(track().copy(genres = folk)) {
            val sources = picture(SOURCES_CHIP_TAG)
            val genre = picture(GENRE_PREFIX + "track:folk")
            assertTrue(
                "the chip's fill ${hex(fillOf(sources))} is a genre chip's, ${hex(fillOf(genre))}",
                near(fillOf(sources), fillOf(genre)),
            )
            assertTrue("a fill of its own, not the ground's", !near(fillOf(sources), groundOf(sources)))
        }

    @Test
    fun aScreenReaderReadsTheChipAsTheHeadingSourcesAButtonCollapsed() =
        show {
            compose
                .onNodeWithTag(SOURCES_CHIP_TAG)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_sources_title)),
                    ),
                ).assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        text(R.string.home_fold_collapsed),
                    ),
                ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
                .assertTouchHeightIsEqualTo(48.dp)
        }

    @Test
    fun theChipOpensThePanelInItsPlaceWithItsHeadingAndCloseWhichFoldsItBack() =
        show {
            assertEquals(0, count(SOURCES_PANEL_TAG))
            tap(SOURCES_CHIP_TAG)
            assertEquals(0, count(SOURCES_CHIP_TAG))
            assertEquals(1, count(SOURCES_PANEL_TAG))
            compose
                .onNodeWithTag(SOURCES_PANEL_HEADING_TAG)
                .assertTextEquals(text(R.string.home_sources_title))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose
                .onNodeWithTag(SOURCES_PANEL_CLOSE_TAG)
                .assertTextEquals(text(R.string.page_close))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertTouchHeightIsEqualTo(48.dp)
            // Inside: the filter chips and the lines.
            compose.onNodeWithTag(CHIP_PREFIX + "MB").assertExists()
            compose.onNodeWithTag(LINE_PREFIX + "MB1").assertExists()

            tap(SOURCES_PANEL_CLOSE_TAG)
            assertEquals(1, count(SOURCES_CHIP_TAG))
            assertEquals(0, count(SOURCES_PANEL_TAG))
            assertEquals(0, count(LINE_PREFIX + "MB1"))
        }

    @Test
    fun fromAKeyboardTheFocusMovesToCloseOnOpeningAndBackToTheChipOnClosing() =
        show {
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_PANEL_CLOSE_TAG).assertIsFocused()
            tap(SOURCES_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(SOURCES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun touchedNothingTakesTheFocus() =
        show(touch = true) {
            tap(SOURCES_CHIP_TAG)
            assertEquals(0, focused())
            tap(SOURCES_PANEL_CLOSE_TAG)
            assertEquals(0, focused())
        }

    @Test
    fun aScreenReaderIsMovedToCloseOnOpeningAndBackToTheChipOnClosing() {
        startScreenReader()
        show(touch = true) {
            var before = sentSoFar()
            tap(SOURCES_CHIP_TAG)
            val close = idOf(SOURCES_PANEL_CLOSE_TAG)
            assertTrue("screen reader focus moved to ${movedTo(before)}, Close is $close", close in movedTo(before))
            // Touched, the input focus is let go once the screen reader's has moved.
            assertEquals(0, focused())

            before = sentSoFar()
            tap(SOURCES_PANEL_CLOSE_TAG)
            val chip = idOf(SOURCES_CHIP_TAG)
            assertTrue("screen reader focus moved to ${movedTo(before)}, the chip is $chip", chip in movedTo(before))
        }
    }

    @Test
    fun aCitationOpensThePanelAndLeavesTheFocusWhereItWas() =
        show(track(insight = written("It topped the charts [W1]."))) {
            compose.onNodeWithText("It topped the charts", substring = true).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "W1"
            }
            compose.waitForIdle()
            assertEquals(1, count(SOURCES_PANEL_TAG))
            compose.onNodeWithTag(SOURCES_PANEL_CLOSE_TAG).assertIsNotFocused()
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aSourceShownHasAGenreChipsFillAndOneHiddenIsOutlinedWithNone() =
        show {
            tap(SOURCES_CHIP_TAG)
            tap(CHIP_PREFIX + "W")
            val shown = picture(CHIP_PREFIX + "MB")
            val hidden = picture(CHIP_PREFIX + "W")
            assertTrue("a shown chip has a fill: ${hex(fillOf(shown))}", !near(fillOf(shown), groundOf(shown)))
            assertTrue(
                "a hidden chip has none: ${hex(fillOf(hidden))} on ${hex(groundOf(hidden))}",
                near(fillOf(hidden), groundOf(hidden)),
            )
            assertTrue("a hidden chip is outlined: ${hex(edgeOf(hidden))}", !near(edgeOf(hidden), groundOf(hidden)))
            assertTrue("a shown chip is not: ${hex(edgeOf(shown))}", near(edgeOf(shown), fillOf(shown)))
            assertNotEquals(fillOf(shown), fillOf(hidden))
        }

    @Test
    fun aScreenReaderReadsEachFilterChipAsASwitchWithWhetherItsLinesShow() =
        show {
            tap(SOURCES_CHIP_TAG)
            tap(CHIP_PREFIX + "W")

            fun check(
                tag: String,
                shown: Boolean,
            ) = compose
                .onNodeWithTag(CHIP_PREFIX + tag)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(shown)),
                ).assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        text(if (shown) R.string.home_source_lines_shown else R.string.home_source_lines_hidden),
                    ),
                ).assertTouchHeightIsEqualTo(48.dp)
            check("MB", shown = true)
            check("W", shown = false)
        }

    /** A new track playing, which folds the panel (*Keep Sources as you left them* off). */
    private fun nextTrack() {
        current.value = state(track(carefree))
    }

    @Test
    fun aNewTrackBeforeTheMoveToCloseRunsMovesNothingAndTheScreenReaderStays() {
        startScreenReader()
        show(touch = true) {
            compose.runOnIdle { elsewhere.requestFocus() }
            compose.onNodeWithTag(ELSEWHERE_TAG).assertIsFocused()
            compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo()
            val before = sentSoFar()
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag(SOURCES_CHIP_TAG).performClick()
                // In the same frame: the panel folds again before *Close* is ever laid out.
                nextTrack()
                repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            assertEquals(0, count(SOURCES_PANEL_TAG))
            // The screen reader is not sent to whatever holds the input focus.
            assertEquals(emptyList<Int>(), movedTo(before))
            compose.onNodeWithTag(ELSEWHERE_TAG).assertIsFocused()
        }
    }

    @Test
    fun aNewTrackFoldingThePanelWithTheFocusInItHandsTheFocusToTheChip() =
        show {
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_PANEL_CLOSE_TAG).assertIsFocused()
            nextTrack()
            compose.waitForIdle()
            assertEquals(0, count(SOURCES_PANEL_TAG))
            compose.onNodeWithTag(SOURCES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun aNewTrackFoldingThePanelWithTheFocusElsewhereMovesNothing() {
        startScreenReader()
        show(track(insight = written("It topped the charts [W1].")), touch = true) {
            compose.onNodeWithText("It topped the charts", substring = true).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "W1"
            }
            compose.waitForIdle()
            assertEquals(1, count(SOURCES_PANEL_TAG))
            val before = sentSoFar()
            nextTrack()
            compose.waitForIdle()
            assertEquals(0, count(SOURCES_PANEL_TAG))
            assertEquals(emptyList<Int>(), movedTo(before))
            assertEquals(0, focused())
        }
    }

    private companion object {
        const val ELSEWHERE_TAG = "elsewhere"

        // Frames for a move of the focus to run its course: it waits one, and one more for a screen reader.
        const val FRAMES = 4

        // An accessibility node id holds its virtual view's id in its upper half.
        const val VIRTUAL_ID_SHIFT = 32

        // Where a chip's fill is read, in dp from its left edge: inside its rounded end, before its text.
        const val INSIDE = 4f

        // How far two pixels' channels may differ and still be the same colour.
        const val TOLERANCE = 2
    }
}
