package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
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
 * A checked insight keeps the reply's line breaks between its sentences (#574): a list's items and
 * a heading, which the grounding check reads as sentences of their own, stay on their own lines,
 * their marks taken out (#555), rather than run on as prose; sentences the reply ran on stay on one
 * line.
 */
@RunWith(RobolectricTestRunner::class)
class InsightLineBreaksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        insight: InsightState.Written,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(insight = insight), aiReady = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
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

    // A citation's chip as the card's text holds it, its id between thin spaces.
    private fun chip(id: String) = "\u2009$id\u2009"

    // The text of the paragraph that holds [part], its citations as their chips' labels.
    private fun paragraph(part: String): String =
        compose
            .onNodeWithText(part, substring = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    @Test
    fun aListsItemsStayOnTheirOwnLines() =
        show(written("- It was recorded [MB1].\n- It topped the charts [W1].\n- It is great.")) {
            assertEquals(
                "It was recorded ${chip("MB1")}.\nIt topped the charts ${chip("W1")}.\nIt is great.",
                paragraph("It was recorded"),
            )
        }

    @Test
    fun aHeadingAndTheSentencesAfterItKeepTheirLines() =
        show(written("# Sundown\nIt was recorded [MB1].\n## Reception\nIt topped the charts [W1].")) {
            assertEquals(
                "Sundown\nIt was recorded ${chip("MB1")}.\nReception\nIt topped the charts ${chip("W1")}.",
                paragraph("It was recorded"),
            )
        }

    @Test
    fun sentencesTheReplyRanOnStayOnOneLine() =
        show(written("It was recorded [MB1]. It topped the charts [W1].\nIt is a single [WD1].")) {
            assertEquals(
                "It was recorded ${chip("MB1")}. It topped the charts ${chip("W1")}.\nIt is a single ${chip("WD1")}.",
                paragraph("It was recorded"),
            )
        }
}
