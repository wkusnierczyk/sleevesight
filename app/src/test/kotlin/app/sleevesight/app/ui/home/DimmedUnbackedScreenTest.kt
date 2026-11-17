package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One mark for text the sources do not back (#954): a dimmed sentence upright, in the text's colour
 * at 60 %, and a `?` chip after the last of each run of them, after its own chips, which goes to the
 * note under the insight ("? Dimmed text is not backed by the sources."); none after a sentence from
 * the AI model's own knowledge.
 */
@RunWith(RobolectricTestRunner::class)
class DimmedUnbackedScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val seeNote get() = app.getString(R.string.home_cite_see_note)

    private fun show(
        insight: InsightState,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(insight = insight), aiReady = true),
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

    // The paragraph that starts with [start], the node that holds its actions.
    private fun prose(start: String) =
        compose.onNode(
            hasText(start, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )

    private fun textOf(start: String): AnnotatedString =
        prose(start).fetchSemanticsNode().config[SemanticsProperties.Text].first { start in it.text }

    private fun said(start: String): String =
        prose(start).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    // A chip's text: its label between thin spaces.
    private fun chip(label: String) = " $label "

    private val mark = chip("?")

    // The `?` chips' links in [text].
    private fun marks(text: AnnotatedString) =
        text
            .getLinkAnnotations(0, text.length)
            .filter { (it.item as? LinkAnnotation.Clickable)?.tag == UNBACKED_LINK_TAG }

    @Test
    fun anUncitedSentenceIsUprightInTheTextColourAt60PercentWithItsTitleInItalics() =
        show(written("It topped the charts [W1]. It is *Sundown* at its best.")) {
            val text = textOf("It topped the charts")
            val dimmed = text.spanStyles.filter { it.item.color.alpha < 1f }
            assertTrue(dimmed.isNotEmpty())
            for (span in dimmed) {
                assertEquals(0.6f, span.item.color.alpha, 0.01f)
                // Upright, but for the title, which stays in italics as anywhere.
                val italic = text.text.substring(span.start, span.end) == "Sundown"
                assertEquals(if (italic) FontStyle.Italic else null, span.item.fontStyle)
                // Nothing behind it.
                assertFalse(span.item.background.isSpecified)
            }
            val shown = dimmed.joinToString("") { text.text.substring(it.start, it.end) }
            assertEquals("It is Sundown at its best.", shown)
        }

    @Test
    fun anUncitedSentenceEndsWithTheMarkBeforeItsStop() =
        show(written("It topped the charts [W1]. It is great.")) {
            val text = textOf("It topped the charts")
            assertEquals("It topped the charts ${chip("W1")}. It is great $mark.", text.text)
            assertEquals(1, marks(text).size)
        }

    @Test
    fun aSentenceCitingAMissingLineGetsTheMarkAfterItsChip() =
        show(written("It topped the charts [W1]. It was a hit [MB9].")) {
            val text = textOf("It topped the charts")
            assertEquals("It topped the charts ${chip("W1")}. It was a hit ${chip("MB9")} $mark.", text.text)
            assertEquals(
                "It topped the charts (Source W1). It was a hit (MB9: no such evidence line) ($seeNote).",
                said("It topped the charts"),
            )
        }

    @Test
    fun anUnsupportedSentenceGetsTheMarkAfterItsChip() =
        show(written("It topped the charts in 1986 [W1]. Sundown was recorded in 1973 [MB1].", support = true)) {
            val text = textOf("It topped the charts")
            assertTrue(text.text.startsWith("It topped the charts in 1986 ${chip("W1")} $mark."))
            assertEquals(1, marks(text).size)
        }

    @Test
    fun aSentenceFromTheModelsOwnKnowledgeGetsNoMark() =
        show(written("It topped the charts [W1]. It is loved [M].", allowModel = true)) {
            val text = textOf("It topped the charts")
            assertEquals(0, marks(text).size)
            assertTrue(mark !in text.text)
            assertEquals(0, compose.onAllNodesWithTag(UNBACKED_NOTE_TAG).fetchSemanticsNodes().size)
        }

    @Test
    fun twoDimmedSentencesInARowGetOneMarkAfterTheSecond() =
        show(written("It topped the charts [W1]. It is great. It is loved.")) {
            val text = textOf("It topped the charts")
            assertEquals("It topped the charts ${chip("W1")}. It is great. It is loved $mark.", text.text)
            assertEquals(1, marks(text).size)
            assertEquals(
                "It topped the charts (Source W1). It is great (not cited). It is loved (not cited) ($seeNote).",
                said("It topped the charts"),
            )
        }

    @Test
    fun dimmedBackedDimmedGetTwoMarks() =
        show(written("It is great. It topped the charts [W1]. It is loved.")) {
            val text = textOf("It is great")
            assertEquals("It is great $mark. It topped the charts ${chip("W1")}. It is loved $mark.", text.text)
            assertEquals(2, marks(text).size)
        }

    @Test
    fun aSentenceFromTheModelsOwnKnowledgeBreaksARun() =
        show(written("It is great. It is loved [M]. It is old.", allowModel = true)) {
            val text = textOf("It is great")
            assertEquals(2, marks(text).size)
            assertTrue(text.text.startsWith("It is great $mark. It is loved "))
        }

    @Test
    fun aRunThatEndsAParagraphKeepsItsMarkAtTheEnd() =
        show(written("It topped the charts [W1]. It is great. It is loved.\n\nIt was recorded in 1973 [MB1].")) {
            val first = textOf("It topped the charts")
            assertTrue(first.text.endsWith("It is great. It is loved $mark."))
            assertEquals(1, marks(first).size)
            assertEquals(0, marks(textOf("It was recorded")).size)
        }

    @Test
    fun theNoteStartsWithTheMark() =
        show(written("It topped the charts [W1]. It is great.")) {
            val note = compose.onNodeWithTag(UNBACKED_NOTE_TAG).performScrollTo().fetchSemanticsNode()
            val words = compose.onNodeWithText(app.getString(R.string.home_note_unbacked)).fetchSemanticsNode()
            assertEquals("Dimmed text is not backed by the sources.", app.getString(R.string.home_note_unbacked))
            // The chip comes first, so the words start to its right; the chip itself is not read.
            assertTrue(words.boundsInRoot.left > note.boundsInRoot.left)
            val read = words.config[SemanticsProperties.Text].single().text
            assertEquals(app.getString(R.string.home_note_unbacked), read)
        }

    @Test
    fun aTapOnTheMarkFlashesTheNote() =
        show(written("It topped the charts [W1]. It is great.")) {
            val link = marks(textOf("It topped the charts")).single().item as LinkAnnotation.Clickable
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { link.linkInteractionListener!!.onClick(link) }
            // Brought into view, then flashing, for its time.
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(INTO_VIEW_MILLIS)
            compose.waitForIdle()
            compose.onNodeWithTag(UNBACKED_NOTE_FLASHING_TAG, useUnmergedTree = true).assertExists()
            compose.mainClock.advanceTimeBy(FLASH_MILLIS)
            compose.onNodeWithTag(UNBACKED_NOTE_TAG, useUnmergedTree = true).assertExists()
            compose.mainClock.autoAdvance = true
        }

    @Test
    fun theScreenReaderHasTheMarkAsAnActionThatGoesToTheNote() =
        show(written("It topped the charts [W1]. It is great.")) {
            assertEquals(
                "It topped the charts (Source W1). It is great (not cited) ($seeNote).",
                said("It topped the charts"),
            )
            val actions = prose("It topped the charts").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            assertEquals(listOf("Source W1", seeNote), actions.map { it.label })
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { actions.last().action() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(INTO_VIEW_MILLIS)
            compose.waitForIdle()
            compose.onNodeWithTag(UNBACKED_NOTE_FLASHING_TAG, useUnmergedTree = true).assertExists()
            compose.mainClock.autoAdvance = true
        }

    private companion object {
        const val FLASH_MILLIS = 2000L
        const val INTO_VIEW_MILLIS = 400L
    }
}
