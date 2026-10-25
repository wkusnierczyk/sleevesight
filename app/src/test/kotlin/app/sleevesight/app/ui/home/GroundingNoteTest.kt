package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line under a written insight (#437): by default no grounded count, and a plain note only for
 * sentences the sources do not back and for an insight kept from before; with *Show grounding
 * details* on, the grounding check's counts in full.
 */
@RunWith(RobolectricTestRunner::class)
class GroundingNoteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun plural(
        id: Int,
        count: Int,
        vararg args: Any,
    ) = app.resources.getQuantityString(id, count, *args)

    private fun unbacked(count: Int) = plural(R.plurals.home_note_unbacked, count, count)

    private fun show(
        insight: InsightState.Written,
        settings: Settings = Settings(),
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(insight = insight), aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
            )
        val actions = NowPlayingActions(normalizer::normalize, { "Spotify" }, { null })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            block()
        }
    }

    private fun count(
        text: String,
        substring: Boolean = false,
    ) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    // Neither the count nor a word of it: "sentences grounded", "sentence grounded".
    private fun assertNoGroundedCount() = assertEquals(0, count(" grounded", substring = true))

    @Test
    fun aFullyCitedInsightShowsNoGroundedCountAndNoNote() =
        show(written("It was recorded in 1973 [MB1]. It topped the charts [W1].")) {
            compose.onNodeWithText("It was recorded in 1973", substring = true).assertExists()
            assertNoGroundedCount()
            assertEquals(0, count(unbacked(1)) + count(unbacked(2)))
            assertEquals(0, count(text(R.string.home_note_kept), substring = true))
        }

    @Test
    fun anUncitedSentenceGetsAPlainNoteInTheSingular() =
        show(written("It was recorded in 1973 [MB1]. It is great.")) {
            compose.onNodeWithText(unbacked(1)).assertExists()
            assertEquals("1 sentence is not backed by the sources.", unbacked(1))
            assertNoGroundedCount()
        }

    @Test
    fun theNoteCountsUncitedMissingAndModelSentencesTogetherInThePlural() =
        show(
            written(
                "It was recorded in 1973 [MB1]. It is great. It was a hit [MB9]. It is loved [M].",
                allowModel = true,
            ),
        ) {
            compose.onNodeWithText(unbacked(3)).assertExists()
            assertEquals("3 sentences are not backed by the sources.", unbacked(3))
            assertNoGroundedCount()
        }

    @Test
    fun aKeptInsightSaysSoAloneOrAfterTheNote() {
        show(written("It was recorded in 1973 [MB1].", cached = true)) {
            compose.onNodeWithText(text(R.string.home_note_kept)).assertExists()
            assertNoGroundedCount()
        }
        show(written("It was recorded in 1973 [MB1]. It is great.", cached = true)) {
            compose.onNodeWithText("${unbacked(1)} ${text(R.string.home_note_kept)}").assertExists()
        }
    }

    @Test
    fun showGroundingDetailsBringsBackTheFullCounts() {
        val details = Settings(developerMode = true, showGroundingDetails = true)
        show(written("It was recorded in 1973 [MB1]. It is great. It was a hit [MB9].", cached = true), details) {
            val grounded = plural(R.plurals.home_meta_grounded, 3, 1, 3)
            val uncited = text(R.string.home_meta_uncited, 1)
            val unknown = plural(R.plurals.home_meta_unknown, 1, 1)
            compose
                .onNodeWithText("$grounded · $uncited · $unknown · ${text(R.string.home_meta_kept)}")
                .assertExists()
            assertEquals(
                "1 of 3 sentences grounded · 1 uncited · 1 cites missing evidence",
                "$grounded · $uncited · $unknown",
            )
            assertEquals(0, count(unbacked(2)))
        }
        // Every sentence grounded: the count still shows, in the singular for one.
        show(written("It was recorded in 1973 [MB1]."), details) {
            compose.onNodeWithText(plural(R.plurals.home_meta_grounded, 1, 1, 1)).assertExists()
            assertEquals("1 of 1 sentence grounded", plural(R.plurals.home_meta_grounded, 1, 1, 1))
        }
    }
}
