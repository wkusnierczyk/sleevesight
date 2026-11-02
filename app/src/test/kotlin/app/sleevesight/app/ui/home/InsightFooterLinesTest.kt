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
 * The first line of a written insight's footer (#671), in each case, with and without developer
 * mode (*Show grounding details*): *As written previously.*, with what follows from it on the same
 * line, after *Look again* found nothing new, after it found something new, and with source or
 * model settings changed, the offer naming what a rewrite would use (#854); alone for an insight
 * kept from before with nothing to follow; nothing for an insight just written. For an insight
 * *Rewrite insight* wrote, *Rewritten with …* in its place, never both, on the play it was rewritten
 * and on a later one, each part named, in order, joined (#854). In developer mode the grounding
 * counts follow, without *kept from before*, which the first line says.
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
    // the counts follow, without *kept from before*. *As written previously.* is said once, or, for an
    // insight a rewrite wrote, not at all.
    private fun assertFirstLine(
        line: String,
        shown: Shown,
    ) {
        for (details in listOf(false, true)) {
            show(shown, details) {
                compose.onNodeWithText(line).assertExists()
                val before = if (shown.rewritten.isEmpty()) 1 else 0
                assertEquals("developer mode $details: said once", before, count(BEFORE))
                if (details) compose.onNodeWithText(COUNTS).assertExists()
                assertEquals("developer mode $details", 0, count("kept from before"))
                assertEquals("developer mode $details", 0, count("Kept from before"))
            }
        }
    }

    @Test
    fun afterLookAgainFoundNothingNew() =
        assertFirstLine("As written previously. No update from sources.", Shown(InsightOffers(nothingNew = true)))

    @Test
    fun afterLookAgainFoundSomethingNew() =
        assertFirstLine(
            "As written previously. Tap Rewrite insight to have AI rewrite the insight with new findings from sources.",
            Shown(offering(RewritePart.NEW_FINDINGS, somethingNew = true)),
        )

    @Test
    fun withSourceSettingsChanged() =
        assertFirstLine(
            "As written previously. Tap Rewrite insight to have AI rewrite the insight with updated source settings.",
            Shown(offering(RewritePart.SOURCE_SETTINGS)),
        )

    @Test
    fun withTheModelChanged() =
        assertFirstLine(
            "As written previously. Tap Rewrite insight to have AI rewrite the insight with GPT-6.1 Sol high effort.",
            Shown(offering(RewritePart.MODEL, with = SOL_HIGH)),
        )

    // Rewrite offered with nothing the footer names changed: the button alone offers it.
    @Test
    fun withRewriteOfferedAndNothingToName() =
        assertFirstLine("As written previously.", Shown(InsightOffers(rewrite = true)))

    // What a rewrite would use is said only while Rewrite insight is offered.
    @Test
    fun notWhileRewriteIsNotOffered() =
        assertFirstLine(
            "As written previously.",
            Shown(InsightOffers(rewriteWith = RewriteOffer(setOf(RewritePart.MODEL), SOL_HIGH))),
        )

    @Test
    fun rewrittenOnThePlayItWasRewritten() =
        assertFirstLine(
            "Rewritten with Claude Haiku 5.5 high effort.",
            Shown(InsightOffers(), cached = false, rewritten = setOf(RewritePart.MODEL)),
        )

    @Test
    fun rewrittenOnALaterPlay() =
        assertFirstLine(
            "Rewritten with Claude Haiku 5.5 high effort.",
            Shown(InsightOffers(), cached = true, rewritten = setOf(RewritePart.MODEL)),
        )

    // As the picked design's example: what the rewrite used, then what another would use.
    @Test
    fun rewrittenWithRewriteOfferedAgain() =
        assertFirstLine(
            "Rewritten with new findings from sources and Claude Haiku 5.5 high effort. " +
                "Tap Rewrite insight to have AI rewrite the insight with GPT-6.1 Sol high effort.",
            Shown(
                offering(RewritePart.MODEL, with = SOL_HIGH),
                rewritten = setOf(RewritePart.MODEL, RewritePart.NEW_FINDINGS),
            ),
        )

    // In order, joined with commas and a last "and"; the model alone at the service's default effort.
    @Test
    fun severalPartsWithTheDefaultEffort() =
        assertFirstLine(
            "Rewritten with updated source settings, Claude Haiku 5.5 and AI service knowledge switched on.",
            Shown(
                InsightOffers(),
                rewritten = setOf(RewritePart.KNOWLEDGE_ON, RewritePart.MODEL, RewritePart.SOURCE_SETTINGS),
                by = HAIKU_HIGH.copy(effort = null),
            ),
        )

    @Test
    fun knowledgeSwitchedOffAndTheAppsInstructions() =
        assertFirstLine(
            "Rewritten with AI service knowledge switched off and the updated app’s instructions.",
            Shown(InsightOffers(), rewritten = setOf(RewritePart.INSTRUCTIONS, RewritePart.KNOWLEDGE_OFF)),
        )

    // A model no longer listed is named by its id, as the byline names it (#811).
    @Test
    fun aModelNoLongerListedByItsId() =
        assertFirstLine(
            "Rewritten with claude-retired-1 low effort.",
            Shown(
                InsightOffers(),
                rewritten = setOf(RewritePart.MODEL),
                by = HAIKU_HIGH.copy(model = "claude-retired-1", effort = ReasoningEffort.LOW),
            ),
        )

    @Test
    fun aRewriteThatFailedAfterARewrite() =
        assertFirstLine(
            "Rewritten with updated source settings. Rewriting failed: no connection.",
            Shown(
                InsightOffers(),
                cardShown = true,
                rewriteFailed = "offline",
                rewritten = setOf(RewritePart.SOURCE_SETTINGS),
            ),
        )

    @Test
    fun keptFromBeforeWithNothingToFollow() = assertFirstLine("As written previously.", Shown(InsightOffers()))

    // A look again on an insight written in this run: written before the look again.
    @Test
    fun afterLookAgainOnAnInsightJustWritten() =
        assertFirstLine(
            "As written previously. No update from sources.",
            Shown(
                InsightOffers(nothingNew = true),
                cached = false,
            ),
        )

    // The card paid for, shown in place of an insight the run lacks, not from the cache.
    @Test
    fun theCardPaidForShownInPlaceOfTheRunsOwn() =
        assertFirstLine("As written previously.", Shown(InsightOffers(), cached = false, cardShown = true))

    @Test
    fun afterARewriteFailedWithoutRewriteOfferedAgain() =
        assertFirstLine(
            "As written previously. Rewriting failed: no connection.",
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
            "As written previously. Rewriting failed: no connection. " +
                "Tap Rewrite insight to have AI rewrite the insight with new findings from sources.",
            Shown(
                offering(RewritePart.NEW_FINDINGS, somethingNew = true),
                cardShown = true,
                rewriteFailed = "offline",
            ),
        )

    @Test
    fun nothingForAnInsightJustWritten() {
        for (details in listOf(false, true)) {
            show(Shown(InsightOffers(), cached = false), details) {
                assertEquals("developer mode $details", 0, count(BEFORE))
                if (details) compose.onNodeWithText(COUNTS).assertExists()
            }
        }
    }

    // Rewrite insight offered, a rewrite using [part], written with [with].
    private fun offering(
        part: RewritePart,
        with: WrittenBy = HAIKU_HIGH,
        somethingNew: Boolean = false,
    ) = InsightOffers(rewrite = true, somethingNew = somethingNew, rewriteWith = RewriteOffer(setOf(part), with))

    private companion object {
        const val BEFORE = "As written previously."
        const val COUNTS = "1 of 1 sentence grounded"
        val HAIKU_HIGH = WrittenBy(5, ProviderId.ANTHROPIC, "claude-haiku-5-5", ReasoningEffort.HIGH)
        val SOL_HIGH = WrittenBy(5, ProviderId.OPENAI, "gpt-6.1-sol", ReasoningEffort.HIGH)
    }
}
