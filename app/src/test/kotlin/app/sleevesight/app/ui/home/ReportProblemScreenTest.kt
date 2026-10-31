package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.report.Handed
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
import app.sleevesight.app.ui.report.ReportDialog
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

    // A mail app that takes the report as [handed] says: whole, its body in the extras only, or none.
    private class FakeMail(
        var handed: Handed = Handed.WHOLE,
        override val confirmsCopy: Boolean = true,
    ) : MailHandoff {
        val sent = mutableListOf<Mail>()
        val copied = mutableListOf<Mail>()

        override fun send(mail: Mail): Handed {
            if (handed != Handed.NO_MAIL_APP) sent += mail
            return handed
        }

        override fun copy(mail: Mail) {
            copied += mail
        }
    }

    private val mail = FakeMail()

    private val reply = "It topped the charts [W1]."

    private fun state(view: PipelineView) =
        NowPlayingState(view, view.track?.nowPlaying ?: sundown, PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING))

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for a recreated activity (as in
    // `SourcesFoldScreenTest`).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private fun show(
        initial: NowPlayingState,
        block: (update: (NowPlayingState) -> Unit) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize, mail = mail))
                            }
                        }
                    }
                }
            }
            block {
                current.value = it
                compose.waitForIdle()
            }
        }
    }

    private fun dialogs() = compose.onAllNodesWithTag(REPORT_DIALOG_TAG).fetchSemanticsNodes().size

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
            assertEquals(0, dialogs())
        }

    @Test
    fun withNoMailAppTheDialogSaysSoAndOffersToCopyTheReport() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            mail.handed = Handed.NO_MAIL_APP
            openReport()
            compose.onNodeWithText(text(R.string.report_reason_offensive)).performClick()
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()

            compose.onNodeWithText(text(R.string.report_no_mail_app, Mail.SUPPORT)).assertExists()
            compose.onNodeWithTag(REPORT_COPY_TAG).performClick()

            assertTrue("Reason: Offensive or inappropriate\n" in mail.copied.single().body)
            // The system confirms the copy itself, as Android 13 and later do: only where to paste it.
            compose.onNodeWithText(text(R.string.report_paste, Mail.SUPPORT)).assertExists()
            compose.onNodeWithTag(REPORT_DIALOG_TAG).assertExists()
        }

    @Test
    fun aReportTooLongForTheAddressIsCopiedTooAndTheDialogStaysOpenToSaySo() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            mail.handed = Handed.BODY_IN_EXTRAS
            openReport()
            compose.onNodeWithText(text(R.string.report_reason_wrong)).performClick()
            compose.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("It charted in 1974.")
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()

            // Handed to the mail app, which may open it empty: copied too, and the dialog says to paste it.
            assertEquals(mail.sent.single(), mail.copied.single())
            compose.onNodeWithText(text(R.string.report_long, Mail.SUPPORT)).assertExists()
            assertEquals(1, dialogs())
        }

    @Test
    fun copyReportIsOfferedWhenAMailAppIsThereToo() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            openReport()
            compose.onNodeWithTag(REPORT_COPY_TAG).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.report_reason_wrong)).performClick()
            compose.onNodeWithTag(REPORT_COPY_TAG).assertIsEnabled().performClick()

            assertTrue("Reason: Wrong\n" in mail.copied.single().body)
            assertTrue(mail.sent.isEmpty())
        }

    @Test
    fun belowAndroid13TheDialogSaysTheReportWasCopied() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    ReportDialog("Report", Mail(Mail.SUPPORT, "S", "B"), FakeMail(confirmsCopy = false), {}) {}
                }
            }
            compose.onNodeWithTag(REPORT_COPY_TAG).performClick()

            compose.onNodeWithText(text(R.string.report_copied, Mail.SUPPORT)).assertExists()
        }
    }

    @Test
    fun theDialogStaysWithWhatWasTypedWhenThePlayerStopsReporting() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) { update ->
            openReport()
            compose.onNodeWithText(text(R.string.report_reason_wrong)).performClick()
            compose.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("It charted in 1974.")

            // The player's session ended: the screen shows nothing playing.
            update(NowPlayingState(PipelineView(), playing = null, settings = Settings()))

            compose.waitUntil(
                5_000,
            ) { compose.onAllNodesWithTag(NOTHING_PLAYING_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()
            assertTrue("Note: It charted in 1974.\n" in mail.sent.single().body)
        }

    @Test
    fun reopenedAfterCancelTheReportStartsAfresh() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) {
            openReport()
            compose.onNodeWithText(text(R.string.report_reason_wrong)).performClick()
            compose.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("It charted in 1974.")
            compose.onNodeWithText(text(R.string.report_cancel)).performClick()
            assertEquals(0, dialogs())

            openReport()

            compose.onNodeWithTag(REPORT_SEND_TAG).assertIsNotEnabled()
            compose.onNode(hasText("It charted in 1974.", substring = true)).assertDoesNotExist()
        }

    @Test
    fun afterTheScreenIsRestoredAReportOpenedOnAnotherTrackStartsAfresh() =
        show(state(PipelineView(track(insight = written(reply)), aiReady = true))) { update ->
            openReport()
            compose.onNodeWithText(text(R.string.report_reason_wrong)).performClick()
            compose.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("It charted in 1974.")

            restore()
            // Closed with the activity, as it holds the track it is of, which is not saved.
            assertEquals(0, dialogs())
            update(state(PipelineView(track(playing = carefree, insight = written(reply)), aiReady = true)))
            openReport()

            // Nothing of the report on the other track: no reason chosen, no note.
            compose.onNodeWithTag(REPORT_SEND_TAG).assertIsNotEnabled()
            compose.onNode(hasText("It charted in 1974.", substring = true)).assertDoesNotExist()
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
