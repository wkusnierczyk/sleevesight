package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Look again* on the insight card (#617): the first item of the split button's menu beside a
 * written insight, ending in the **AI** mark and read as "Look again, uses AI"; enabled while the
 * pipeline offers it, and a tap carries the key it was offered under and the track shown; absent
 * with no written insight. Under *Kept from before.*, while it looks: what it costs, the web search
 * named when it is looked up; once it found nothing new, that it did.
 */
@RunWith(RobolectricTestRunner::class)
class LookAgainScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val key = RunKey.of(normalizer, sundown)
    private val kept = track(insight = written("It is a song [MB1]."))
    private val view = MutableStateFlow(PipelineView(kept, aiReady = true, cardShown = true, lookAgainKey = key))
    private val taps = mutableListOf<Pair<RunKey, NowPlaying>>()

    private fun show(block: () -> Unit) {
        val actions = NowPlayingActions(normalizer::normalize, lookAgain = { tapped, shown -> taps += tapped to shown })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val pipeline by view.collectAsState()
                    val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
                    val state = NowPlayingState(pipeline, sundown, playback, settings = Settings())
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.onNodeWithTag(WRITE_MODE_TAG).performScrollTo()
            block()
        }
    }

    private fun openMenu() {
        compose.onNodeWithTag(WRITE_MODE_TAG).performClick()
        compose.waitForIdle()
    }

    private val item get() = compose.onNodeWithTag(LOOK_AGAIN_TAG)

    @Test
    fun aWrittenInsightOffersLookAgainFirstInTheMenuWithTheAiMark() {
        show {
            compose
                .onNodeWithTag(WRITE_MODE_TAG)
                .assertContentDescriptionEquals("Writing mode: On track change (auto), and Look again")
            openMenu()
            item.assertIsEnabled().assertContentDescriptionEquals("Look again, uses AI")
            val marks = compose.onAllNodesWithTag(AI_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            val inItem =
                marks.count { mark ->
                    val bounds = item.fetchSemanticsNode().boundsInRoot
                    bounds.contains(mark.boundsInRoot.center)
                }
            assertEquals(1, inItem)
            // Above the modes.
            val first = item.fetchSemanticsNode().boundsInRoot
            val mode = compose.onNodeWithText("On demand").fetchSemanticsNode().boundsInRoot
            assertEquals(true, first.bottom <= mode.top)

            item.performClick()
            compose.waitForIdle()
            assertEquals(listOf(key to sundown), taps)
            item.assertDoesNotExist()
        }
    }

    @Test
    fun lookAgainIsDisabledWhileThePipelineDoesNotOfferIt() {
        view.value = PipelineView(kept, aiReady = true, cardShown = true)
        show {
            openMenu()
            item.assertIsNotEnabled()
        }
    }

    @Test
    fun noWrittenInsightHasNoLookAgain() {
        view.value =
            PipelineView(track(insight = InsightState.Idle(canWrite = true)), aiReady = true, lookAgainKey = key)
        show {
            compose.onNodeWithTag(WRITE_MODE_TAG).assertContentDescriptionEquals("Writing mode: On track change (auto)")
            openMenu()
            item.assertDoesNotExist()
        }
    }

    @Test
    fun whileItLooksTheCardSaysWhatItCosts() {
        view.value = PipelineView(kept, aiReady = true, cardShown = true, lookingAgain = true)
        show {
            compose
                .onNodeWithText(
                    "Looking every source up again. If they find anything new, the insight is written again, " +
                        "a paid call.",
                ).assertExists()
        }
        val searching = kept.copy(sources = kept.sources + (SourceId.WEB to SourceState.Pending))
        view.value = PipelineView(searching, aiReady = true, cardShown = true, lookingAgain = true)
        show {
            compose
                .onNodeWithText(
                    "Looking every source up again, the web search included: a paid search on your Exa key. " +
                        "If they find anything new, the insight is written again, a paid call.",
                ).assertExists()
        }
    }

    @Test
    fun nothingNewIsSaidUnderKeptFromBefore() {
        view.value = PipelineView(kept, aiReady = true, cardShown = true, lookAgainKey = key, nothingNew = true)
        show {
            val above = compose.onNodeWithText("Kept from before.").fetchSemanticsNode().boundsInRoot
            val line = compose.onNodeWithText("The sources found nothing new.").fetchSemanticsNode().boundsInRoot
            assertEquals(above.bottom, line.top, 1f)
        }
    }

    @Test
    fun aWebSearchSwitchedOffIsNotNamed() {
        val off = kept.copy(sources = kept.sources + (SourceId.WEB to done(outcome = Outcome.SourceOff)))
        view.value = PipelineView(off, aiReady = true, cardShown = true, lookingAgain = true)
        show {
            compose.onNodeWithText("web search", substring = true).assertDoesNotExist()
        }
    }
}
