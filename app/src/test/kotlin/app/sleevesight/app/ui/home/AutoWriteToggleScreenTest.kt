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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * The insight card's toggle for automatic writing (#553): one setting with *Reactive*, each
 * showing the other's change; off is *Manual*, on again the mode used before; turning it on says
 * each new track's insight is a paid call; *Saver*'s pause shows while it stays on; a switch to a
 * screen reader, named with its state, a 48 dp target, and fixed while *Show insights* is off.
 */
@RunWith(RobolectricTestRunner::class)
class AutoWriteToggleScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = MutableStateFlow(Settings())
    private val view =
        MutableStateFlow(PipelineView(track(insight = written("It is a song [MB1].")), aiReady = true))

    private fun show(block: () -> Unit) {
        // The toggle saves as the app's does: through the settings, which the card then reads.
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                autoWrite = { on -> settings.value = settings.value.withAutomaticWriting(on) },
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
            compose.onNodeWithTag(AUTO_WRITE_TAG).performScrollTo()
            block()
        }
    }

    private val toggle get() = compose.onNodeWithTag(AUTO_WRITE_TAG)

    private fun says(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    private fun pausedShown() =
        compose.onAllNodesWithTag(AUTO_WRITE_PAUSED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun turningItOffSetsReactiveToManualAndOnAgainRestoresTheModeUsedBefore() {
        settings.value = Settings().withWriteAutomatically(WriteAutomatically.WHEN_I_SKIP)
        show {
            toggle.assertIsOn()
            toggle.performClick()
            compose.waitForIdle()
            assertEquals(WriteAutomatically.NEVER, settings.value.writeAutomatically)
            toggle.assertIsOff()
            toggle.performClick()
            compose.waitForIdle()
            assertEquals(WriteAutomatically.WHEN_I_SKIP, settings.value.writeAutomatically)
            toggle.assertIsOn()
        }
    }

    @Test
    fun turningItOnWithNoModeEverUsedIsOnTrackChange() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show {
            toggle.performClick()
            compose.waitForIdle()
            assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, settings.value.writeAutomatically)
        }
    }

    @Test
    fun reactiveChangedInTheSettingsShowsInTheToggle() =
        show {
            toggle.assertIsOn()
            settings.value = settings.value.withWriteAutomatically(WriteAutomatically.NEVER)
            compose.waitForIdle()
            toggle.assertIsOff()
            settings.value = settings.value.withWriteAutomatically(WriteAutomatically.WHEN_I_SKIP)
            compose.waitForIdle()
            toggle.assertIsOn()
        }

    @Test
    fun turningItOnSaysEachNewTracksInsightIsAPaidCallAndTurningItOffSaysNothing() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show {
            toggle.performClick()
            compose.waitForIdle()
            assertEquals("Each new track’s insight is a paid call.", ShadowToast.getTextOfLatestToast())
            ShadowToast.reset()
            toggle.performClick()
            compose.waitForIdle()
            assertNull(ShadowToast.getLatestToast())
        }
    }

    @Test
    fun aScreenReaderReadsASwitchNamedWithItsState() {
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show {
            toggle
                .assertContentDescriptionEquals("Write insights automatically")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .assert(says("off"))
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            toggle.performClick()
            compose.waitForIdle()
            toggle.assert(says("on"))
        }
    }

    @Test
    fun aLongPressShowsItsNameAndState() =
        show {
            toggle.performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithText("Write insights automatically: on").assertExists()
        }

    @Test
    fun whileSaverHasPausedAutomaticWritingItStaysOnAndSaysSo() {
        view.value = view.value.copy(paused = true)
        show {
            toggle.assertIsOn().assert(says("on, paused"))
            assertEquals(1, pausedShown())
        }
    }

    @Test
    fun thePauseIsNotShownWhileNotPausedOrWhileOff() {
        show { assertEquals(0, pausedShown()) }
        view.value = view.value.copy(paused = true)
        settings.value = Settings(writeAutomatically = WriteAutomatically.NEVER)
        show {
            toggle.assertIsOff().assert(says("off"))
            assertEquals(0, pausedShown())
        }
    }

    @Test
    fun itCannotBeChangedWhileShowInsightsIsOff() {
        settings.value = Settings(writeInsights = false)
        show {
            toggle.assertIsNotEnabled().assertIsOn()
            toggle.performClick()
            compose.waitForIdle()
            assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, settings.value.writeAutomatically)
        }
        settings.value = Settings()
        show { toggle.assertIsEnabled() }
    }
}
