package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsLog
import app.sleevesight.app.diagnostics.SkipLearning
import app.sleevesight.app.ui.home.PAGE_BACK_TAG
import app.sleevesight.app.ui.home.PAGE_TITLE_TAG
import app.sleevesight.core.pipeline.EventLine
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.store.CacheStats
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangePage
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import app.sleevesight.store.history.HistoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The diagnostics screen (#54): the events, the track changes a page at a time, what is learned. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HistoryDatabase.Schema.create(it) }
    private val history = SessionHistory(jdbc)
    private val start = Instant.parse("2026-10-17T08:00:00Z")

    @After
    fun close() {
        appScope.cancel()
        jdbc.close()
    }

    private class Learning(
        crossfades: Map<String, Duration>,
        private val fails: Boolean = false,
    ) : SkipLearning {
        override val learnedCrossfades = MutableStateFlow(crossfades)
        var resets = 0

        override suspend fun reset(): Boolean {
            resets++
            check(!fails) { "database is locked" }
            learnedCrossfades.value = emptyMap()
            return true
        }
    }

    /** The process's scope, as the app graph gives it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun text(id: Int) = app.getString(id)

    private fun diagnostics(learning: SkipLearning? = null) =
        Diagnostics(
            log = DiagnosticsLog(),
            readChanges = history::changes,
            readCacheStats = {
                CacheStats(
                    TableStats(3, 300),
                    TableStats(2, 20),
                    TableStats(1, 1_000),
                    TableStats(4, 400),
                )
            },
            // The JDBC driver's in-memory database is for one thread.
            io = Dispatchers.Main,
            scope = appScope,
            version = "test",
            skipLearning = learning,
        )

    private fun change(n: Int) =
        TrackChangeRecord(
            player = "com.spotify.music",
            changedAt = start + n.seconds,
            from = TrackRef("Song $n", null),
            to = TrackRef("Song ${n + 1}", null),
            timeLeft = 2_000.milliseconds,
            states = listOf(PlaybackStateRecord(1_200.milliseconds, "PLAYING", null, null, 1f)),
            sinceLastAction = null,
            learnedCrossfade = null,
        )

    private fun show(
        diagnostics: Diagnostics,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DiagnosticsScreen(diagnostics, onBack = {}) } }
            compose.waitForIdle()
            block()
        }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(conditionDescription = text, timeoutMillis = WAIT_MS) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun y(text: String) =
        compose
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .positionInRoot.y

    @Test
    fun theBarHasTheBackArrowTheTitleAndExport() {
        var backs = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DiagnosticsScreen(diagnostics(), onBack = { backs++ }) } }
            compose
                .onNodeWithTag(PAGE_TITLE_TAG)
                .assertTextEquals(text(R.string.diagnostics_title))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNodeWithText(text(R.string.diagnostics_export)).assertIsDisplayed()
            compose
                .onNodeWithTag(PAGE_BACK_TAG)
                .assertContentDescriptionEquals(text(R.string.page_back))
                .performClick()
            assertEquals(1, backs)
        }
    }

    @Test
    fun showsTheCachesAndTheEventsNewestFirst() {
        val diagnostics = diagnostics()
        val older = PipelineEvent.Superseded("older")
        val newer = PipelineEvent.NoEvidence("newer")
        diagnostics.log.record(older)
        diagnostics.log.record(newer)

        show(diagnostics) {
            waitForText("retrieval: 3 entries, 300 bytes")
            waitForText("artist results: 4 entries, 400 bytes")
            assertTrue(y(EventLine.of(newer)) < y(EventLine.of(older)))
        }
    }

    @Test
    fun showsTheTrackChangesAPageAtATimeEachOpeningOnItsStates() {
        (1..Diagnostics.PAGE_SIZE + 1).forEach { history.changed(change(it)) }

        show(diagnostics()) {
            compose.onNodeWithText(text(R.string.diagnostics_tab_changes)).performClick()
            val last = Diagnostics.PAGE_SIZE + 1
            val newest = "com.spotify.music: \"Song $last\" -> \"Song ${last + 1}\""
            waitForText(newest)
            // The states open on a tap.
            val state = "-1200 ms PLAYING, position -, speed 1.0"
            assertEquals(0, compose.onAllNodesWithText(state).fetchSemanticsNodes().size)
            compose.onNodeWithText(newest, substring = true).performClick()
            waitForText(state)

            // The oldest change is on the next page.
            val oldest = "com.spotify.music: \"Song 1\" -> \"Song 2\""
            assertEquals(0, compose.onAllNodesWithText(oldest, substring = true).fetchSemanticsNodes().size)
            val older = text(R.string.diagnostics_older_changes)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(older))
            compose.onNodeWithText(older).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(oldest, substring = true))
            waitForText(oldest)
            assertEquals(0, compose.onAllNodesWithText(older).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun saysWhenThereAreNoTrackChanges() =
        show(diagnostics()) {
            compose.onNodeWithText(text(R.string.diagnostics_tab_changes)).performClick()
            waitForText(text(R.string.diagnostics_no_changes))
        }

    @Test
    fun saysThatNothingIsLearnedWhenSkipDetectionSuppliesNothing() =
        show(diagnostics()) {
            compose.onNodeWithText(text(R.string.diagnostics_tab_learned)).performClick()
            waitForText(text(R.string.diagnostics_not_learning))
            assertEquals(0, compose.onAllNodesWithText(text(R.string.diagnostics_reset)).fetchSemanticsNodes().size)
        }

    @Test
    fun showsWhatIsLearnedAndResetsItOnceConfirmed() {
        val learning = Learning(mapOf("com.spotify.music" to 2_000.milliseconds))

        show(diagnostics(learning)) {
            compose.onNodeWithText(text(R.string.diagnostics_tab_learned)).performClick()
            waitForText("com.spotify.music: learned crossfade 2000 ms")

            compose.onNodeWithText(text(R.string.diagnostics_reset)).performClick()
            waitForText(text(R.string.diagnostics_reset_title))
            assertEquals(0, learning.resets)
            compose.onNode(hasText(text(R.string.diagnostics_reset)) and hasAnyAncestor(isDialog())).performClick()

            waitForText(text(R.string.diagnostics_nothing_learned))
            assertEquals(1, learning.resets)
        }
    }

    @Test
    fun aResetThatFailsSaysSo() {
        val learning = Learning(mapOf("com.spotify.music" to 2_000.milliseconds), fails = true)

        show(diagnostics(learning)) {
            compose.onNodeWithText(text(R.string.diagnostics_tab_learned)).performClick()
            compose.onNodeWithText(text(R.string.diagnostics_reset)).performClick()
            compose.onNode(hasText(text(R.string.diagnostics_reset)) and hasAnyAncestor(isDialog())).performClick()

            waitForText(text(R.string.diagnostics_reset_failed))
            assertEquals(1, learning.resets)
        }
    }

    @Test
    fun anExportGoesOnThroughTheScreenRecreatedAndItsResultIsShownThere() {
        val gate = CountDownLatch(1)
        val diagnostics =
            Diagnostics(
                log = DiagnosticsLog(),
                // Held until the screen has been recreated.
                readChanges = { _, _ ->
                    gate.await()
                    TrackChangePage(listOf(change(1)), next = null)
                },
                readCacheStats = { CacheStats(TableStats(0, 0), TableStats(0, 0), TableStats(0, 0), TableStats(0, 0)) },
                io = Dispatchers.IO,
                scope = appScope,
                version = "test",
            )
        val out = ByteArrayOutputStream()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DiagnosticsScreen(diagnostics, onBack = {}) } }
            // As the file picker's result would start it.
            scenario.onActivity { diagnostics.export { out } }
            waitForText(text(R.string.diagnostics_exporting))

            scenario.recreate()
            scenario.onActivity { it.setContent { DiagnosticsScreen(diagnostics, onBack = {}) } }
            waitForText(text(R.string.diagnostics_exporting))
            gate.countDown()

            waitForText(text(R.string.diagnostics_exported))
            assertTrue(out.toString(Charsets.UTF_8).contains("\"Song 1\" -> \"Song 2\""))
        }
    }
}
