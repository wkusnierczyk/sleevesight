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
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.CallId
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.RunId
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
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
 * The insight card's split button (#615, #625): the leading half is *Write insight* or *Rewrite
 * insight*, the same in every mode, or *Stop writing* while one is written, in each situation:
 * the app just started, a new track with nothing written, an insight shown, a track that came to
 * play while *Saver* had paused automatic writing, a card written with earlier settings (*Rewrite
 * insight*, said after *As written previously.* on the same line, #606, #671), a track that followed the last one
 * by itself, writing, and a failure; each with its line. The arrow's menu is *Reactive*, in order of
 * how much is written automatically, each showing the other's change, every choice told on (the
 * one chosen included), and it ends with the way to it in the settings; another automatic mode
 * chosen says each new track's insight is a paid call, *On demand* or the mode already chosen says
 * nothing; a screen reader reads the action, *Writing mode: <mode>* as a drop-down list, and the
 * modes as one choice among them; 48 dp targets; fixed while *Show insights* is off.
 */
@RunWith(RobolectricTestRunner::class)
class WriteSplitButtonScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = MutableStateFlow<Settings?>(Settings())
    private val unavailable = MutableStateFlow<UnavailableModel?>(null)
    private val view =
        MutableStateFlow(PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true))

    // Each mode chosen in the menu, as the app's chooser hears it (#625), and whether its save works.
    private val chosen = mutableListOf<WriteAutomatically>()
    private var saves = true

    private fun show(block: () -> Unit) {
        // The menu saves as the app's does: through the settings, which the card then reads.
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                chooseWritingMode = { mode ->
                    chosen += mode
                    // A save that fails leaves the setting as it was.
                    if (saves) settings.value = settings.value?.copy(writeAutomatically = mode)
                    CompletableDeferred(saves)
                },
                openSettingsAt = { openedAt += it },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val shown by settings.collectAsState()
                    val pipeline by view.collectAsState()
                    val missing by unavailable.collectAsState()
                    val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
                    val state = NowPlayingState(pipeline, sundown, playback, settings = shown, unavailable = missing)
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

    private fun absent(text: String) = compose.onNodeWithText(text, substring = true).assertDoesNotExist()

    // Write insight, enabled, its line [line], in every mode.
    private fun writeInsightInEveryMode(
        pipeline: PipelineView,
        line: String,
    ) {
        view.value = pipeline
        for (mode in WriteAutomatically.entries) {
            settings.value = Settings(writeAutomatically = mode)
            show {
                leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
                says(line)
                REMOVED.forEach(::absent)
            }
        }
    }

    @Test
    fun theAppJustStartedItIsWriteInsightInEveryMode() =
        // No first action yet since the app started: Write offered, nothing written automatically.
        writeInsightInEveryMode(
            PipelineView(offered, aiReady = true, waiting = true),
            "Tap Write insight to have it written from the sources below.",
        )

    @Test
    fun aNewTrackUnderOnDemandIsWriteInsight() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        view.value = PipelineView(offered, aiReady = true)
        show {
            leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
            says("Tap Write insight to have it written from the sources below.")
        }
    }

    @Test
    fun aNewTrackAutomaticWritingWillWriteIsWriteInsightDisabled() {
        view.value = PipelineView(notYet, aiReady = true)
        val lines =
            mapOf(
                WriteAutomatically.ON_EVERY_TRACK_CHANGE to "Writing automatically on every track change.",
                WriteAutomatically.WHEN_I_SKIP to "Writing automatically when you skip a track.",
            )
        for ((mode, line) in lines) {
            settings.value = Settings(writeAutomatically = mode)
            show {
                leading(NO_ACTION_TAG).assertTextEquals("Write insight").assertIsNotEnabled()
                assertEquals(0, count(WRITE_TAG))
                says(line)
            }
        }
    }

    @Test
    fun aTrackThatCameToPlayWhileSaverHadPausedAutomaticWritingIsWriteInsightAndSaysItResumes() {
        for (mode in listOf(WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.WHEN_I_SKIP)) {
            settings.value = Settings(writeAutomatically = mode)
            view.value = PipelineView(offered, aiReady = true, waiting = true, lapsed = true)
            show {
                leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
                says(
                    "Automatic writing was paused while Sleevesight was not on screen, and resumes now. This track " +
                        "wasn’t written: tap Write insight to have it written.",
                )
                // No *Paused* beside the button: opening Sleevesight has resumed it.
                absent("Paused")
                arrow.assertContentDescriptionEquals("Writing mode: ${NAMES.getValue(mode)}")
            }
        }
    }

    @Test
    fun aTrackThatFollowedTheLastOneByItselfIsWriteInsightAndSaysWhy() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)
        view.value = PipelineView(offered, aiReady = true, followed = true)
        show {
            leading(WRITE_TAG).assertTextEquals("Write insight").assertIsEnabled()
            says(
                "This track followed the last one by itself, so it wasn’t written automatically. Tap Write " +
                    "insight to have it written.",
            )
        }
    }

    @Test
    fun anInsightShownIsWriteInsightDisabledInEveryMode() {
        val kept = track(insight = written("It is a song [MB1].", cached = true))
        for (mode in WriteAutomatically.entries) {
            settings.value = Settings(writeAutomatically = mode)
            // Written now, or kept with nothing offered: Write insight, disabled, and no rewrite line.
            for (pipeline in listOf(
                PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true),
                PipelineView(kept, aiReady = true, cardShown = true),
            )) {
                view.value = pipeline
                show {
                    leading(NO_ACTION_TAG).assertTextEquals("Write insight").assertIsNotEnabled()
                    assertEquals(0, count(WRITE_TAG))
                    absent("Rewrite")
                    arrow.assertIsEnabled()
                }
            }
        }
    }

    @Test
    fun aCardWrittenWithEarlierSettingsIsRewriteInsightInEveryMode() {
        // Kept from before, its key changed by a settings change: the coordinator offers a rewrite.
        // Not from the cache, so *As written previously.* says so for the card paid for (#606, #671).
        val kept = track(insight = written("It is a song [MB1]."))
        for (mode in WriteAutomatically.entries) {
            settings.value = Settings(writeAutomatically = mode)
            for (waiting in listOf(false, true)) {
                view.value = PipelineView(kept, aiReady = true, rewrite = true, cardShown = true, waiting = waiting)
                show {
                    leading(WRITE_TAG).assertTextEquals("Rewrite insight").assertIsEnabled()
                    assertEquals(0, count(NO_ACTION_TAG))
                    says("As written previously. $REWRITE_OFFERED")
                    arrow.assertIsEnabled()
                }
            }
        }
    }

    @Test
    fun theRewritesWordsFollowAsWrittenPreviouslyOnItsLineAboveReportAProblem() {
        // One line (#671), for the rewrite offered and for one that failed or was stopped, and
        // *Report a problem* under it, at the start.
        val kept = track(insight = written("It is a song [MB1]."))
        val lines =
            listOf(
                PipelineView(kept, aiReady = true, rewrite = true, cardShown = true) to
                    "As written previously. $REWRITE_OFFERED",
                PipelineView(kept, aiReady = true, rewrite = true, cardShown = true, rewriteFailed = "offline") to
                    "As written previously. Rewriting failed: no connection. $REWRITE_OFFERED",
                PipelineView(kept, aiReady = true, cardShown = true, rewriteFailed = PipelineHost.STOPPED) to
                    "As written previously. Rewriting stopped.",
            )
        for ((pipeline, line) in lines) {
            view.value = pipeline
            show {
                val above = bounds(line)
                val report = compose.onNodeWithTag(REPORT_TAG).fetchSemanticsNode().boundsInRoot
                assertTrue("Report a problem under the line", report.top >= above.bottom - 1f)
            }
        }
    }

    private fun bounds(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    @Test
    fun rewriteIsNotOfferedWhileTheModelIsFoundUnavailable() {
        val kept = track(insight = written("It is a song [MB1].", cached = true))
        view.value = PipelineView(kept, aiReady = true, rewrite = true, cardShown = true)
        unavailable.value = UnavailableModel(ProviderId.ANTHROPIC, "claude-gone")
        show {
            leading(NO_ACTION_TAG).assertTextEquals("Write insight").assertIsNotEnabled()
            absent(REWRITE_OFFERED)
        }
    }

    @Test
    fun aCardKeptFromBeforeSaysSoEvenWhenNotFromTheCache() {
        // The card paid for, shown in place of the run's own insight: not served from the cache.
        view.value = PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true, cardShown = true)
        show { says("As written previously.") }
        view.value = PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true)
        show { absent("As written previously.") }
    }

    @Test
    fun aRewriteThatFailedOrStoppedShowsTheCardBeforeSaysSoAndOffersRewriteInsightAgain() {
        val kept = track(insight = written("It is a song [MB1]."))
        val said =
            mapOf(
                "offline" to "Rewriting failed: no connection.",
                PipelineHost.STOPPED to "Rewriting stopped.",
            )
        for ((why, line) in said) {
            view.value = PipelineView(kept, aiReady = true, rewrite = true, cardShown = true, rewriteFailed = why)
            show {
                compose.onNodeWithText("It is a song", substring = true).assertExists()
                says("As written previously. $line $REWRITE_OFFERED")
                leading(WRITE_TAG).assertTextEquals("Rewrite insight").assertIsEnabled()
            }
            // Until the coordinator offers it again: the line, and Write insight disabled.
            view.value = PipelineView(kept, aiReady = true, cardShown = true, rewriteFailed = why)
            show {
                says("As written previously. $line")
                leading(NO_ACTION_TAG).assertTextEquals("Write insight").assertIsNotEnabled()
            }
        }
    }

    @Test
    fun aFailureIsWriteInsightWithTheReasonInEveryMode() =
        writeInsightInEveryMode(
            PipelineView(track(insight = InsightState.Failed("offline")), aiReady = true, retry = true, waiting = true),
            "Writing failed: no connection. Tap Write insight to try again, as a new call.",
        )

    @Test
    fun aStoppedCallIsWriteInsightInEveryMode() =
        writeInsightInEveryMode(
            PipelineView(track(insight = InsightState.Stopped), aiReady = true, retry = true, waiting = true),
            "Tap Write insight to try again, as a new call.",
        )

    @Test
    fun aFailureNotOfferedAgainSaysWhyAndOffersNothing() {
        view.value = PipelineView(track(insight = InsightState.Failed("offline")), aiReady = true)
        show {
            says("Writing failed: no connection.")
            leading(NO_ACTION_TAG).assertTextEquals("Write insight").assertIsNotEnabled()
        }
    }

    @Test
    fun whileWritingItIsStopWritingWithItsCaveat() {
        view.value =
            PipelineView(track(insight = InsightState.Writing("It is")), aiReady = true, stopCall = CallId(RunId(1), 1))
        show {
            leading(STOP_TAG).assertTextEquals("Stop writing").assertIsEnabled()
            assertEquals(0, count(WRITE_TAG))
            // Under *Writing…*, which stays (#785).
            says(
                "Stop writing ends the call at once. If the request has reached your AI service, it still charges " +
                    "for what it has read and written so far.",
            )
            compose.onNodeWithText("Writing…").assertExists()
        }
    }

    @Test
    fun withNothingToWriteFromItIsDisabled() {
        view.value = PipelineView(track(insight = InsightState.NoEvidence), aiReady = true)
        show { leading(NO_ACTION_TAG).assertIsNotEnabled() }
    }

    @Test
    fun aModeChosenInTheMenuIsReactiveAndToldOnTheOneChosenIncluded() {
        val modes =
            listOf(
                WriteAutomatically.WHEN_I_SKIP,
                // Chosen again: an automatic mode chosen starts automatic writing (#625).
                WriteAutomatically.WHEN_I_SKIP,
                WriteAutomatically.NEVER,
                WriteAutomatically.ON_EVERY_TRACK_CHANGE,
            )
        show {
            for (mode in modes) {
                choose(mode)
                assertEquals(mode, settings.value?.writeAutomatically)
            }
        }
        assertEquals(modes, chosen)
    }

    @Test
    fun reactiveChangedInTheSettingsShowsInTheMenu() =
        show {
            arrow.assertContentDescriptionEquals("Writing mode: On track change (auto), and Look again")
            settings.value = settings.value?.copy(writeAutomatically = WriteAutomatically.NEVER)
            compose.waitForIdle()
            arrow.assertContentDescriptionEquals("Writing mode: On demand, and Look again")
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
    fun aModeWhoseSaveFailedIsAnnouncedAndTheMenuKeepsTheStoredMode() {
        saves = false
        show {
            ShadowToast.reset()
            choose(WriteAutomatically.NEVER)
            compose.waitForIdle()
            assertEquals(
                "The last change could not be saved: the phone’s storage failed. The setting keeps its saved " +
                    "value; try again.",
                ShadowToast.getTextOfLatestToast(),
            )
            arrow.assertContentDescriptionEquals("Writing mode: On track change (auto), and Look again")
        }
        assertEquals(listOf(WriteAutomatically.NEVER), chosen)
    }

    @Test
    fun theModeAlreadyChosenChosenAgainSaysNothing() {
        for (mode in listOf(WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.WHEN_I_SKIP)) {
            settings.value = Settings(writeAutomatically = mode)
            show {
                ShadowToast.reset()
                choose(mode)
                assertNull(ShadowToast.getLatestToast())
                assertEquals(mode, settings.value?.writeAutomatically)
            }
        }
    }

    @Test
    fun aScreenReaderReadsTheActionTheModeAsADropDownListAndTheModesAsOneChoice() =
        show {
            compose
                .onNodeWithTag(NO_ACTION_TAG)
                .reads(Role.Button)
                .assertTextEquals("Write insight")
                .assertTouchHeightIsEqualTo(48.dp)
            arrow
                .assertContentDescriptionEquals("Writing mode: On track change (auto), and Look again")
                .reads(Role.DropdownList)
                .assertHasClickAction()
                .assertTouchWidthIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
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
    fun theMenuNamesEachModeByHowMuchIsWrittenAutomaticallyAndEndsWithTheWayToTheirDetails() =
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
            // The names alone, as in *Reactive*: what each does is said in the settings.
            for ((mode, name) in NAMES) item(mode).assertTextEquals(name)
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
        // What follows *As written previously.* with *Rewrite insight* offered (#671).
        const val REWRITE_OFFERED = "Tap Rewrite insight to write it with the updated source or model settings."

        // In order of how much is written automatically (#625).
        val WRITING_ORDER =
            listOf(WriteAutomatically.NEVER, WriteAutomatically.WHEN_I_SKIP, WriteAutomatically.ON_EVERY_TRACK_CHANGE)

        val NAMES =
            mapOf(
                WriteAutomatically.NEVER to "On demand",
                WriteAutomatically.WHEN_I_SKIP to "On track skip (auto)",
                WriteAutomatically.ON_EVERY_TRACK_CHANGE to "On track change (auto)",
            )

        // What the button and the card no longer say (#625).
        val REMOVED =
            listOf(
                "Write on change",
                "Write on skip",
                "start writing automatically",
                "starts writing automatically",
                "resumes writing automatically",
            )
    }
}
