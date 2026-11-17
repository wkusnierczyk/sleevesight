package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.RewriteOffer
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.RewritePart
import app.sleevesight.core.pipeline.WrittenBy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first line of a written insight's footer, its credit (#671, #1162), in each case, with and
 * without developer mode (*Show grounding details*): *Written by {model}* with the effort when not
 * the service's default, with what follows from it on the same line, after *Look again* found
 * nothing new, after it found something new, and with source or model settings changed, the offer
 * naming what a rewrite would use (#854); alone for an insight kept from before or just written with
 * nothing to follow. For an insight *Rewrite insight* wrote, *Rewritten by {model}* with the effort
 * and the rewrite's other reasons after "with", in order, joined, on the play it was rewritten and
 * on a later one; never *As written previously.* nor a second *Rewritten with …* line. In developer
 * mode the grounding counts follow, without *kept from before*.
 */
@RunWith(RobolectricTestRunner::class)
class InsightFooterLinesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    // What the card is shown with: the insight from the cache or not, the pipeline's offers, the card
    // paid for shown in place of the run's own insight, and why its rewrite failed.
    private class Shown(
        val offers: InsightOffers,
        val cached: Boolean = true,
        val cardShown: Boolean = false,
        val rewriteFailed: String? = null,
        val rewritten: Set<RewritePart> = emptySet(),
        val by: WrittenBy = HAIKU_HIGH,
    )

    private fun show(
        shown: Shown,
        details: Boolean,
        block: () -> Unit,
    ) {
        val insight =
            HomeFixtures
                .written("Sundown was recorded in 1973 [MB1].", cached = shown.cached)
                .copy(writtenBy = shown.by, rewritten = shown.rewritten)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = shown.offers,
                cardShown = shown.cardShown,
                rewriteFailed = shown.rewriteFailed,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Column {
                            InsightCard(
                                insight,
                                context,
                                InsightActions({}, {}, {}, onReport = { _, _ -> }),
                                groundingDetails = details,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    // Line 1 reads [line] in full, in one text, with and without developer mode; in developer mode
    // the counts follow, without *kept from before*. The model is credited once, and neither *As
    // written previously.* nor *Rewritten with* is said.
    private fun assertFirstLine(
        line: String,
        shown: Shown,
    ) {
        for (details in listOf(false, true)) {
            show(shown, details) {
                compose.onNodeWithText(line).assertExists()
                assertEquals("developer mode $details: credited once", 1, count("ritten by "))
                assertEquals("developer mode $details", 0, count("As written previously"))
                assertEquals("developer mode $details", 0, count("Rewritten with"))
                if (details) compose.onNodeWithText(COUNTS).assertExists()
                assertEquals("developer mode $details", 0, count("kept from before"))
                assertEquals("developer mode $details", 0, count("Kept from before"))
            }
        }
    }

    @Test
    fun afterLookAgainFoundNothingNew() =
        assertFirstLine("$WRITTEN No update from sources.", Shown(InsightOffers(nothingNew = true)))

    @Test
    fun afterLookAgainFoundSomethingNew() =
        assertFirstLine(
            "$WRITTEN Tap Rewrite insight to have AI rewrite the insight with new findings from sources.",
            Shown(offering(RewritePart.NEW_FINDINGS, somethingNew = true)),
        )

    @Test
    fun withTheSourcesUpdated() =
        assertFirstLine(
            "$WRITTEN Tap Rewrite insight to have AI rewrite the insight with updated sources.",
            Shown(offering(RewritePart.UPDATED_SOURCES)),
        )

    @Test
    fun withTheModelChanged() =
        assertFirstLine(
            "$WRITTEN Tap Rewrite insight to have AI rewrite the insight with GPT-6.1 Sol high effort.",
            Shown(offering(RewritePart.MODEL, with = SOL_HIGH)),
        )

    // Rewrite offered with nothing the footer names changed: the button alone offers it.
    @Test
    fun withRewriteOfferedAndNothingToName() = assertFirstLine(WRITTEN, Shown(InsightOffers(rewrite = true)))

    // What a rewrite would use is said only while Rewrite insight is offered.
    @Test
    fun notWhileRewriteIsNotOffered() =
        assertFirstLine(
            WRITTEN,
            Shown(InsightOffers(rewriteWith = RewriteOffer(setOf(RewritePart.MODEL), SOL_HIGH))),
        )

    @Test
    fun rewrittenOnThePlayItWasRewritten() =
        assertFirstLine(
            "Rewritten by Claude Haiku 5.5 with high effort.",
            Shown(InsightOffers(), cached = false, rewritten = setOf(RewritePart.MODEL)),
        )

    @Test
    fun rewrittenOnALaterPlay() =
        assertFirstLine(
            "Rewritten by Claude Haiku 5.5 with high effort.",
            Shown(InsightOffers(), cached = true, rewritten = setOf(RewritePart.MODEL)),
        )

    // As the picked design's example: who rewrote it and with what, then what another would use.
    @Test
    fun rewrittenWithRewriteOfferedAgain() =
        assertFirstLine(
            "Rewritten by Claude Haiku 5.5 with high effort and new findings from sources. " +
                "Tap Rewrite insight to have AI rewrite the insight with GPT-6.1 Sol high effort.",
            Shown(
                offering(RewritePart.MODEL, with = SOL_HIGH),
                rewritten = setOf(RewritePart.MODEL, RewritePart.NEW_FINDINGS),
            ),
        )

    // In order, joined with commas and a last "and"; no effort at the service's default.
    @Test
    fun severalPartsWithTheDefaultEffort() =
        assertFirstLine(
            "Rewritten by Claude Haiku 5.5 with updated sources and AI service knowledge switched on.",
            Shown(
                InsightOffers(),
                rewritten = setOf(RewritePart.KNOWLEDGE_ON, RewritePart.MODEL, RewritePart.UPDATED_SOURCES),
                by = HAIKU_HIGH.copy(effort = null),
            ),
        )

    @Test
    fun knowledgeSwitchedOffAndTheAppsInstructions() =
        assertFirstLine(
            "Rewritten by Claude Haiku 5.5 with high effort, AI service knowledge switched off and the " +
                "updated app’s instructions.",
            Shown(InsightOffers(), rewritten = setOf(RewritePart.INSTRUCTIONS, RewritePart.KNOWLEDGE_OFF)),
        )

    // A model no longer listed is named by its id (#811).
    @Test
    fun aModelNoLongerListedByItsId() =
        assertFirstLine(
            "Rewritten by claude-retired-1 with medium effort.",
            Shown(
                InsightOffers(),
                rewritten = setOf(RewritePart.MODEL),
                by = HAIKU_HIGH.copy(model = "claude-retired-1", effort = ReasoningEffort.MEDIUM),
            ),
        )

    @Test
    fun aRewriteThatFailedAfterARewrite() =
        assertFirstLine(
            "Rewritten by Claude Haiku 5.5 with high effort and updated sources. Rewriting failed: no connection.",
            Shown(
                InsightOffers(),
                cardShown = true,
                rewriteFailed = "offline",
                rewritten = setOf(RewritePart.UPDATED_SOURCES),
            ),
        )

    @Test
    fun keptFromBeforeWithNothingToFollow() = assertFirstLine(WRITTEN, Shown(InsightOffers()))

    // A look again on an insight written in this run: written before the look again.
    @Test
    fun afterLookAgainOnAnInsightJustWritten() =
        assertFirstLine(
            "$WRITTEN No update from sources.",
            Shown(
                InsightOffers(nothingNew = true),
                cached = false,
            ),
        )

    // The card paid for, shown in place of an insight the run lacks, not from the cache.
    @Test
    fun theCardPaidForShownInPlaceOfTheRunsOwn() =
        assertFirstLine(WRITTEN, Shown(InsightOffers(), cached = false, cardShown = true))

    @Test
    fun afterARewriteFailedWithoutRewriteOfferedAgain() =
        assertFirstLine(
            "$WRITTEN Rewriting failed: no connection.",
            Shown(
                InsightOffers(),
                cached = false,
                cardShown = true,
                rewriteFailed = "offline",
            ),
        )

    // In the order things happened: the rewrite that failed, then what a later look again found.
    @Test
    fun aRewriteThatFailedIsSaidBeforeWhatALaterLookAgainFound() =
        assertFirstLine(
            "$WRITTEN Rewriting failed: no connection. " +
                "Tap Rewrite insight to have AI rewrite the insight with new findings from sources.",
            Shown(
                offering(RewritePart.NEW_FINDINGS, somethingNew = true),
                cardShown = true,
                rewriteFailed = "offline",
            ),
        )

    // Written just now, with nothing to follow: the credit alone, as for one kept (#1162).
    @Test
    fun theCreditAloneForAnInsightJustWritten() = assertFirstLine(WRITTEN, Shown(InsightOffers(), cached = false))

    // At the service's default effort, the model alone.
    @Test
    fun theModelAloneAtTheDefaultEffort() {
        show(Shown(InsightOffers(), by = HAIKU_HIGH.copy(effort = null)), details = false) {
            compose.onNodeWithText("Written by Claude Haiku 5.5.").assertExists()
        }
    }

    // Rewrite insight offered, a rewrite using [part], written with [with].
    private fun offering(
        part: RewritePart,
        with: WrittenBy = HAIKU_HIGH,
        somethingNew: Boolean = false,
    ) = InsightOffers(rewrite = true, somethingNew = somethingNew, rewriteWith = RewriteOffer(setOf(part), with))

    private companion object {
        const val WRITTEN = "Written by Claude Haiku 5.5 with high effort."
        const val COUNTS = "1 of 1 sentence grounded"
        val HAIKU_HIGH = WrittenBy(5, ProviderId.ANTHROPIC, "claude-haiku-5-5", ReasoningEffort.HIGH)
        val SOL_HIGH = WrittenBy(5, ProviderId.OPENAI, "gpt-6.1-sol", ReasoningEffort.HIGH)
    }
}
