package app.sleevesight.app.ui.report

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
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
import app.sleevesight.app.ui.HomeScreen
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.CacheStats
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Home's report button under each *Report button* choice (#518): *Show* stays while Home scrolls;
 * *Auto* hides it while Home scrolls and brings it back once the scrolling stops, at once with the
 * phone's animations off, and kept while its menu is open; either way, scrolled to the end, it covers
 * nothing. A tap opens its menu: *Report* opens the review, and *Hide* sets *Hide* and, once that is
 * saved, says where to bring the button back, until *Dismiss*; a save that fails says the button
 * could not be hidden. Time is the test clock's, never the wall clock's.
 */
@RunWith(RobolectricTestRunner::class)
class ReportButtonHomeTest {
    // As `HomeScreenTest`'s: the test rule's recomposer does not read the phone's animator duration
    // scale, so the scale is given to it here.
    private val motion =
        object : MotionDurationScale {
            var factor = 1f
            override val scaleFactor get() = factor
        }

    @get:Rule
    val compose = createEmptyComposeRule(motion)

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun close() = scope.coroutineContext[Job]!!.cancel()

    private val settings = MutableStateFlow(Settings(developerMode = true, reportButton = ReportButton.AUTO))
    private var hides = 0

    // How *Hide* is saved: by default at once, as the app's store does it.
    private var save: () -> Deferred<Boolean> = {
        settings.value = settings.value.copy(reportButton = ReportButton.HIDE)
        CompletableDeferred(true)
    }

    private val none = TableStats(0, 0)

    private val reports =
        TesterReports(
            build = AppBuild("1.2.3", 7, testBuild = true),
            view = MutableStateFlow(PipelineView()),
            playing = MutableStateFlow(null),
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
        )

    private val mail =
        object : MailHandoff {
            override fun send(mail: Mail) = Handed.WHOLE

            override fun copy(mail: Mail) = Unit

            override val confirmsCopy: Boolean = true
        }

    // Home with the button, over enough lines to scroll far past a screenful; *Hide* by [save].
    private fun show(block: () -> Unit) {
        val feed =
            TesterReportFeed(
                settings,
                hide = {
                    hides++
                    save()
                },
                reports,
                mail,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme {
                        WithTesterReport(feed) { report ->
                            HomeScreen(true, onOpenFirstRun = {}, onOpenSettings = {}, report = report) {
                                Column(Modifier.testTag(LINES_TAG)) {
                                    for (i in 0 until LINES) Text("Line $i")
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun buttons() = compose.onAllNodesWithTag(TESTER_REPORT_TAG).fetchSemanticsNodes().size

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // A finger on Home, moved up past the touch slop and held there: Home scrolls until it lifts.
    private fun holdAScroll() =
        compose.onNodeWithTag(LINES_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 1f))
            moveBy(Offset(0f, -SCROLL_PX))
        }

    // The finger lifted after resting: no fling, so the scroll ends with it.
    private fun lift() =
        compose.onNodeWithTag(LINES_TAG).performTouchInput {
            advanceEventTime(REST_MILLIS)
            up()
        }

    @Test
    fun underShowTheButtonStaysWhileHomeScrolls() {
        settings.value = settings.value.copy(reportButton = ReportButton.SHOW)
        show {
            compose.mainClock.autoAdvance = false
            holdAScroll()
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            assertEquals(1, buttons())
            lift()
        }
    }

    @Test
    fun underAutoTheButtonHidesWhileHomeScrollsAndComesBackOnceItStops() =
        show {
            assertEquals(1, buttons())
            compose.mainClock.autoAdvance = false
            holdAScroll()
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            assertEquals("Hidden while Home scrolls", 0, buttons())

            lift()
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            assertEquals("Back once the scrolling stops", 1, buttons())
        }

    @Test
    fun underAutoWithAnimationsOffTheButtonGoesAtOnce() =
        show {
            motion.factor = 0f
            compose.mainClock.autoAdvance = false
            holdAScroll()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            assertEquals(0, buttons())
            lift()
        }

    @Test
    fun underShowAndAutoScrolledToTheEndTheButtonCoversNothing() {
        for (choice in listOf(ReportButton.SHOW, ReportButton.AUTO)) {
            settings.value = settings.value.copy(reportButton = choice)
            show {
                repeat(SWIPES) { compose.onNodeWithTag(LINES_TAG).performTouchInput { swipeUp() } }
                compose.waitForIdle()
                // The last of what scrolls: the way to what leaves the device, under the lines.
                val last = compose.onNode(hasText(app.getString(R.string.home_what_leaves))).fetchSemanticsNode()
                val button = compose.onNodeWithTag(TESTER_REPORT_TAG).fetchSemanticsNode()
                assertTrue(
                    "$choice: ${last.boundsInRoot} under ${button.boundsInRoot}",
                    last.boundsInRoot.bottom <= button.boundsInRoot.top,
                )
            }
        }
    }

    @Test
    fun theMenusReportOpensTheReview() =
        show {
            compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
            compose.onNodeWithText(app.getString(R.string.tester_report_menu_report)).assertExists()
            compose.onNodeWithTag(TESTER_REPORT_MENU_REPORT_TAG).performClick()
            compose.waitForIdle()
            assertTrue(shown(REPORT_DIALOG_TAG))
            assertEquals(0, hides)
        }

    @Test
    fun theMenusHideSetsHideAndSaysWhereToBringTheButtonBackUntilDismissed() =
        show {
            compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
            compose.onNodeWithTag(TESTER_REPORT_MENU_HIDE_TAG).performClick()
            compose.waitForIdle()
            assertEquals(1, hides)
            assertEquals(ReportButton.HIDE, settings.value.reportButton)
            assertEquals(0, buttons())
            assertTrue(shown(TESTER_REPORT_HIDDEN_TAG))
            compose.onNodeWithText(app.getString(R.string.tester_report_hidden)).assertExists()
            assertFalse(shown(REPORT_DIALOG_TAG))

            compose.onNodeWithTag(TESTER_REPORT_HIDDEN_DISMISS_TAG).performClick()
            compose.waitForIdle()
            assertFalse(shown(TESTER_REPORT_HIDDEN_TAG))
            assertEquals(0, buttons())
        }

    private fun hide() {
        compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
        compose.onNodeWithTag(TESTER_REPORT_MENU_HIDE_TAG).performClick()
        compose.waitForIdle()
    }

    @Test
    fun aHideStillBeingSavedShowsNoMessageUntilItIsSaved() {
        val saved = CompletableDeferred<Boolean>()
        save = { saved }
        show {
            hide()
            assertEquals(1, hides)
            assertFalse(shown(TESTER_REPORT_HIDDEN_TAG))
            assertEquals(1, buttons())

            settings.value = settings.value.copy(reportButton = ReportButton.HIDE)
            saved.complete(true)
            compose.waitForIdle()
            compose.onNodeWithText(app.getString(R.string.tester_report_hidden)).assertExists()
            assertEquals(0, buttons())
        }
    }

    @Test
    fun aHideThatCouldNotBeSavedSaysSoAndKeepsTheButton() {
        save = { CompletableDeferred(false) }
        show {
            hide()
            assertTrue(shown(TESTER_REPORT_HIDDEN_TAG))
            compose.onNodeWithText(app.getString(R.string.tester_report_not_hidden)).assertExists()
            assertTrue(
                compose
                    .onAllNodes(
                        hasText(app.getString(R.string.tester_report_hidden)),
                    ).fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertEquals(ReportButton.AUTO, settings.value.reportButton)

            compose.onNodeWithTag(TESTER_REPORT_HIDDEN_DISMISS_TAG).performClick()
            compose.waitForIdle()
            assertFalse(shown(TESTER_REPORT_HIDDEN_TAG))
            assertEquals(1, buttons())
        }
    }

    @Test
    fun underAutoTheButtonStaysWhileItsMenuIsOpenEvenAsHomeScrolls() {
        var scrolling by mutableStateOf(false)
        val state = ReportButtonState(hideWhileScrolling = true, onReport = {}, onHide = {})
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { SleevesightTheme { TesterReportButton(state, scrolling = { scrolling }) } }
            }
            compose.mainClock.autoAdvance = false
            // Without the menu, scrolling hides it, and it is back once the scrolling stops.
            Snapshot.withMutableSnapshot { scrolling = true }
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            assertEquals("Hidden while scrolling", 0, buttons())
            Snapshot.withMutableSnapshot { scrolling = false }
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            assertEquals(1, buttons())

            // With the menu open, it stays, with both items.
            compose.onNodeWithTag(TESTER_REPORT_TAG).performClick()
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            Snapshot.withMutableSnapshot { scrolling = true }
            compose.mainClock.advanceTimeBy(ANIMATIONS_END)
            assertEquals("Kept while the menu is open", 1, buttons())
            assertTrue(shown(TESTER_REPORT_MENU_REPORT_TAG))
            assertTrue(shown(TESTER_REPORT_MENU_HIDE_TAG))
        }
    }

    private companion object {
        const val LINES = 120
        const val LINES_TAG = "lines"
        const val SCROLL_PX = 200f
        const val REST_MILLIS = 500L
        const val SWIPES = 30

        /** Past every animation the button runs, and the scroll's settling. */
        const val ANIMATIONS_END = 1_000L
    }
}
