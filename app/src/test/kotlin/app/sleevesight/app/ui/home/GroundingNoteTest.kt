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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The grounding line under a written insight (#437): by default no grounded count, and a plain note
 * only for sentences the sources do not back; with *Show grounding details* on, the grounding
 * check's counts in full. An insight kept from before says so in the footer's first line, *As
 * written previously.* (#671), not in this line.
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

    // No note of any count, "0 sentences …" included.
    private fun assertNoUnbackedNote() {
        assertTrue(unbacked(1).contains(NOT_BACKED) && unbacked(2).contains(NOT_BACKED))
        assertEquals(0, count(NOT_BACKED, substring = true))
    }

    @Test
    fun aFullyCitedInsightShowsNoGroundedCountAndNoNote() =
        show(written("It was recorded in 1973 [MB1]. It topped the charts [W1].")) {
            compose.onNodeWithText("It was recorded in 1973", substring = true).assertExists()
            assertNoGroundedCount()
            assertNoUnbackedNote()
            assertEquals(0, count(text(R.string.home_note_kept), substring = true))
        }

    @Test
    fun anUncitedSentenceGetsAPlainNoteInTheSingular() =
        show(written("It was recorded in 1973 [MB1]. It is great.")) {
            compose.onNodeWithText(unbacked(1)).assertExists()
            assertEquals("The sentence underlined in red is not backed by the sources.", unbacked(1))
            assertNoGroundedCount()
        }

    @Test
    fun theNoteCountsTheUnderlinedSentencesUncitedAndMissingTogetherInThePlural() =
        show(
            written(
                "It was recorded in 1973 [MB1]. It is great. It was a hit [MB9]. It is loved [AI].",
                allowModel = true,
            ),
        ) {
            // The model's own sentence carries its chip and no underline, so the note leaves it out.
            compose.onNodeWithText(unbacked(2)).assertExists()
            assertEquals("The 2 sentences underlined in red are not backed by the sources.", unbacked(2))
            assertNoGroundedCount()
        }

    @Test
    fun aSentenceFromTheModelsOwnKnowledgeAloneGetsNoNote() =
        show(written("It was recorded in 1973 [MB1]. It is loved [M].", allowModel = true)) {
            assertNoUnbackedNote()
        }

    @Test
    fun aKeptInsightSaysSoInItsOwnLineNotInTheNote() {
        show(written("It was recorded in 1973 [MB1].", cached = true)) {
            compose.onNodeWithText(text(R.string.home_written_before)).assertExists()
            assertEquals(0, count(text(R.string.home_note_kept), substring = true))
            assertNoGroundedCount()
            assertNoUnbackedNote()
        }
        show(written("It was recorded in 1973 [MB1]. It is great.", cached = true)) {
            // Each on its own line: the note alone, under the line about the insight's history.
            val before = compose.onNodeWithText(text(R.string.home_written_before)).fetchSemanticsNode().boundsInRoot
            val note = compose.onNodeWithText(unbacked(1)).fetchSemanticsNode().boundsInRoot
            assertTrue("the note under As written previously.", note.top >= before.bottom - 1f)
            assertEquals(0, count(text(R.string.home_note_kept), substring = true))
        }
    }

    @Test
    fun showGroundingDetailsBringsBackTheFullCounts() {
        val details = Settings(developerMode = true, showGroundingDetails = true)
        show(written("It was recorded in 1973 [MB1]. It is great. It was a hit [MB9].", cached = true), details) {
            val grounded = plural(R.plurals.home_meta_grounded, 3, 1, 3)
            val uncited = text(R.string.home_meta_uncited, 1)
            val unknown = plural(R.plurals.home_meta_unknown, 1, 1)
            // Without *kept from before*, which the line above it says (#671).
            compose.onNodeWithText("$grounded · $uncited · $unknown").assertExists()
            compose.onNodeWithText(text(R.string.home_written_before)).assertExists()
            assertEquals(0, count("kept from before", substring = true))
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

    @Test
    fun showGroundingDetailsSaysWhenTheModelsSelfCorrectionWasTakenOutAndCountsWhatIsShown() {
        val details = Settings(developerMode = true, showGroundingDetails = true)
        val reply =
            "It was recorded in 1973 [MB1]. It is great.\n\n" +
                "Wait, that second sentence is not supported, so I'll drop it. Here is the corrected insight:\n\n" +
                "It was recorded in 1973 [MB1]."
        show(written(reply), details) {
            val grounded = plural(R.plurals.home_meta_grounded, 1, 1, 1)
            compose.onNodeWithText("$grounded · ${text(R.string.home_meta_self_corrected)}").assertExists()
            assertEquals(0, count("Wait,", substring = true))
            assertEquals(0, count("It is great", substring = true))
        }
        // Without the details, no note: the version shown is cited throughout.
        show(written(reply)) {
            assertNoUnbackedNote()
            assertEquals(0, count(text(R.string.home_meta_self_corrected), substring = true))
        }
    }

    private companion object {
        const val NOT_BACKED = "not backed by the sources"
    }
}
