package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The insight card while the switched-on AI service's model is found unavailable (#540): it says
 * so, and asks for another model or service, with a link to the settings, and offers no Write; in
 * place of why nothing is written, or with an insight, last in its footer (#911), in place of why a
 * rewrite the service refused the model for failed (#969).
 */
@RunWith(RobolectricTestRunner::class)
class ModelUnavailableScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private var settingsOpened = 0
    private val settingsOpenedAt = mutableListOf<SettingsTarget>()

    private val haiku = UnavailableModel(ProviderId.ANTHROPIC, "claude-haiku-5-5")

    private fun show(
        insight: InsightState,
        unavailable: UnavailableModel?,
        settings: Settings = Settings(aiProvider = ProviderId.ANTHROPIC),
        rewriteFailed: String? = null,
        block: () -> Unit,
    ) {
        // A rewrite that failed shows the card paid for in its place, as the pipeline gives it.
        val view =
            PipelineView(
                track(insight = insight),
                aiReady = false,
                retry = true,
                cardShown = rewriteFailed != null,
                rewriteFailed = rewriteFailed,
            )
        val state =
            NowPlayingState(
                view,
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
                unavailable = unavailable,
            )
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                openSettings = { settingsOpened++ },
                openSettingsAt = { settingsOpenedAt += it },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            block()
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private val said =
        "The currently selected Anthropic model Claude Haiku 5.5 is not available. " +
            "Please select another model or provider in AI services."

    @Test
    fun anUnavailableModelIsSaidInPlaceOfWhyNothingIsWrittenWithALinkToItsRowInTheSettings() =
        show(InsightState.Idle(canWrite = false), haiku) {
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performScrollTo().assertTextEquals(said)
            val place = app.getString(R.string.home_model_unavailable_place)
            assertEquals(said, app.getString(R.string.home_model_unavailable, "Anthropic", "Claude Haiku 5.5", place))
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performFirstLinkClick()
            compose.waitForIdle()
            // The settings open at the service's model row, under *AI services* (#581).
            assertEquals(listOf(SettingsTarget(SettingsSection.AI_SERVICES, "model_ANTHROPIC")), settingsOpenedAt)
            assertEquals(0, settingsOpened)
        }

    // The words of a model the service refused (`failureText`), and the start of the line that gives them.
    private val refusals =
        listOf(R.string.home_failure_model_not_found, R.string.home_failure_model_refused).map(app::getString)
    private val rewritingFailed = app.getString(R.string.home_rewrite_failed, "").substringBefore(':')

    private fun assertNoRefusalSaid() {
        for (words in refusals + rewritingFailed) {
            compose.onAllNodesWithText(words, substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun theCallItFailedSaysSoTooWithNoWriteToTryAgain() =
        show(InsightState.Failed("model_not_found"), haiku) {
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performScrollTo().assertTextEquals(said)
            assertEquals(0, count(WRITE_TAG))
            // Said as the model unavailable, not as the call's reason (#969).
            assertNoRefusalSaid()
        }

    @Test
    fun aRewriteTheServiceRefusedTheModelForIsSaidAsTheModelUnavailableLastInTheFooter() {
        // The insight kept, shown again after its rewrite was refused, as the model not known or not
        // allowed: the footer's first line says it is kept, its last that the model is unavailable,
        // and nothing gives the refusal's reason (#969).
        for (reason in listOf("model_not_found", "model_refused")) {
            show(written("It is a song [MB1].", cached = true), haiku, rewriteFailed = reason) {
                val previously =
                    compose
                        .onNodeWithText(app.getString(R.string.home_written_before))
                        .performScrollTo()
                        .assertTextEquals(app.getString(R.string.home_written_before))
                val report = compose.onNodeWithTag(REPORT_TAG)
                val line = compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).assertTextEquals(said)
                val write = compose.onNodeWithTag(NO_ACTION_TAG).assertIsNotEnabled()
                val tops = listOf(previously, report, line, write).map { it.fetchSemanticsNode().positionInRoot.y }
                assertEquals("top to bottom, in this order", tops.sorted(), tops)
                assertNoRefusalSaid()
            }
        }
    }

    @Test
    fun aRewriteThatFailedOtherwiseStillSaysWhyAndWithoutTheNoteSoDoesARefusal() {
        val failed = { why: Int -> app.getString(R.string.home_rewrite_failed, app.getString(why)) }
        val previously = app.getString(R.string.home_written_before)
        // Offline, with the model found unavailable: not what the note says, so said as before.
        show(written("It is a song [MB1].", cached = true), haiku, rewriteFailed = "offline") {
            compose.onNodeWithText("$previously ${failed(R.string.home_failure_offline)}").performScrollTo()
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).assertTextEquals(said)
        }
        // A refusal with the model not (yet) found unavailable: no note, so the reason is said, as before.
        show(written("It is a song [MB1].", cached = true), null, rewriteFailed = "model_not_found") {
            compose.onNodeWithText("$previously ${failed(R.string.home_failure_model_not_found)}").performScrollTo()
            assertEquals(0, count(MODEL_UNAVAILABLE_TAG))
        }
    }

    @Test
    fun withAnInsightItIsSaidLastInTheFooterAboveTheDisabledWrite() =
        // Kept from before, with a sentence the sources do not back: every line of the footer shows.
        show(written("It is a song [MB1]. It was a hit.", cached = true), haiku) {
            val previously = compose.onNodeWithText(app.getString(R.string.home_written_before)).performScrollTo()
            val note = compose.onNodeWithText(app.getString(R.string.home_note_unbacked))
            val report = compose.onNodeWithTag(REPORT_TAG)
            val line = compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).assertTextEquals(said)
            val write = compose.onNodeWithTag(NO_ACTION_TAG)
            val tops = listOf(previously, note, report, line, write).map { it.fetchSemanticsNode().positionInRoot.y }
            assertEquals("top to bottom, in this order", tops.sorted(), tops)
            assertEquals(tops.size, tops.toSet().size)
            // Write insight, disabled: a Rewrite would fail too, and nothing offers it.
            write.assertIsNotEnabled().assertTextContains(app.getString(R.string.home_write))
            assertEquals(0, count(WRITE_TAG))
            compose.onAllNodesWithText(app.getString(R.string.home_rewrite), substring = true).assertCountEquals(0)
            // Its link opens the model's row in the settings, as without an insight.
            line.performFirstLinkClick()
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget(SettingsSection.AI_SERVICES, "model_ANTHROPIC")), settingsOpenedAt)
        }

    @Test
    fun aWrittenInsightStaysAndNothingIsSaidWithoutAnUnavailableModel() {
        show(written("It is a song [MB1]."), null) { assertEquals(0, count(MODEL_UNAVAILABLE_TAG)) }
        show(InsightState.Idle(canWrite = false), null) { assertEquals(0, count(MODEL_UNAVAILABLE_TAG)) }
        // Insights off: that is why nothing is written.
        show(InsightState.Idle(canWrite = false), haiku, Settings(writeInsights = false)) {
            assertEquals(0, count(MODEL_UNAVAILABLE_TAG))
        }
    }
}
