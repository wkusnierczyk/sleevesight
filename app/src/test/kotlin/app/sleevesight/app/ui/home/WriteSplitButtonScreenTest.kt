package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * The insight card's split button (#615): the leading half says what a tap does in each state,
 * the mode's Write while automatic writing waits for a tap, disabled while it runs by itself; the
 * arrow's menu is *Reactive*, each showing the other's change, and ends with the way to it in the
 * settings; an automatic mode chosen says each new track's insight is a paid call, *Now* says
 * nothing; *Saver*'s pause shows beside it; a screen reader reads the action, *Writing mode: <mode>*
 * as a drop-down list, and the modes as one choice among them; 48 dp targets; fixed while *Show
 * insights* is off.
 */
@RunWith(RobolectricTestRunner::class)
class WriteSplitButtonScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = MutableStateFlow<Settings?>(Settings())
    private val view =
        MutableStateFlow(PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true))

    private fun show(block: () -> Unit) {
        // The menu saves as the app's does: through the settings, which the card then reads.
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                chooseWritingMode = { mode -> settings.value = settings.value?.copy(writeAutomatically = mode) },
                openSettingsAt = { openedAt += it },
            )
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

    private val openedAt = mutableListOf<SettingsTarget>()

    private val arrow get() = compose.onNodeWithTag(WRITE_MODE_TAG)

    private fun item(mode: WriteAutomatically) = compose.onNodeWithTag(writingModeTag(mode))

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun choose(mode: WriteAutomatically) {
        arrow.performClick()
        compose.waitForIdle()
        item(mode).performClick()
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.reads(role: Role) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))

    private val offered = track(insight = InsightState.Idle(canWrite = true))
    private val notYet = track(insight = InsightState.Idle(canWrite = false))

    private fun leading(tag: String) = compose.onNodeWithTag(tag)

    private fun says(line: String) = compose.onNodeWithText(line).assertExists()

    @Test
    fun underNowWithNoInsightShownItIsWriteInsight() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        view.value = PipelineView(offered, aiReady = true, waiting = true)
        show {
            leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
            says("Tap Write insight to have it written from the sources below.")
        }
    }

    @Test
    fun underAnAutomaticModeBeforeTheFirstTapItIsTheModesWriteEnabled() {
        view.value = PipelineView(offered, aiReady = true, waiting = true)
        show {
            leading(WRITE_TAG).assertTextEquals("Write on change").assertIsEnabled()
            says("Tap Write on change to write this track’s insight and start writing automatically.")
        }
        settings.value = Settings(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)
        show {
            leading(WRITE_TAG).assertTextEquals("Write on skip").assertIsEnabled()
            says("Tap Write on skip to write this track’s insight and start writing automatically.")
        }
    }

    @Test
    fun whileSaverHasPausedItTheModesWriteResumesIt() {
        view.value = PipelineView(offered, aiReady = true, waiting = true, lapsed = true, paused = true)
        show {
            leading(WRITE_TAG).assertTextEquals("Write on change").assertIsEnabled()
            says(
                "Automatic writing paused: tracks changed while Sleevesight was not on screen. Tap Write on change " +
                    "to write this one and resume.",
            )
        }
    }

    @Test
    fun whileAutomaticWritingRunsByItselfTheModesWriteIsDisabled() {
        view.value = PipelineView(notYet, aiReady = true)
        show {
            leading(NO_ACTION_TAG).assertTextEquals("Write on change").assertIsNotEnabled()
            assertEquals(0, count(WRITE_TAG))
            says("Writing automatically on every track change.")
        }
        settings.value = Settings(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)
        show {
            leading(NO_ACTION_TAG).assertTextEquals("Write on skip").assertIsNotEnabled()
            says("Writing automatically when you skip a track.")
        }
    }

    @Test
    fun aTrackAutomaticWritingLeavesIsWrittenWithWriteInsight() {
        // Under On track skip, a track that followed another by itself: Write offered, nothing waits.
        settings.value = Settings(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)
        view.value = PipelineView(offered, aiReady = true)
        show {
            leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
            says("Tap Write insight to have it written from the sources below.")
        }
    }

    @Test
    fun anInsightShownIsTheModesWriteDisabledInEveryModeWithNoRewrite() {
        val labels =
            mapOf(
                WriteAutomatically.NEVER to "Write insight",
                WriteAutomatically.ON_EVERY_TRACK_CHANGE to "Write on change",
                WriteAutomatically.WHEN_I_SKIP to "Write on skip",
            )
        // Written now, and kept from before with the coordinator still offering to write it again.
        val shown =
            listOf(
                PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true),
                PipelineView(
                    track(insight = written("It is a song [MB1].", cached = true)),
                    aiReady = true,
                    rewrite = true,
                ),
            )
        for (pipeline in shown) {
            view.value = pipeline
            for ((mode, label) in labels) {
                settings.value = Settings(writeAutomatically = mode)
                show {
                    leading(NO_ACTION_TAG).assertTextEquals(label).assertIsNotEnabled()
                    assertEquals(0, count(WRITE_TAG))
                    compose.onNodeWithText("Rewrite", substring = true).assertDoesNotExist()
                    arrow.assertIsEnabled()
                }
            }
        }
    }

    @Test
    fun aFailedOrStoppedCallOffersWriteInsightAgainInEveryMode() {
        for (insight in listOf(InsightState.Failed("overloaded"), InsightState.Stopped)) {
            view.value = PipelineView(track(insight = insight), aiReady = true, retry = true)
            for (mode in WriteAutomatically.entries) {
                settings.value = Settings(writeAutomatically = mode)
                show {
                    leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
                    says("Write insight tries again, as a new call to your AI service.")
                }
            }
        }
    }

    @Test
    fun whileWritingItIsStop() {
        view.value =
            PipelineView(track(insight = InsightState.Writing("It is")), aiReady = true, stopCall = CallId(RunId(1), 1))
        show {
            leading(STOP_TAG).assertTextEquals("Stop").assertIsEnabled()
            assertEquals(0, count(WRITE_TAG))
            // One line, in place of *Writing…*.
            says("Writing from the sources. Tap Stop to stop it.")
            compose.onNodeWithText("Writing…").assertDoesNotExist()
        }
    }

    @Test
    fun withNothingToWriteFromItIsDisabled() {
        view.value = PipelineView(track(insight = InsightState.NoEvidence), aiReady = true)
        show { leading(NO_ACTION_TAG).assertIsNotEnabled() }
    }

    @Test
    fun aModeChosenInTheMenuIsReactive() {
        show {
            for (mode in listOf(
                WriteAutomatically.WHEN_I_SKIP,
                WriteAutomatically.NEVER,
                WriteAutomatically.ON_EVERY_TRACK_CHANGE,
            )) {
                choose(mode)
                assertEquals(mode, settings.value?.writeAutomatically)
            }
        }
    }

    @Test
    fun reactiveChangedInTheSettingsShowsInTheMenu() =
        show {
            arrow.assertContentDescriptionEquals("Writing mode: On track change")
            settings.value = settings.value?.copy(writeAutomatically = WriteAutomatically.NEVER)
            compose.waitForIdle()
            arrow.assertContentDescriptionEquals("Writing mode: Now")
            arrow.performClick()
            compose.waitForIdle()
            item(WriteAutomatically.NEVER).assertIsSelected()
            item(WriteAutomatically.ON_EVERY_TRACK_CHANGE).assertIsNotSelected()
            item(WriteAutomatically.WHEN_I_SKIP).assertIsNotSelected()
        }

    @Test
    fun anAutomaticModeChosenSaysEachNewTracksInsightIsAPaidCallAndNowSaysNothing() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show {
            for (mode in listOf(WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.WHEN_I_SKIP)) {
                ShadowToast.reset()
                choose(mode)
                assertEquals("Each new track’s insight is a paid call.", ShadowToast.getTextOfLatestToast())
            }
            ShadowToast.reset()
            choose(WriteAutomatically.NEVER)
            assertNull(ShadowToast.getLatestToast())
        }
    }

    @Test
    fun aScreenReaderReadsTheActionTheModeAsADropDownListAndTheModesAsOneChoice() =
        show {
            compose
                .onNodeWithTag(NO_ACTION_TAG)
                .reads(Role.Button)
                .assertTextEquals("Write on change")
                .assertHeightIsAtLeast(48.dp)
            arrow
                .assertContentDescriptionEquals("Writing mode: On track change")
                .reads(Role.DropdownList)
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            arrow.performClick()
            compose.waitForIdle()
            for (mode in WriteAutomatically.entries) {
                item(mode).reads(Role.RadioButton).assertHasClickAction().assertHeightIsAtLeast(48.dp)
            }
            item(WriteAutomatically.ON_EVERY_TRACK_CHANGE).assertIsSelected()
            item(WriteAutomatically.NEVER).assertIsNotSelected()
            // The modes sit in one group, so a screen reader reads them as one choice among three.
            val group =
                item(WriteAutomatically.NEVER)
                    .fetchSemanticsNode()
                    .parent
                    ?.config
                    ?.getOrNull(SemanticsProperties.SelectableGroup)
            assertTrue(group != null)
        }

    @Test
    fun theMenuNamesEachModeNowFirstAndEndsWithTheWayToTheirDetails() =
        show {
            arrow.performClick()
            compose.waitForIdle()
            val tops =
                (WRITING_ORDER.map(::writingModeTag) + WRITE_MODE_DETAILS_TAG).map {
                    compose
                        .onNodeWithTag(it)
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                }
            assertEquals(tops.sorted(), tops)
            // The names alone: what each does is said in the settings.
            item(WriteAutomatically.NEVER).assertTextEquals("Now")
            item(WriteAutomatically.ON_EVERY_TRACK_CHANGE).assertTextEquals("On track change")
            item(WriteAutomatically.WHEN_I_SKIP).assertTextEquals("On track skip")
            compose
                .onNodeWithTag(
                    WRITE_MODE_DETAILS_TAG,
                ).assertTextEquals("See Settings for details")
                .assertHasClickAction()
        }

    @Test
    fun seeSettingsForDetailsOpensTheSettingsAtReactive() =
        show {
            arrow.performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(WRITE_MODE_DETAILS_TAG).performClick()
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget(SettingsSection.INSIGHTS, "reactive")), openedAt)
            // The menu closed, the mode as it was.
            assertEquals(0, count(WRITE_MODE_DETAILS_TAG))
            assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, settings.value?.writeAutomatically)
        }

    @Test
    fun whileSaverHasPausedAutomaticWritingItSaysSo() {
        view.value = view.value.copy(paused = true)
        show {
            arrow.assertContentDescriptionEquals("Writing mode: On track change, paused")
            assertEquals(1, count(WRITE_MODE_PAUSED_TAG))
        }
    }

    @Test
    fun thePauseIsNotShownWhileNotPausedOrUnderNow() {
        show { assertEquals(0, count(WRITE_MODE_PAUSED_TAG)) }
        view.value = view.value.copy(paused = true)
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show {
            arrow.assertContentDescriptionEquals("Writing mode: Now")
            assertEquals(0, count(WRITE_MODE_PAUSED_TAG))
        }
    }

    @Test
    fun theModeCannotBeChangedWhileShowInsightsIsOff() {
        settings.value = Settings(writeInsights = false)
        show {
            arrow.assertIsNotEnabled()
            arrow.performClick()
            compose.waitForIdle()
            assertEquals(0, count(writingModeTag(WriteAutomatically.NEVER)))
            compose.onNodeWithTag(NO_ACTION_TAG).assertIsNotEnabled()
        }
        settings.value = Settings()
        show { arrow.assertIsEnabled() }
    }

    @Test
    fun beforeTheSettingsFirstReadTheArrowCannotBeUsed() {
        settings.value = null
        view.value = PipelineView(track(insight = InsightState.Idle(canWrite = true)), aiReady = true)
        show {
            arrow.assertIsNotEnabled().assertContentDescriptionEquals("Writing mode")
            compose.onNodeWithTag(WRITE_TAG).assertIsEnabled()
        }
    }

    private companion object {
        val WRITING_ORDER =
            listOf(WriteAutomatically.NEVER, WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.WHEN_I_SKIP)
    }
}
