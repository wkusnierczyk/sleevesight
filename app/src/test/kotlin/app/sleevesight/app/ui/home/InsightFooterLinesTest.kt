package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The first line of a written insight's footer (#671), in each case, with and without developer
 * mode (*Show grounding details*): *As written previously.*, with what follows from it on the same
 * line, after *Look again* found nothing new, after it found something new, and with source or
 * model settings changed; alone for an insight kept from before with nothing to follow; nothing for
 * an insight just written. In developer mode the grounding counts follow, without *kept from
 * before*, which the first line says.
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
    )

    private fun show(
        shown: Shown,
        details: Boolean,
        block: () -> Unit,
    ) {
        val insight = HomeFixtures.written("Sundown was recorded in 1973 [MB1].", cached = shown.cached)
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
    // the counts follow, without *kept from before*.
    private fun assertFirstLine(
        line: String,
        shown: Shown,
    ) {
        for (details in listOf(false, true)) {
            show(shown, details) {
                compose.onNodeWithText(line).assertExists()
                assertEquals("developer mode $details: said once", 1, count(BEFORE))
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
            "As written previously. New findings from sources: tap Rewrite insight to use them, a paid call.",
            Shown(InsightOffers(rewrite = true, somethingNew = true)),
        )

    @Test
    fun withSourceOrModelSettingsChanged() =
        assertFirstLine(
            "As written previously. Tap Rewrite insight to write it with the updated source or model settings.",
            Shown(InsightOffers(rewrite = true)),
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
                "New findings from sources: tap Rewrite insight to use them, a paid call.",
            Shown(
                InsightOffers(rewrite = true, somethingNew = true),
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

    private companion object {
        const val BEFORE = "As written previously."
        const val COUNTS = "1 of 1 sentence grounded"
    }
}
