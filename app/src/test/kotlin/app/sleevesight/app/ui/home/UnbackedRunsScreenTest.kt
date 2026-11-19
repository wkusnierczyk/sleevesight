package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One mark per run of dimmed sentences with the same backing (#1437): a grey chip for each line that
 * does not back them, a `?` for none, never both on one sentence; both go to the note, worded for what
 * is dimmed, whose lines' chips, sorted, open *Sources*.
 */
@RunWith(RobolectricTestRunner::class)
class UnbackedRunsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        reply: String,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(insight = written(reply, support = true)), aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            block()
        }
    }

    private fun prose() =
        compose.onNode(
            hasText("It topped the charts", substring = true) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )

    private fun textOf(): AnnotatedString =
        prose().fetchSemanticsNode().config[SemanticsProperties.Text].first { "It topped the charts" in it.text }

    private fun links(text: AnnotatedString) =
        text.getLinkAnnotations(0, text.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()

    private fun noteSaid() =
        compose
            .onNodeWithTag(UNBACKED_NOTE_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()

    // A chip's text: its label between thin spaces.
    private fun chip(label: String) = "\u2009$label\u2009"

    private val mark = "⁠ " + chip("?")

    @Test
    fun aRunCitingTheSameLineShowsItsGreyChipOnceAfterItsLast() =
        show("It topped the charts in 1986 [W1]. It was a hit in 1990 [W1]. Sundown was recorded in 1973 [MB1].") {
            val text = textOf()
            assertEquals(
                "It topped the charts in 1986. It was a hit in 1990 ${chip("W1")}. " +
                    "Sundown was recorded in 1973 ${chip("MB1")}.",
                text.text,
            )
            assertEquals(1, links(text).count { it.tag == CITE_PREFIX + "W1" })
            assertEquals(0, links(text).count { it.tag == UNBACKED_LINK_TAG })
            assertEquals(
                "It topped the charts in 1986. It was a hit in 1990 (W1: does not say this). " +
                    "Sundown was recorded in 1973 (Source MB1).",
                prose().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
            )
        }

    @Test
    fun aSentenceCitingALineThenOneCitingNothingShowOneMarkEach() =
        show("It topped the charts in 1986 [W1]. It is great.") {
            val text = textOf()
            assertEquals("It topped the charts in 1986 ${chip("W1")}. It is great$mark.", text.text)
            assertEquals(1, links(text).count { it.tag == UNBACKED_LINK_TAG })
            assertEquals(
                "Dimmed text is not fully backed by the cited sources (Source W1), or is not backed by any source.",
                noteSaid(),
            )
        }

    @Test
    fun theNoteNamesOnlyLinesThatDoNotBackTheText() =
        show("It topped the charts in 1986 [W1]. Sundown was recorded in 1973 [MB1].") {
            assertEquals("Dimmed text is not fully backed by the cited sources (Source W1).", noteSaid())
        }

    @Test
    fun theNotesLinesAreEachShownOnceSorted() =
        show(
            "It topped the charts in 1986 [W1]. It is a single. Sundown sold well in 1975 [MB1]. " +
                "It was a hit in 1990 [W1].",
        ) {
            assertEquals(
                "Dimmed text is not fully backed by the cited sources (Source MB1, Source W1), " +
                    "or is not backed by any source.",
                noteSaid(),
            )
            val note =
                compose
                    .onNodeWithTag(UNBACKED_NOTE_TAG)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            assertEquals(listOf(CITE_PREFIX + "MB1", CITE_PREFIX + "W1"), links(note).map { it.tag })
        }

    @Test
    fun theGreyChipAndItsActionGoToTheNote() =
        show("It topped the charts in 1986 [W1]. Sundown was recorded in 1973 [MB1].") {
            val actions = prose().fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(listOf("Source MB1", "W1: does not say this, see the note"), actions.map { it.label })
            val w1 = links(textOf()).single { it.tag == CITE_PREFIX + "W1" }
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread { w1.linkInteractionListener!!.onClick(w1) }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(INTO_VIEW_MILLIS)
                compose.waitForIdle()
                compose.onNodeWithTag(UNBACKED_NOTE_FLASHING_TAG, useUnmergedTree = true).assertExists()
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }

    @Test
    fun theNotesChipOpensItsLineInSources() =
        show("It topped the charts in 1986 [W1]. Sundown was recorded in 1973 [MB1].") {
            val node = compose.onNodeWithTag(UNBACKED_NOTE_TAG).performScrollTo().fetchSemanticsNode()
            val action = node.config[SemanticsActions.CustomActions].single()
            assertEquals("Source W1", action.label)
            compose.runOnUiThread { action.action() }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertExists()
        }

    private companion object {
        const val INTO_VIEW_MILLIS = 400L
    }
}
