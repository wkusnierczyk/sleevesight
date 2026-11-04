package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.RewriteOffer
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.WrittenBy
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The song's, the artist's and the album's insights written by another model (#911), as the key
 * insight's: the insight kept, "As written previously." with what *Rewrite insight* would rewrite it
 * with, and *Rewrite insight*, read "Rewrite insight, uses AI"; a rewrite stopped or failed says so
 * after *As written previously.*; one a rewrite wrote says "Rewritten with …"; a model found
 * unavailable is said last, above a disabled *Write insight*.
 */
@RunWith(RobolectricTestRunner::class)
class ItemRewriteScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val asks = mutableListOf<ItemAsk>()

    // The song's insight shown as [shown]; every other item offered.
    private fun notes(shown: ItemShown) =
        object : ItemNotes {
            override fun item(
                track: TrackState,
                item: InsightItem,
            ): Flow<ItemShown> = flowOf(if (item == InsightItem.SONG) shown else ItemShown.Offered)

            override fun write(
                track: TrackState,
                item: InsightItem,
                ask: ItemAsk,
            ) {
                asks += ask
            }
        }

    private val earlier =
        written("Sundown was recorded in Toronto [MB1].", cached = true)
            .copy(writtenBy = WrittenBy(1, ProviderId.ANTHROPIC, FIRST, null))

    private val rewrite = RewriteOffer(setOf(RewritePart.MODEL), WrittenBy(1, ProviderId.ANTHROPIC, OTHER, null))

    private fun earlierShown(failed: String? = null) =
        ItemShown.Shown(
            earlier,
            ItemEvidence(evidence, emptyMap()),
            FIRST,
            rewriteWith = rewrite,
            rewriteFailed = failed,
        )

    private fun show(
        shown: ItemShown,
        unavailable: UnavailableModel? = null,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(), aiReady = true),
                HomeFixtures.sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
                unavailable = unavailable,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize, itemNotes = notes(shown)))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private val written = text(R.string.home_written_before)
    private val offer = app.getString(R.string.home_rewrite_offer, OTHER)

    private fun rewriteButton() = compose.onNodeWithTag(itemWriteTag(InsightItem.SONG)).performScrollTo()

    @Test
    fun anInsightAnotherModelWroteSaysSoAndRewriteInsightRewritesItOnATap() =
        show(earlierShown()) {
            compose.onNodeWithText("by $FIRST").assertExists()
            compose.onNodeWithText("$written $offer").performScrollTo()
            val button = rewriteButton().assertIsEnabled().assertTextContains(text(R.string.home_rewrite))
            assertEquals(
                app.getString(R.string.home_uses_ai, text(R.string.home_rewrite)),
                button.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
            )
            compose
                .onNode(
                    hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(itemWriteTag(InsightItem.SONG))),
                    useUnmergedTree = true,
                ).assertExists()
            button.performClick()
            assertEquals(listOf(ItemAsk.TAP), asks)
        }

    @Test
    fun theHistoryLineComesBeforeTheGroundingNoteAndReportAProblem() =
        show(earlierShown()) {
            val history = compose.onNodeWithText("$written $offer").performScrollTo()
            val report = compose.onNodeWithTag(REPORT_TAG)
            val button = rewriteButton()
            val tops = listOf(history, report, button).map { it.fetchSemanticsNode().positionInRoot.y }
            assertEquals(tops.sorted(), tops)
        }

    @Test
    fun aRewriteStoppedSaysSoAfterAsWrittenPreviouslyWithRewriteAgain() =
        show(earlierShown(PipelineHost.STOPPED)) {
            compose.onNodeWithText("$written ${text(R.string.home_rewrite_stopped)} $offer").performScrollTo()
            rewriteButton().assertIsEnabled().assertTextContains(text(R.string.home_rewrite))
        }

    @Test
    fun aRewriteFailedSaysWhyAfterAsWrittenPreviouslyWithRewriteAgain() =
        show(earlierShown("boom")) {
            val failed = app.getString(R.string.home_rewrite_failed, app.getString(R.string.home_failure_other, "boom"))
            compose.onNodeWithText("$written $failed $offer").performScrollTo()
            rewriteButton().assertIsEnabled()
        }

    @Test
    fun withNothingThatMayWriteTheInsightKeptShowsWithADisabledWriteInsightAndNoOffer() =
        show(earlierShown().copy(canWrite = false)) {
            compose.onNodeWithText(written).performScrollTo()
            compose.onAllNodesWithText(offer, substring = true).assertCountEquals(0)
            rewriteButton().assertIsNotEnabled().assertTextContains(text(R.string.home_write))
        }

    @Test
    fun anInsightARewriteWroteSaysRewrittenWithItsModelAndOffersNothing() {
        val rewritten =
            written("Sundown was recorded in Toronto [MB1].")
                .copy(writtenBy = WrittenBy(1, ProviderId.ANTHROPIC, OTHER, null), rewritten = setOf(RewritePart.MODEL))
        show(ItemShown.Shown(rewritten, ItemEvidence(evidence, emptyMap()), OTHER)) {
            compose.onNodeWithText(app.getString(R.string.home_rewritten_with, OTHER)).performScrollTo()
            compose.onAllNodesWithText(written, substring = true).assertCountEquals(0)
            compose.onNodeWithTag(itemWriteTag(InsightItem.SONG)).assertDoesNotExist()
        }
    }

    @Test
    fun anItemsOwnInsightHasNoHistoryLine() {
        val own = written("Sundown was recorded in Toronto [MB1].", cached = true)
        show(ItemShown.Shown(own, ItemEvidence(evidence, emptyMap()), OTHER)) {
            compose.onAllNodesWithText(written, substring = true).assertCountEquals(0)
            compose.onNodeWithTag(itemWriteTag(InsightItem.SONG)).assertDoesNotExist()
        }
    }

    @Test
    fun withTheModelUnavailableItIsSaidLastAboveADisabledWriteInsight() =
        show(earlierShown(), UnavailableModel(ProviderId.ANTHROPIC, OTHER)) {
            // No offer: a rewrite would fail.
            val history = compose.onNodeWithText(written).performScrollTo()
            val report = compose.onNodeWithTag(REPORT_TAG)
            // The key insight says it too, above: the item's is the last.
            val line =
                compose
                    .onAllNodesWithTag(MODEL_UNAVAILABLE_TAG)
                    .fetchSemanticsNodes()
                    .maxBy { it.positionInRoot.y }
            val button = rewriteButton().assertIsNotEnabled().assertTextContains(text(R.string.home_write))
            val tops =
                listOf(
                    history.fetchSemanticsNode().positionInRoot.y,
                    report.fetchSemanticsNode().positionInRoot.y,
                    line.positionInRoot.y,
                    button.fetchSemanticsNode().positionInRoot.y,
                )
            assertEquals(tops.sorted(), tops)
        }

    private companion object {
        const val FIRST = "model-first"
        const val OTHER = "model-other"
    }
}
