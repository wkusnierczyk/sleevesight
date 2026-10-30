package app.sleevesight.app.ui.home

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityRecord
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.core.settings.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The guide's notes (#673): each a button, named by its part, whose tap opens a panel about the part
 * over the picture, closed by a second tap or *Close*, which hands the focus back to the note; the
 * picture no longer one image to a screen reader.
 */
@RunWith(RobolectricTestRunner::class)
// Real text metrics, so the panel is placed as on a phone.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GuideNotesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun show(block: () -> Unit) {
        val actions = NowPlayingActions(normalizer::normalize, playerLabel = { "Spotify" })
        val settings = Settings(followedPlayers = mapOf(Settings.SPOTIFY to true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(NowPlayingState(PipelineView(), playing = null, settings = settings), actions)
                    }
                }
            }
            compose.awaitIdleHome()
            compose.waitUntil(WAIT) {
                compose.onAllNodesWithTag(guideNoteTag(GuidePart.SOURCES)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    @After
    fun stopScreenReader() {
        // The accessibility manager outlives the test: leave no screen reader on for the next one.
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setTouchExplorationEnabled(false)
        manager.setEnabledAccessibilityServiceList(emptyList())
    }

    /** Turns on a screen reader that explores by touch, as TalkBack does, for Compose to report to. */
    private fun startScreenReader() {
        val manager = shadowOf(app.getSystemService(AccessibilityManager::class.java))
        manager.setEnabled(true)
        manager.setTouchExplorationEnabled(true)
        manager.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    private fun sentSoFar() =
        shadowOf(app.getSystemService(AccessibilityManager::class.java)).sentAccessibilityEvents.size

    /** The nodes, by semantics id, the screen reader's focus was moved to after the first [from] events. */
    private fun movedTo(from: Int): List<Int> =
        shadowOf(app.getSystemService(AccessibilityManager::class.java))
            .sentAccessibilityEvents
            .drop(from)
            .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED }
            .map { event ->
                // The virtual view it comes from (`AccessibilityRecord`'s hidden source id).
                val source = AccessibilityRecord::class.java.getMethod("getSourceNodeId").invoke(event) as Long
                (source shr VIRTUAL_ID_SHIFT).toInt()
            }

    private fun note(part: GuidePart) = compose.onNodeWithTag(guideNoteTag(part))

    private fun panels() = compose.onAllNodesWithTag(GUIDE_NOTE_PANEL_TAG).fetchSemanticsNodes()

    @Test
    fun eachNoteIsAButtonNamedByThePartItPointsAt() =
        show {
            for (part in GuidePart.entries) {
                val named = text(R.string.home_guide_named_note, text(part.label), text(part.note))
                val node = note(part).fetchSemanticsNode()
                assertEquals(listOf(named), node.config.getOrNull(SemanticsProperties.ContentDescription))
                assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
                assertTrue(node.config.contains(SemanticsActions.OnClick))
            }
            assertEquals(
                listOf("Settings: tap to configure"),
                note(GuidePart.SETTINGS).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
            )
        }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun eachNotesTargetIsAtLeast48DpAndNoTwoOverlap() = notesHaveRoomyTargets()

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeEachNotesTargetIsAtLeast48DpAndNoTwoOverlap() = notesHaveRoomyTargets()

    private fun notesHaveRoomyTargets() =
        show {
            for (part in GuidePart.entries) {
                // Its bounds, which its touch target covers (no smaller than them).
                note(part).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                // Over its text as drawn.
                val text = bounds(guideNoteTextTag(part))
                val target = bounds(guideNoteTag(part))
                assertTrue("$part: $text in $target", text.top >= target.top - 1f && text.bottom <= target.bottom + 1f)
            }
            val targets = GuidePart.entries.map { bounds(guideNoteTag(it)) }
            val guide = bounds(GUIDE_PICTURE_TAG)
            val drawn = bounds(GUIDE_DRAWN_TAG)
            for (target in targets) {
                // Inside the guide; one under the picture's corner below the picture, clear of its magnifier.
                assertTrue("$target in $guide", target.top >= guide.top - 1f && target.bottom <= guide.bottom + 1f)
                if (target.left < drawn.right) assertTrue("$target under $drawn", target.top >= drawn.bottom - 1f)
            }
            for ((i, a) in targets.withIndex()) {
                for (b in targets.drop(i + 1)) {
                    assertTrue(
                        "$a and $b",
                        a.intersect(b).let {
                            it.width <= 0f ||
                                it.height <= 0f
                        },
                    )
                }
            }
        }

    @Test
    fun aTapBetweenTwoStackedNotesGoesToTheNearerOne() =
        show {
            val upper = bounds(guideNoteTextTag(GuidePart.INSIGHT))
            val lower = bounds(guideNoteTextTag(GuidePart.CITE))
            val guide = bounds(GUIDE_PICTURE_TAG)
            val gap = lower.top - upper.bottom
            assertTrue("gap $gap", gap > 0f)
            val tapAt = { y: Float ->
                compose.onNodeWithTag(GUIDE_PICTURE_TAG).performTouchInput {
                    click(Offset(lower.left + 10f - guide.left, y - guide.top))
                }
                compose.waitForIdle()
            }
            // A little nearer the upper note's text: its panel.
            tapAt(upper.bottom + gap * 0.4f)
            compose.onNodeWithTag(GUIDE_NOTE_PANEL_TEXT_TAG).assert(hasText(text(R.string.home_guide_about_insight)))
            compose.onNodeWithTag(GUIDE_NOTE_CLOSE_TAG).performClick()
            compose.waitForIdle()
            // A little nearer the lower one's: its.
            tapAt(upper.bottom + gap * 0.6f)
            compose.onNodeWithTag(GUIDE_NOTE_PANEL_TEXT_TAG).assert(hasText(text(R.string.home_guide_about_cite)))
        }

    @Test
    fun thePictureIsNoLongerOneImageAndTheDrawnHomeIsNotRead() =
        show {
            val image = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)
            assertEquals(0, compose.onAllNodes(image).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodes(hasText("Blue in Green")).fetchSemanticsNodes().size)
            // Its way in for a screen reader is the magnifier.
            compose
                .onNodeWithTag(GUIDE_MAGNIFY_TAG)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.home_guide_magnify)),
                    ),
                )
        }

    @Test
    fun aTapOnANoteOpensItsPanelAndASecondTapClosesIt() =
        show {
            assertEquals(0, panels().size)
            note(GuidePart.WRITE).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, panels().size)
            compose
                .onNodeWithTag(GUIDE_NOTE_PANEL_TEXT_TAG)
                .assert(hasText(text(R.string.home_guide_part_write)))
                .assert(hasText(text(R.string.home_guide_about_write)))
            // Another note's tap shows its panel instead.
            note(GuidePart.SETTINGS).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(GUIDE_NOTE_PANEL_TEXT_TAG).assert(hasText(text(R.string.home_guide_about_settings)))
            note(GuidePart.SETTINGS).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(0, panels().size)
        }

    @Test
    fun dismissClosesThePanelAndAScreenReadersFocusGoesBackToTheNote() {
        startScreenReader()
        show {
            note(GuidePart.GENRES).performScrollTo().performClick()
            compose.waitForIdle()
            val before = sentSoFar()
            compose.onNodeWithTag(GUIDE_NOTE_CLOSE_TAG).performClick()
            compose.waitForIdle()
            assertEquals(0, panels().size)
            val genres = note(GuidePart.GENRES).fetchSemanticsNode().id
            assertTrue(
                "screen reader focus moved to ${movedTo(before)}, the note is $genres",
                genres in movedTo(before),
            )
        }
    }

    @Test
    fun thePanelIsARegionReadAsItOpensRightAfterItsNote() =
        show {
            note(GuidePart.INSIGHT).performScrollTo().performClick()
            compose.waitForIdle()
            val region = compose.onNodeWithTag(GUIDE_NOTE_PANEL_TEXT_TAG).fetchSemanticsNode()
            assertEquals(LiveRegionMode.Polite, region.config.getOrNull(SemanticsProperties.LiveRegion))
            val panel = compose.onNodeWithTag(GUIDE_NOTE_PANEL_TAG).fetchSemanticsNode()
            val index = { tag: String ->
                compose
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.TraversalIndex)
            }
            val after = panel.config.getOrNull(SemanticsProperties.TraversalIndex)!!
            assertTrue(after > index(guideNoteTag(GuidePart.INSIGHT))!!)
            assertTrue(after < index(guideNoteTag(GuidePart.CITE))!!)
        }

    @Test
    fun thePanelLeavesItsNoteInSightAndLiesOverThePicture() =
        show {
            for (part in GuidePart.entries) {
                note(part).performScrollTo().performClick()
                compose.waitForIdle()
                val panel = compose.onNodeWithTag(GUIDE_NOTE_PANEL_TAG).fetchSemanticsNode().boundsInRoot
                val at = note(part).fetchSemanticsNode().boundsInRoot
                val guide = compose.onNodeWithTag(GUIDE_PICTURE_TAG).fetchSemanticsNode().boundsInRoot
                assertTrue("$part: $panel and $at", panel.bottom <= at.top || panel.top >= at.bottom)
                // On the picture's side, from its left edge.
                assertEquals(guide.left, panel.left, 1f)
                note(part).performScrollTo().performClick()
                compose.waitForIdle()
            }
        }

    private companion object {
        const val WAIT = 5_000L
        const val VIRTUAL_ID_SHIFT = 32
    }
}
