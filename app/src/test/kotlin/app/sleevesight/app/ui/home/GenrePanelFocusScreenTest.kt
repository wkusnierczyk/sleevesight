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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A genre under *Expandable boxes* unfolds under the chips with no *Close* (#786), so a tap on a chip
 * leaves the focus on it, from a keyboard or for a screen reader, unfolding, replacing or folding; a
 * genre that goes with a new track, or labels without it, while the focus is in it hands the focus
 * to a chip (#629); so does *Retry* under its failed definition as it gives way to the loading line;
 * nothing moves while merely touched.
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
                        GenresContent(current.value, Settings(), PanelNotes(notes))
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

    // A genre's notes with *Why is this …?* offered, something to hold the focus in the genre; its
    // definition as [definition] holds it, which *Retry* turns to looking.
    private val definition = MutableStateFlow<DefinitionState?>(null)
    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> = definition

            override fun retryDefinition(genre: String) {
                definition.value = DefinitionState.Looking
            }

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> = flowOf(GenreReason.Offered)

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    // The focus put in the genre, on its *Why is this …?*, as a keyboard's would be.
    private fun focusInGenre() {
        compose.onNodeWithTag(GENRE_WHY_TAG).performScrollTo().requestFocus()
        compose.onNodeWithTag(GENRE_WHY_TAG).assertIsFocused()
    }

    // Enter on [tag], focused, as a keyboard does.
    private fun press(tag: String) {
        compose.onNodeWithTag(tag).requestFocus()
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
    }

    @Test
    fun fromAKeyboardTheFocusStaysOnTheChipUnfoldingReplacingAndFolding() =
        show {
            // The second chip, so the focus is not merely back at the first.
            press(BALLAD)
            assertEquals(1, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(BALLAD).assertIsFocused()
            press(FOLK)
            compose.onNodeWithTag(FOLK).assertIsSelected().assertIsFocused()
            press(FOLK)
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(FOLK).assertIsFocused()
        }

    @Test
    fun fromAKeyboardEnterOnAnotherChipReplacesTheGenreWithTheFocusOnIt() =
        show {
            tap(FOLK)
            compose.onNodeWithTag(BALLAD).requestFocus()
            compose.onNodeWithTag(BALLAD).performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
            compose.onNodeWithTag(BALLAD).assertIsSelected().assertIsFocused()
            assertEquals(1, count(GENRE_PANEL_TAG))
        }

    @Test
    fun touchedNothingTakesTheFocus() =
        show(touch = true) {
            tap(FOLK)
            assertEquals(0, focused())
            tap(FOLK)
            assertEquals(0, focused())
        }

    @Test
    fun aScreenReaderIsMovedNowhereOnUnfoldingOrFolding() {
        startScreenReader()
        show(touch = true) {
            val before = sentSoFar()
            tap(BALLAD)
            tap(BALLAD)
            val chip = idOf(BALLAD)
            assertTrue("screen reader focus moved to ${movedTo(before)}", movedTo(before).all { it == chip })
        }
    }

    @Test
    fun aNewTrackFoldingTheGenreWithTheFocusInItHandsTheFocusToTheChipTappedIfStillShown() =
        show {
            tap(BALLAD)
            focusInGenre()
            nextTrack("rock", "ballad")
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(BALLAD).assertIsFocused()
        }

    @Test
    fun aNewTrackFoldingTheGenreWithTheFocusInItHandsTheFocusToTheFirstChipWhenTheOneTappedIsGone() =
        show {
            tap(BALLAD)
            focusInGenre()
            nextTrack("rock", "pop")
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(GENRE_PREFIX + "track:rock").assertIsFocused()
        }

    @Test
    fun labelsThatNoLongerHaveTheOpenGenreHandTheFocusToTheFirstChip() =
        show {
            tap(BALLAD)
            focusInGenre()
            // The same track, its labels replaced (a source landing later) without *Ballad*.
            current.value = current.value.copy(genres = genres("rock", "pop"))
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(GENRE_PREFIX + "track:rock").assertIsFocused()
        }

    @Test
    fun aNewTrackFoldingTheGenreWithTheFocusElsewhereMovesNothing() =
        show {
            tap(FOLK)
            compose.runOnIdle { elsewhere.requestFocus() }
            compose.onNodeWithTag(ELSEWHERE_TAG).assertIsFocused()
            nextTrack("folk", "pop")
            compose.waitForIdle()
            assertEquals(0, count(GENRE_PANEL_TAG))
            compose.onNodeWithTag(ELSEWHERE_TAG).assertIsFocused()
        }

    @Test
    fun retryUnderAFailedDefinitionHandsTheFocusToTheChipAsItGoes() {
        definition.value = DefinitionState.Failed
        show {
            tap(BALLAD)
            val retry =
                compose.onNode(
                    hasTestTag(RETRY_TAG) and hasAnyAncestor(hasTestTag(GENRE_DEFINITION_FAILED_TAG)),
                )
            retry.performScrollTo().requestFocus()
            retry.assertIsFocused()
            retry.performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
            assertEquals(0, count(GENRE_DEFINITION_FAILED_TAG))
            compose.onNodeWithTag(BALLAD).assertIsFocused()
        }
    }

    private companion object {
        const val ELSEWHERE_TAG = "elsewhere"
        const val FOLK = GENRE_PREFIX + "track:folk"
        const val BALLAD = GENRE_PREFIX + "track:ballad"

        /** Where a virtual view's id sits in an accessibility node id (`AccessibilityNodeInfo`'s hidden layout). */
        const val VIRTUAL_ID_SHIFT = 32
    }
}
