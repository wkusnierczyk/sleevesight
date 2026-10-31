package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.CallId
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.RunId
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The **AI** mark on Home's buttons that call the AI service (#624): every Write of the insight
 * card's split button ends in it, enabled or not, after its label and on its baseline, and reads as
 * "<label>, uses AI" with the mark itself hidden; *Stop writing* and the arrow, which make no call,
 * do not.
 * Both halves are drawn as tall as Home's chips, 32 dp, growing alike with a large font, in a row
 * 48 dp high, and each answers a tap across 48 dp.
 */
@RunWith(RobolectricTestRunner::class)
class AiMarkScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = MutableStateFlow<Settings?>(Settings())
    private val view = MutableStateFlow(PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true))

    private fun show(block: () -> Unit) {
        val actions = NowPlayingActions(normalizer::normalize)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val shown by settings.collectAsState()
                    val pipeline by view.collectAsState()
                    val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
                    val state = NowPlayingState(pipeline, sundown, playback, settings = shown)
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.onNodeWithTag(WRITE_MODE_TAG).performScrollTo()
            block()
        }
    }

    private val offered = track(insight = InsightState.Idle(canWrite = true))

    // The marks inside the node tagged [tag], in the tree as drawn, where the hidden mark still is.
    private fun marksIn(tag: String) =
        compose
            .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun SemanticsNodeInteraction.carriesTheMark(label: String) {
        assertTextEquals(label).assertContentDescriptionEquals("$label, uses AI")
        val tag = fetchSemanticsNode().config[SemanticsProperties.TestTag]
        assertEquals("one mark on $label", 1, marksIn(tag))
    }

    @Test
    fun everyWriteEndsInTheMarkAndReadsAsUsesAi() {
        // Write insight, in every mode (#625).
        for (mode in WriteAutomatically.entries) {
            settings.value = Settings(writeAutomatically = mode)
            view.value = PipelineView(offered, aiReady = true, waiting = true)
            show { compose.onNodeWithTag(WRITE_TAG).assertIsEnabled().carriesTheMark("Write insight") }
        }
        // Rewrite, beside the card paid for once a settings change changed its key.
        settings.value = Settings()
        val kept = track(insight = written("It is a song [MB1].", cached = true))
        view.value = PipelineView(kept, aiReady = true, rewrite = true, cardShown = true)
        show { compose.onNodeWithTag(WRITE_TAG).assertIsEnabled().carriesTheMark("Rewrite insight") }
    }

    @Test
    fun aDisabledWriteKeepsItsMark() {
        view.value = PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true)
        show { compose.onNodeWithTag(NO_ACTION_TAG).assertIsNotEnabled().carriesTheMark("Write insight") }
    }

    @Test
    fun stopAndTheArrowMakeNoCallAndCarryNoMark() {
        view.value =
            PipelineView(track(insight = InsightState.Writing("It is")), aiReady = true, stopCall = CallId(RunId(1), 1))
        show {
            val stop = compose.onNodeWithTag(STOP_TAG).assertTextEquals("Stop writing")
            stop.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            assertEquals(0, marksIn(STOP_TAG))
            assertEquals(0, marksIn(WRITE_MODE_TAG))
            val anywhere = compose.onAllNodes(hasTestTag(AI_MARK_TAG), useUnmergedTree = true)
            assertEquals(0, anywhere.fetchSemanticsNodes().size)
        }
    }

    // Text measured as on a phone.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theMarkFollowsTheLabelOnItsBaseline() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        view.value = PipelineView(offered, aiReady = true, waiting = true)
        show {
            val inWrite = hasText("Write insight") and hasAnyAncestor(hasTestTag(WRITE_TAG))
            val label = compose.onNode(inWrite, useUnmergedTree = true)
            val mark = compose.onNodeWithTag(AI_MARK_TAG, useUnmergedTree = true)
            val labelBounds = label.getUnclippedBoundsInRoot()
            val markBounds = mark.getUnclippedBoundsInRoot()
            assertTrue("after the label", markBounds.left >= labelBounds.right)
            // Smaller than the label, so a centred mark would sit higher: their baselines meet.
            val smaller = markBounds.bottom - markBounds.top < labelBounds.bottom - labelBounds.top
            assertTrue("smaller than the label", smaller)
            val labelBaseline = labelBounds.top + label.getAlignmentLinePosition(FirstBaseline)
            val markBaseline = markBounds.top + mark.getAlignmentLinePosition(FirstBaseline)
            assertEquals(labelBaseline.value, markBaseline.value, TOLERANCE)
        }
    }

    // Text measured as on a phone.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun bothHalvesAreDrawnAsTallAsTheChipsInA48dpRowAndAnswerATapAcross48dp() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        view.value = PipelineView(offered, aiReady = true, waiting = true)
        show {
            // The 48 dp the targets need is kept in the layout, not only lent by the touch handling.
            compose.onNodeWithTag(WRITE_HALVES_TAG).assertHeightIsEqualTo(48.dp)
            for (tag in listOf(WRITE_TAG, WRITE_MODE_TAG)) {
                compose
                    .onNodeWithTag(tag)
                    .assertHeightIsEqualTo(32.dp)
                    .assertTouchHeightIsEqualTo(48.dp)
            }
            compose.onNodeWithTag(WRITE_MODE_TAG).assertTouchWidthIsEqualTo(48.dp)
        }
    }

    // At twice the font size the label's line is taller than 32 dp: both halves grow with it, alike.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(fontScale = 2f)
    fun atALargeFontBothHalvesGrowAlike() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        view.value = PipelineView(offered, aiReady = true, waiting = true)
        show {
            val write = compose.onNodeWithTag(WRITE_TAG).getUnclippedBoundsInRoot()
            val arrow = compose.onNodeWithTag(WRITE_MODE_TAG).getUnclippedBoundsInRoot()
            assertTrue("taller than 32 dp", (write.bottom - write.top) > 32.dp)
            assertEquals((write.bottom - write.top).value, (arrow.bottom - arrow.top).value, TOLERANCE)
            assertEquals(write.top.value, arrow.top.value, TOLERANCE)
        }
    }

    private companion object {
        // Half a dp: each edge or baseline rounds to whole pixels.
        const val TOLERANCE = 0.5f
    }
}
