package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.report.REPORT_COPY_TAG
import app.sleevesight.app.ui.report.REPORT_DIALOG_TAG
import app.sleevesight.app.ui.report.REPORT_NOTE_TAG
import app.sleevesight.app.ui.report.REPORT_SEND_TAG
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** *Report a problem* on the insight card (#390): offered on a written insight, shown, and handed over. */
@RunWith(RobolectricTestRunner::class)
class ReportProblemScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    // A mail app that takes the report, or none ([takes] false).
    private class FakeMail(
        var takes: Boolean = true,
    ) : MailHandoff {
        val sent = mutableListOf<Mail>()
        val copied = mutableListOf<Mail>()

        override fun send(mail: Mail): Boolean {
            if (takes) sent += mail
            return takes
        }

        override fun copy(mail: Mail) {
            copied += mail
        }
    }

    private val mail = FakeMail()

    private val reply = "It topped the charts [W1]."

    private fun state(view: PipelineView) =
        NowPlayingState(view, view.track?.nowPlaying ?: sundown, PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING))

    private fun show(
        initial: NowPlayingState,
        block: (update: (NowPlayingState) -> Unit) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize, mail = mail))
                    }
                }
            }
            block { current.value = it }
        }
    }

    private fun openReport() = compose.onNodeWithTag(REPORT_TAG).performScrollTo().performClick()

    @Test
    fun aWrittenInsightOffersReportAProblemNamedForTheInsight() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            compose
                .onNodeWithTag(REPORT_TAG)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(text(R.string.report_action_description)),
                    ),
                )
        }

    @Test
    fun anInsightNotWrittenOffersNoReport() {
        val states =
            listOf(
                InsightState.Idle(canWrite = true),
                InsightState.Writing("It topped"),
                InsightState.NoEvidence,
                InsightState.Failed("rate_limited"),
                InsightState.Stopped,
            )
        show(state(PipelineView(track(insight = states.first()), aiReady = true))) { update ->
            for (insight in states) {
                update(state(PipelineView(track(insight = insight), aiReady = true)))
                compose.waitForIdle()
                assertEquals(insight.toString(), 0, compose.onAllNodesWithTag(REPORT_TAG).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun theReportIsShownAndSentOnlyOnceAReasonIsChosenAndItCarriesTheNote() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            openReport()
            compose.onNodeWithTag(REPORT_SEND_TAG).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.report_preview_waits)).assertExists()

            compose.onNodeWithText(text(R.string.report_reason_wrong)).performClick()
            compose.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("It charted in 1974.")
            // The preview is the mail as it will be sent.
            compose.onNode(hasText("Note: It charted in 1974.", substring = true)).assertExists()
            compose.onNodeWithTag(REPORT_SEND_TAG).assertIsEnabled().performClick()

            val sent = mail.sent.single()
            assertEquals(Mail.SUPPORT, sent.to)
            assertTrue("Reason: Wrong\nNote: It charted in 1974.\n" in sent.body)
            assertTrue(reply in sent.body)
            // A mail app took it: the dialog closes, and the user sends it from there.
            assertEquals(0, compose.onAllNodesWithTag(REPORT_DIALOG_TAG).fetchSemanticsNodes().size)
        }

    @Test
    fun withNoMailAppTheDialogSaysSoAndOffersToCopyTheReport() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            mail.takes = false
            openReport()
            compose.onNodeWithText(text(R.string.report_reason_offensive)).performClick()
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()

            compose.onNodeWithText(text(R.string.report_no_mail_app, Mail.SUPPORT)).assertExists()
            compose.onNodeWithTag(REPORT_COPY_TAG).performClick()

            assertTrue("Reason: Offensive or inappropriate\n" in mail.copied.single().body)
            compose.onNodeWithText(text(R.string.report_copied, Mail.SUPPORT)).assertExists()
            compose.onNodeWithTag(REPORT_DIALOG_TAG).assertExists()
        }

    @Test
    fun theReportStaysOfTheTrackItWasOpenedOnWhenTheNextOnePlays() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) { update ->
            openReport()
            update(state(PipelineView(track(playing = carefree, insight = written(reply)), aiReady = true)))
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.report_reason_other)).performClick()
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()

            assertTrue("Title: Sundown\n" in mail.sent.single().body)
        }
}
