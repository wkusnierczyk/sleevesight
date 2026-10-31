package app.sleevesight.app.ui.report

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsLog
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.report.AppBuild
import app.sleevesight.app.report.Handed
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.TesterPart
import app.sleevesight.app.report.TesterReport
import app.sleevesight.app.report.TesterReports
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.CacheStats
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangePage
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Home's report button for testers (#452): shown only with developer mode on and *Report button*
 * not at *Hide*; its review, opened from its menu's *Report* (#518), with a switch per part, the
 * history off until turned on and read only then, each part editable, the note; and the mail handed
 * over as the tester left it.
 */
@RunWith(RobolectricTestRunner::class)
class TesterReportScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private class FakeMail : MailHandoff {
        val sent = mutableListOf<Mail>()

        override fun send(mail: Mail): Handed {
            sent += mail
            return Handed.WHOLE
        }

        override fun copy(mail: Mail) = Unit

        override val confirmsCopy: Boolean = true
    }

    private val mail = FakeMail()
    private val at = Instant.parse("2026-10-25T12:00:00Z")
    private val settings = MutableStateFlow(Settings(developerMode = true, reportButton = ReportButton.SHOW))
    private var historyReads = 0
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun close() = scope.coroutineContext[Job]!!.cancel()

    private val played =
        TrackChangeRecord(
            player = Settings.SPOTIFY,
            changedAt = at - 1.minutes,
            from = TrackRef("Song A", null),
            to = TrackRef("Song B", null),
            timeLeft = null,
            states = emptyList(),
            sinceLastAction = null,
            learnedCrossfade = null,
        )

    private val none = TableStats(0, 0)

    private val diagnostics =
        Diagnostics(
            log = DiagnosticsLog(),
            readChanges = { _, _ ->
                historyReads++
                TrackChangePage(listOf(played), next = null)
            },
            readCacheStats = { CacheStats(none, none, none, none) },
            io = EmptyCoroutineContext,
            scope = scope,
            version = "1.2.3",
        )

    private val reports =
        TesterReports(
            build = AppBuild("1.2.3", 7, testBuild = true),
            view = MutableStateFlow(PipelineView(track = track(insight = written("It topped the charts [W1].")))),
            playing = MutableStateFlow(sundown),
            playback = MutableStateFlow(PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)),
            diagnostics = diagnostics,
            clock =
                object : Clock {
                    override fun now(): Instant = at
                },
            zone = { ZoneOffset.UTC },
            elapsedRealtime = { 0L },
        )

    private fun show(block: () -> Unit) =
        showContent(block) {
            WithTesterReport(
                TesterReportFeed(settings, hide = { CompletableDeferred(true) }, reports, mail),
            ) { report ->
                HomeScreen(true, onOpenFirstRun = {}, onOpenSettings = {}, report = report) {
                    Text("What is playing")
                }
            }
        }

    // The review alone, of the moment it is opened, with the history read by [readHistory].
    private fun showReview(
        readHistory: suspend () -> String,
        block: () -> Unit,
    ) {
        val snapshot = reports.snapshot(settings.value)
        showContent(block) { TesterReportDialog(snapshot, readHistory, mail) {} }
    }

    private fun showContent(
        block: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent(content = content) }
            compose.waitForIdle()
            block()
        }
    }

    private fun send() = compose.onNodeWithTag(REPORT_SEND_TAG)

    private fun copy() = compose.onNodeWithTag(REPORT_COPY_TAG)

    private fun buttons() = compose.onAllNodesWithTag(TESTER_REPORT_TAG).fetchSemanticsNodes().size

    // The mail as the dialog shows it, which is what is sent.
    private fun preview(): String =
        compose
            .onAllNodes(hasAnyAncestor(hasTestTag(REPORT_PREVIEW_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .joinToString("") { it.text }

    private fun part(part: TesterPart) = compose.onNodeWithTag(testerPartTag(part)).performScrollTo()

    // The review, from the button's menu (#518).
    private fun open() {
        compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
        compose.onNodeWithTag(TESTER_REPORT_MENU_REPORT_TAG).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theButtonShowsOnlyWithDeveloperModeOnAndReportButtonNotAtHide() =
        show {
            for (developer in listOf(true, false)) {
                for (button in ReportButton.entries) {
                    settings.value = Settings(developerMode = developer, reportButton = button)
                    compose.waitForIdle()
                    val shown = developer && button != ReportButton.HIDE
                    assertEquals("developer $developer, button $button", if (shown) 1 else 0, buttons())
                }
            }
        }

    @Test
    fun aScreenReaderNamesTheButtonForWhatItDoes() =
        show {
            compose
                .onNodeWithTag(TESTER_REPORT_TAG)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(app.getString(R.string.tester_report_button)),
                    ),
                )
        }

    @Test
    fun theReviewHoldsEveryPartButTheHistoryWhichIsReadOnlyOnceTurnedOn() =
        show {
            open()
            compose.onNodeWithTag(REPORT_DIALOG_TAG).assertExists()
            for (kept in TesterPart.entries - TesterPart.HISTORY) {
                part(kept).assertIsOn()
                assertTrue(kept.label, preview().contains("## ${kept.label}\n"))
            }
            part(TesterPart.HISTORY).assertIsOff()
            assertFalse(preview().contains("## ${TesterPart.HISTORY.label}"))
            assertEquals(0, historyReads)

            part(TesterPart.HISTORY).performClick()
            compose.waitUntil(WAIT_MS) { preview().contains("\"Song B\"") }
            assertTrue(preview().contains("## ${TesterPart.HISTORY.label}\n"))
            assertEquals(1, historyReads)

            // Turned off again, it leaves the report; on again, it is not read again.
            part(TesterPart.HISTORY).performClick()
            compose.waitForIdle()
            assertFalse(preview().contains("\"Song B\""))
            part(TesterPart.HISTORY).performClick()
            compose.waitForIdle()
            assertTrue(preview().contains("\"Song B\""))
            assertEquals(1, historyReads)
        }

    @Test
    fun theMailSentIsTheReportAsTheTesterLeftIt() =
        show {
            open()
            part(TesterPart.SETTINGS).performClick()
            compose.onNodeWithTag(testerEditTag(TesterPart.APP)).performScrollTo().performClick()
            compose.onNodeWithTag(testerTextTag(TesterPart.APP)).performScrollTo().performTextReplacement("Edited.")
            compose.onNodeWithTag(TESTER_NOTE_TAG).performScrollTo().performTextInput("The insight came late.")
            compose.waitForIdle()
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()

            val body = mail.sent.single().body
            assertTrue(body, body.contains("## Note\nThe insight came late.\n"))
            assertTrue(body, body.contains("## App\nEdited.\n"))
            assertFalse(body, body.contains("## Settings"))
            assertTrue(body, body.contains("## Now playing\n"))
            assertTrue(body, body.contains("It topped the charts [W1]."))
            assertFalse(body, body.contains("## ${TesterPart.HISTORY.label}"))
            // A mail app took it: the review closes, and the tester sends it from there.
            assertEquals(0, compose.onAllNodesWithTag(REPORT_DIALOG_TAG).fetchSemanticsNodes().size)
        }

    @Test
    fun whileTheHistoryIsReadNothingCanBeSentOrCopied() {
        val read = CompletableDeferred<Unit>()
        showReview({
            read.await()
            "\"Song B\" was played."
        }) {
            send().assertIsEnabled()
            part(TesterPart.HISTORY).performClick()
            compose.waitForIdle()
            send().assertIsNotEnabled()
            copy().assertIsNotEnabled()
            assertEquals(app.getString(R.string.tester_report_waits_for_history), preview())

            // Turned off while it is read, it no longer holds the report back; on again, it does.
            part(TesterPart.HISTORY).performClick()
            compose.waitForIdle()
            send().assertIsEnabled()
            part(TesterPart.HISTORY).performClick()
            compose.waitForIdle()
            send().assertIsNotEnabled()

            read.complete(Unit)
            compose.waitUntil(WAIT_MS) { preview().contains("\"Song B\" was played.") }
            send().assertIsEnabled()
            copy().assertIsEnabled()
        }
    }

    @Test
    fun aReportTooLongToHandOverWaitsUntilItIsShortened() =
        showReview({ "" }) {
            compose.onNodeWithTag(testerEditTag(TesterPart.APP)).performScrollTo().performClick()
            val app = compose.onNodeWithTag(testerTextTag(TesterPart.APP)).performScrollTo()
            app.performTextReplacement("x".repeat(TesterReport.MAX_CHARS))
            compose.waitForIdle()
            send().assertIsNotEnabled()
            copy().assertIsNotEnabled()
            assertTrue(preview(), preview().startsWith("The report is too long to send or copy"))

            app.performTextReplacement("Shorter.")
            compose.waitForIdle()
            send().assertIsEnabled().performClick()
            assertTrue(
                mail.sent
                    .single()
                    .body
                    .contains("## App\nShorter.\n"),
            )
        }

    @Test
    fun aPartTooLongToEditInPlaceHasNoEdit() {
        // Some 150 events: the diagnostics part runs past what *Edit* opens.
        repeat(150) { diagnostics.log.record(PipelineEvent.Superseded("a track with a long title|its artist $it")) }
        showReview({ "" }) {
            compose.onNodeWithTag(testerTooLongTag(TesterPart.DIAGNOSTICS)).performScrollTo().assertExists()
            compose.onAllNodesWithTag(testerEditTag(TesterPart.DIAGNOSTICS)).assertCountEquals(0)
            compose.onNodeWithTag(testerEditTag(TesterPart.APP)).performScrollTo().assertExists()
            // Left out with its switch all the same.
            part(TesterPart.DIAGNOSTICS).performClick()
            compose.waitForIdle()
            assertFalse(preview().contains("## Diagnostics"))
        }
    }

    @Test
    fun theReportIsOfTheMomentOfTheTap() =
        show {
            open()
            settings.value = Settings(developerMode = true, reportButton = ReportButton.SHOW, writeInsights = false)
            compose.waitForIdle()
            // The settings as they were at the tap.
            assertTrue(preview().contains("writeInsights: on"))
            compose.onNodeWithTag(REPORT_SEND_TAG).performClick()
            assertTrue(
                mail.sent
                    .single()
                    .body
                    .contains("writeInsights: on"),
            )
        }
}
