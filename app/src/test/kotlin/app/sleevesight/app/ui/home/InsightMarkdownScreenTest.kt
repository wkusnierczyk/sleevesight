package app.sleevesight.app.ui.home

import android.app.PendingIntent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The insight on Home with the Markdown its model wrote anyway (#555): cleaned, its titles in italics. */
@RunWith(RobolectricTestRunner::class)
class InsightMarkdownScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private fun show(
        insight: InsightState,
        block: () -> Unit,
    ) {
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                { "Spotify" },
                { null as PendingIntent? },
                write = { _, _ -> },
                stop = {},
            )
        val view = PipelineView(track(insight = insight), aiReady = true)
        val state =
            NowPlayingState(
                view,
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            block()
        }
    }

    // The paragraph that starts with [start]: its text, and the node, which holds the actions.
    private fun prose(start: String) =
        compose.onNode(
            hasText(start, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )

    private fun textOf(start: String): AnnotatedString =
        prose(start)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first { start in it.text }

    // The text of [prose] that [matches] styles.
    private fun styled(
        prose: AnnotatedString,
        matches: (SpanStyle) -> Boolean,
    ): List<String> = prose.spanStyles.filter { matches(it.item) }.map { prose.text.substring(it.start, it.end) }

    // A citation chip's text: its id between thin spaces.
    private fun chip(id: String) = "\u2009$id\u2009"

    private val reply = "It is the song from *Project Hail Mary* [MB1]. It was **huge** on _Crazy World_ [W1]."

    private fun assertCleaned() {
        val prose = textOf("It is the song from")
        assertEquals(
            "It is the song from Project Hail Mary ${chip("MB1")}. It was huge on Crazy World ${chip("W1")}.",
            prose.text,
        )
        assertEquals(listOf("Project Hail Mary", "Crazy World"), styled(prose) { it.fontStyle == FontStyle.Italic })
        assertEquals(listOf("huge"), styled(prose) { it.fontWeight == FontWeight.Bold })
        // A screen reader hears neither the marks nor the citations' brackets.
        val said = prose("It is the song from").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(
            listOf(
                "It is the song from Project Hail Mary (${text(R.string.home_cite, "MB1")}). " +
                    "It was huge on Crazy World (${text(R.string.home_cite, "W1")}).",
            ),
            said,
        )
    }

    @Test
    fun aWrittenInsightShowsItsTitlesInItalicsWithoutTheMarksAndItsCitationsAsChips() =
        show(written(reply)) {
            assertCleaned()
            // The citation after the title is still a chip that scrolls to its line.
            prose("It is the song from").performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "MB1"
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "MB1").assertIsDisplayed()
        }

    @Test
    fun aCardKeptFromBeforeShowsCleanedToo() = show(written(reply, cached = true)) { assertCleaned() }

    @Test
    fun anInsightBeingWrittenShowsCleaned() = show(InsightState.Writing(reply)) { assertCleaned() }

    @Test
    fun aHeadingAndAListShowAsTheirText() =
        show(written("# Sundown\nIt topped the charts [W1].\n\n- It was recorded in 1973 [MB1].\n- It is great.")) {
            val first = textOf("Sundown")
            assertEquals("Sundown\nIt topped the charts ${chip("W1")}.", first.text)
            val second = textOf("It was recorded")
            assertEquals("It was recorded in 1973 ${chip("MB1")}.\nIt is great.", second.text)
            assertFalse('#' in first.text || '-' in second.text)
            // The second item is a sentence of its own, on its own line, and flagged as uncited (#574).
            val flagged = second.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }
            assertEquals(listOf("It is great."), flagged.map { second.text.substring(it.start, it.end) })
        }

    @Test
    fun anUncitedSentenceWithBoldAndItalicsIsFlaggedAndSaidToBeSo() =
        show(written("It topped the charts [W1]. It is **really** *great*.")) {
            val prose = textOf("It topped the charts")
            val flagged = lightColorScheme().error
            val really = prose.spanStyles.single { prose.text.substring(it.start, it.end) == "really" }.item
            assertEquals(FontWeight.Bold, really.fontWeight)
            assertEquals(TextDecoration.Underline, really.textDecoration)
            assertEquals(flagged, really.color)
            val great = prose.spanStyles.single { prose.text.substring(it.start, it.end) == "great" }.item
            assertEquals(FontStyle.Italic, great.fontStyle)
            assertEquals(TextDecoration.Underline, great.textDecoration)
            assertEquals(flagged, great.color)
            assertEquals(
                listOf(
                    "It topped the charts (${text(R.string.home_cite, "W1")}). " +
                        "It is really great (${text(R.string.home_cite_none)}).",
                ),
                prose("It topped the charts").fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
            )
        }

    @Test
    fun aRuleOnItsOwnShowsAsNothingAndIsNotCountedAsUnbacked() =
        show(written("It charted [W1].\n\n---\n\nIt sold [MB1].")) {
            // The check reads the rule as a paragraph and an uncited sentence; the card shows none of it.
            val unbacked = app.resources.getQuantityString(R.plurals.home_note_unbacked, 1, 1)
            assertEquals(0, compose.onAllNodesWithText(unbacked).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("---", substring = true).fetchSemanticsNodes().size)
            textOf("It charted")
            textOf("It sold")
        }
}
