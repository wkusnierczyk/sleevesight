package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.CallId
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.RunId
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The turning record on the insight card (#785), as picked in the mock-up: *Writing…* with it for
 * the whole write, before the first words and under the words written so far, with what *Stop
 * writing* costs under it while Stop can end the call; and *Look again*'s sentence on a line of its
 * own under *As written previously.*, with it, while it looks, the rest of the footer's first line
 * joined as before, and no record once it is done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class InsightRecordScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val kept = track(insight = written("It is a song [MB1]."))

    private fun show(
        view: PipelineView,
        block: () -> Unit,
    ) {
        val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
        val state = NowPlayingState(view, sundown, playback, settings = Settings())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            compose.onNodeWithTag(WRITE_MODE_TAG).performScrollTo()
            compose.waitForIdle()
            block()
        }
    }

    private fun bounds(text: String): Rect = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun records(): List<Rect> =
        compose
            .onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }

    // The records before [line], on its first line: to its left, their centre within it.
    private fun recordsBefore(line: Rect): List<Rect> =
        records().filter { it.right <= line.left && it.center.y > line.top && it.center.y < line.bottom }

    // The texts of [texts] a screen reader reads, in the order it reads them: the merged tree, depth first.
    private fun readOrder(vararg texts: String): List<String> {
        fun walk(node: SemanticsNode): List<String> {
            val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            return own.map { it.text } + node.children.flatMap(::walk)
        }
        return walk(compose.onRoot().fetchSemanticsNode()).filter { it in texts }
    }

    @Test
    fun beforeTheFirstWordsWritingShowsWithTheRecord() {
        show(PipelineView(track(insight = InsightState.Writing("")), aiReady = true)) {
            val writing = bounds(text(R.string.home_insight_writing))
            assertEquals(1, recordsBefore(writing).size)
            compose.onNodeWithText(text(R.string.home_insight_writing_stop)).assertDoesNotExist()
        }
    }

    @Test
    fun whileStopCanEndTheCallWritingShowsWithTheRecordAndTheStopNoteUnderIt() {
        val view =
            PipelineView(
                track(insight = InsightState.Writing("It is a song.")),
                aiReady = true,
                stopCall = CallId(RunId(1), 1),
            )
        show(view) {
            val words = bounds("It is a song.")
            val writing = bounds(text(R.string.home_insight_writing))
            val stop = bounds(text(R.string.home_insight_writing_stop))
            assertEquals(1, recordsBefore(writing).size)
            // The words so far, then *Writing…*, then the note, with no record by the note.
            assertTrue(writing.top >= words.bottom)
            assertTrue(stop.top >= writing.bottom)
            assertEquals(0, recordsBefore(stop).size)
            val order =
                listOf("It is a song.", text(R.string.home_insight_writing), text(R.string.home_insight_writing_stop))
            assertEquals(order, readOrder(*order.toTypedArray()))
        }
    }

    @Test
    fun whileItLooksAgainItsSentenceIsOnItsOwnLineWithTheRecord() {
        show(PipelineView(kept, aiReady = true, cardShown = true, lookingAgain = true)) {
            val before = bounds(text(R.string.home_written_before))
            val looking = bounds(text(R.string.home_look_again_looking))
            assertTrue(looking.top >= before.bottom)
            assertEquals(1, recordsBefore(looking).size)
            assertEquals(0, recordsBefore(before).size)
            assertEquals(
                listOf(text(R.string.home_written_before), text(R.string.home_look_again_looking)),
                readOrder(text(R.string.home_written_before), text(R.string.home_look_again_looking)),
            )
        }
    }

    @Test
    fun whileItLooksAgainTheRestOfTheFooterLineStaysJoinedAboveIt() {
        val view = PipelineView(kept, aiReady = true, cardShown = true, lookingAgain = true, rewrite = true)
        show(view) {
            val joined = text(R.string.home_written_before) + " " + text(R.string.home_rewrite_offered)
            val line = bounds(joined)
            val looking = bounds(text(R.string.home_look_again_looking))
            assertTrue(looking.top >= line.bottom)
            assertEquals(1, recordsBefore(looking).size)
            assertEquals(
                listOf(joined, text(R.string.home_look_again_looking)),
                readOrder(joined, text(R.string.home_look_again_looking)),
            )
        }
    }

    @Test
    fun onceItIsDoneLookAgainHasNoRecordAndStaysInTheJoinedLine() {
        show(PipelineView(kept, aiReady = true, cardShown = true, nothingNew = true)) {
            val joined = text(R.string.home_written_before) + " " + text(R.string.home_look_again_nothing_new)
            compose.onNodeWithText(joined).assertExists()
            compose.onNodeWithText(text(R.string.home_look_again_looking)).assertDoesNotExist()
            assertEquals(emptyList<Rect>(), records())
        }
    }
}
