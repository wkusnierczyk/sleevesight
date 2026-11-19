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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
import app.sleevesight.core.synthesis.SentenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sentence whose cited line holds none of its year (#1036): kept, dimmed as unbacked and named in
 * the note, which shows its line's chip (#1437); its chip grey, with no outline (#1458), and the
 * line's tag in *Sources* a dim outline; TalkBack hears "W1: does not say this", reading aloud "Unverified:"; the
 * developer counts say "1 unsupported (W1)".
 */
@RunWith(RobolectricTestRunner::class)
class UnsupportedCitationScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // `W1` is "It topped the charts.", `MB1` "Sundown was recorded in 1973.": the first sentence's
    // year is in neither.
    private val insight =
        written("It topped the charts in 1986 [W1]. Sundown was recorded in 1973 [MB1].", support = true)

    private fun show(
        written: InsightState.Written = insight,
        settings: Settings = Settings(),
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(insight = written), aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
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

    private fun prose(start: String = "It topped the charts") =
        compose.onNode(
            hasText(start, substring = true) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        )

    @Test
    fun theCheckMarksTheSentenceUnsupported() {
        val statuses =
            insight.grounded.paragraphs
                .flatten()
                .map { it.status }
        assertEquals(listOf(SentenceStatus.UNSUPPORTED, SentenceStatus.OK), statuses)
        assertEquals(setOf("W1"), unsupportedLines(insight.grounded))
    }

    /**
     * Codex, PR #1238: a line another sentence cites is not outlined in *Sources*, whatever that
     * sentence's status, one citing a missing line too.
     */
    @Test
    fun aLineAnotherSentenceCitesIsNotOutlinedInSources() {
        val mixed = written("It topped the charts in 1986 [W1]. It was a hit [W1][MB9].", support = true)
        val statuses =
            mixed.grounded.paragraphs
                .flatten()
                .map { it.status }
        assertEquals(listOf(SentenceStatus.UNSUPPORTED, SentenceStatus.UNKNOWN), statuses)
        assertEquals(emptySet<String>(), unsupportedLines(mixed.grounded))
    }

    @Test
    fun theSentenceIsDimmedNotedAndReadAsNotBacked() =
        show {
            val note = compose.onNodeWithTag(UNBACKED_NOTE_TAG).fetchSemanticsNode()
            assertEquals(
                "Dimmed text is not fully backed by the cited sources (Source W1).",
                note.config[SemanticsProperties.ContentDescription].single(),
            )
            val said = prose().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            assertEquals(
                "It topped the charts in 1986 (W1: does not say this). Sundown was recorded in 1973 (Source MB1).",
                said,
            )
        }

    /**
     * Codex, PR #1453: the last sentence of a run shows its lines' chips once each, as one group at
     * its end, before its stop; none kept where it cited them, inline or repeated.
     */
    @Test
    fun itsChipsShowOnceAtItsEndNotInline() =
        show(
            written(
                "It sold in 1986 [W1], then again in 1990 [W1]. Sundown was recorded in 1973 [MB1].",
                support = true,
            ),
        ) {
            val text = prose("It sold").fetchSemanticsNode().config[SemanticsProperties.Text].first()
            val chip = { id: String -> "\u2009$id\u2009" }
            assertEquals(
                "It sold in 1986, then again in 1990 ${chip("W1")}. Sundown was recorded in 1973 ${chip("MB1")}.",
                text.text,
            )
            val links = clickable(text)
            assertEquals(1, links.count { it.tag == CITE_PREFIX + "W1" })
        }

    private fun clickable(text: AnnotatedString) =
        text.getLinkAnnotations(0, text.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()

    @Test
    fun itsChipIsGreyWithNoOutline() =
        show {
            val text = prose().fetchSemanticsNode().config[SemanticsProperties.Text].first()
            // No outline drawn over it (#1458): the paragraph marks no chip for one.
            assertEquals(emptyList<Any>(), text.getStringAnnotations("outline", 0, text.length))
            val links = clickable(text)
            // A link (to the note, #1437), filled grey as the `?` chip is, its text not the line chip's.
            val w1 = checkNotNull(links.single { it.tag == CITE_PREFIX + "W1" }.styles?.style)
            assertEquals(UNBACKED_GREY, w1.background.alpha, 0.01f)
            // The supported sentence's chip keeps its fill; both chips of one size and weight.
            val mb1 = checkNotNull(links.single { it.tag == CITE_PREFIX + "MB1" }.styles?.style)
            assertEquals(true, mb1.background.isSpecified && mb1.background.alpha == 1f)
            assertNotEquals(mb1.color, w1.color)
            assertEquals(mb1.fontWeight to mb1.fontSize, w1.fontWeight to w1.fontSize)
        }

    @Test
    fun theLinesTagInSourcesIsOutlinedToo() =
        show {
            compose
                .onAllNodesWithTag(SOURCES_CHIP_TAG)
                .onFirst()
                .performScrollTo()
                .performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(UNSUPPORTED_TAG_PREFIX + "W1").assertExists()
            assertEquals(0, compose.onAllNodesWithTag(UNSUPPORTED_TAG_PREFIX + "MB1").fetchSemanticsNodes().size)
        }

    @Test
    fun theDeveloperCountsSayUnsupported() =
        show(settings = Settings(developerMode = true, showGroundingDetails = true)) {
            val grounded = app.resources.getQuantityString(R.plurals.home_meta_grounded, 2, 1, 2)
            val unsupported = app.getString(R.string.home_meta_unsupported, 1, "W1")
            assertEquals("1 of 2 sentences grounded · 1 unsupported (W1)", "$grounded · $unsupported")
            compose.onNodeWithText("$grounded · $unsupported").assertExists()
        }

    @Test
    fun readingAloudSaysUnverifiedBeforeIt() {
        val words = SpokenWords("Unverified:") { title, artists -> "$title, by $artists." }
        assertEquals(
            listOf("Unverified: It topped the charts in 1986.", "Sundown was recorded in 1973."),
            spokenSentences(insight.grounded, words),
        )
    }
}
