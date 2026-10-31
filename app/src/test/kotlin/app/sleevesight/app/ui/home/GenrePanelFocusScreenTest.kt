package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
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
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.track
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The genre panel under *Expandable boxes* moves the focus as *Sources*' panel does (#629, as
 * `SourcesPanelScreenTest`'s focus tests): to *Close* when a chip opens a genre, from a keyboard or
 * for a screen reader, and back to the chip tapped on closing; a panel that goes with a new track
 * while the focus is in it hands the focus to a chip; nothing moves while merely touched.
 */
@RunWith(RobolectricTestRunner::class)
class GenrePanelFocusScreenTest {
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

    private fun genres(vararg tags: String) =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.TRACK,
                    null,
                    tags.map { tag ->
                        val shown = tag.replaceFirstChar(Char::uppercase)
                        TrackLabel(
                            GenreLabel(tag, "family", shown, tag, level = GenreLevel.TRACK, artist = null),
                            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/$tag")),
                        )
                    },
                ),
            ),
        )

    // What Home shows: [show] starts it, and a test changes it as the pipeline would (a new track).
    private val current = mutableStateOf(track().copy(genres = genres("folk", "ballad")))

    // A control above the genres that can take the focus while touched, to hold it elsewhere.
    private val elsewhere = FocusRequester()

    private fun show(
        playing: TrackState = current.value,
        touch: Boolean = false,
        block: () -> Unit,
    ) {
        current.value = playing
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
                        Genres(current.value, Settings())
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

    /** The nodes, semantics nodes' ids, that the screen reader's focus was moved to, after the first [from] events. */
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

    /** A new track playing, its genres gathered at once, which closes the panel. */
    private fun nextTrack(vararg tags: String) {
        current.value = track(carefree).copy(genres = genres(*tags))
    }

    @Test
    fun fromAKeyboardTheFocusMovesToCloseOnOpeningAndBackToTheTappedChipOnClosing() =
        show {
            // The second chip, so the focus is not merely back at the first.
            tap(BALLAD)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            tap(GENRE_PANEL_CLOSE_TAG)
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(BALLAD).assertIsFocused()
        }

    @Test
    fun fromAKeyboardAnotherChipSwitchesThePanelAndTheFocusMovesToCloseAgain() =
        show {
            tap(FOLK)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            // From *Close* to the other chip, and Enter on it, as a keyboard does: the focus is
            // on the chip, not still on *Close*, when the panel switches.
            compose.onNodeWithTag(BALLAD).requestFocus()
            compose.onNodeWithTag(BALLAD).assertIsFocused()
            compose.onNodeWithTag(BALLAD).performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
            compose.onNodeWithTag(GENRE_PANEL_HEADING_TAG).assertTextEquals("Ballad")
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            tap(GENRE_PANEL_CLOSE_TAG)
            compose.onNodeWithTag(BALLAD).assertIsFocused()
        }

    @Test
    fun fromAKeyboardTheSameChipAgainClosesThePanelWithTheFocusOnTheChip() =
        show {
            tap(FOLK)
            tap(FOLK)
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(FOLK).assertIsFocused()
        }

    @Test
    fun touchedNothingTakesTheFocus() =
        show(touch = true) {
            tap(FOLK)
            assertEquals(0, focused())
            tap(GENRE_PANEL_CLOSE_TAG)
            assertEquals(0, focused())
        }

    @Test
    fun aScreenReaderIsMovedToCloseOnOpeningAndBackToTheChipOnClosing() {
        startScreenReader()
        show(touch = true) {
            var before = sentSoFar()
            tap(BALLAD)
            val close = idOf(GENRE_PANEL_CLOSE_TAG)
            assertTrue("screen reader focus moved to ${movedTo(before)}, Close is $close", close in movedTo(before))
            // Touched, the input focus is let go once the screen reader's has moved.
            assertEquals(0, focused())

            before = sentSoFar()
            tap(GENRE_PANEL_CLOSE_TAG)
            val chip = idOf(BALLAD)
            assertTrue("screen reader focus moved to ${movedTo(before)}, the chip is $chip", chip in movedTo(before))
        }
    }

    @Test
    fun aNewTrackClosingThePanelWithTheFocusInItHandsTheFocusToTheChipTappedIfStillShown() =
        show {
            tap(BALLAD)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            nextTrack("rock", "ballad")
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(BALLAD).assertIsFocused()
        }

    @Test
    fun aNewTrackClosingThePanelWithTheFocusInItHandsTheFocusToTheFirstChipWhenTheOneTappedIsGone() =
        show {
            tap(BALLAD)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            nextTrack("rock", "pop")
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(GENRE_PREFIX + "track:rock").assertIsFocused()
        }

    @Test
    fun labelsThatNoLongerHaveTheOpenGenreHandTheFocusFromCloseToTheFirstChip() =
        show {
            tap(BALLAD)
            compose.onNodeWithTag(GENRE_PANEL_CLOSE_TAG).assertIsFocused()
            // The same track, its labels replaced (a source landing later) without *Ballad*.
            current.value = current.value.copy(genres = genres("rock", "pop"))
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(GENRE_PREFIX + "track:rock").assertIsFocused()
        }

    @Test
    fun aNewTrackClosingThePanelWithTheFocusElsewhereMovesNothing() =
        show {
            tap(FOLK)
            compose.runOnIdle { elsewhere.requestFocus() }
            compose.onNodeWithTag(ELSEWHERE_TAG).assertIsFocused()
            nextTrack("folk", "pop")
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(ELSEWHERE_TAG).assertIsFocused()
        }

    private companion object {
        const val ELSEWHERE_TAG = "elsewhere"
        const val FOLK = GENRE_PREFIX + "track:folk"
        const val BALLAD = GENRE_PREFIX + "track:ballad"

        /** Where a virtual view's id sits in an accessibility node id (`AccessibilityNodeInfo`'s hidden layout). */
        const val VIRTUAL_ID_SHIFT = 32
    }
}
