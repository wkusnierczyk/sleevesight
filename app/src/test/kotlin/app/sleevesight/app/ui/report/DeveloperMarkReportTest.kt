package app.sleevesight.app.ui.report

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsLog
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.report.AppBuild
import app.sleevesight.app.report.Handed
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.TesterReports
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.DEVELOPER_MARK_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.CacheStats
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The developer mark (#536) on the reports, in developer mode with marking on: the tester's report
 * button, its menu and the review's own inputs are marked; the production *Report a problem*
 * dialog, which every user has, is not.
 */
@RunWith(RobolectricTestRunner::class)
class DeveloperMarkReportTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private object NoMail : MailHandoff {
        override fun send(mail: Mail): Handed = Handed.WHOLE

        override fun copy(mail: Mail) = Unit

        override val confirmsCopy: Boolean = true
    }

    private val at = Instant.parse("2026-10-25T12:00:00Z")
    private val settings = Settings(developerMode = true, reportButton = ReportButton.SHOW)
    private val scope = CoroutineScope(SupervisorJob())
    private val insight = written("It topped the charts [W1].")

    @After
    fun close() = scope.coroutineContext[Job]!!.cancel()

    private val reports by lazy {
        val none = TableStats(0, 0)
        TesterReports(
            build = AppBuild("1.2.3", 7, testBuild = true),
            view = MutableStateFlow(PipelineView(track = track(insight = insight))),
            playing = MutableStateFlow(sundown),
            playback = MutableStateFlow(PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)),
            diagnostics =
                Diagnostics(
                    log = DiagnosticsLog(),
                    readChanges = { _, _ -> TrackChangePage(emptyList(), next = null) },
                    readCacheStats = { CacheStats(none, none, none, none) },
                    io = EmptyCoroutineContext,
                    scope = scope,
                    version = "1.2.3",
                ),
            clock =
                object : Clock {
                    override fun now(): Instant = at
                },
            zone = { ZoneOffset.UTC },
            elapsedRealtime = { 0L },
        )
    }

    private fun show(
        block: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { SleevesightTheme(developerMark = true) { content() } } }
            compose.waitForIdle()
            block()
        }
    }

    private fun marks() =
        compose.onAllNodesWithTag(DEVELOPER_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun markedTag(tag: String) =
        compose
            .onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(DEVELOPER_MARK_TAG)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    @Test
    fun theReportButtonAndItsMenuAreMarked() =
        show({
            assertEquals(1, markedTag(TESTER_REPORT_TAG))
            compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
            compose.waitForIdle()
            assertEquals(1, markedTag(TESTER_REPORT_MENU_REPORT_TAG))
            assertEquals(1, markedTag(TESTER_REPORT_MENU_HIDE_TAG))
        }) { TesterReportButton(ReportButtonState(false, {}, {}), scrolling = { false }) }

    @Test
    fun theTestersReviewIsMarked() =
        show({
            assertTrue("marks: ${marks()}", marks() >= 1)
            assertEquals(1, markedTag(TESTER_NOTE_TAG))
        }) { TesterReportDialog(reports.snapshot(settings), { "" }, NoMail) {} }

    @Test
    fun theProductionReportDialogIsNotMarked() =
        show({
            compose.onNodeWithTag(REPORT_DIALOG_TAG).assertExists()
            assertEquals(0, marks())
        }) { InsightReportDialog(track(insight = insight), insight, NoMail) {} }
}
