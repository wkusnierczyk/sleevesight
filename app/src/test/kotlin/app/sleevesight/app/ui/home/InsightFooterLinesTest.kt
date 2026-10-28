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

    private fun show(
        cached: Boolean,
        offers: InsightOffers,
        details: Boolean,
        block: () -> Unit,
    ) {
        val insight = HomeFixtures.written("Sundown was recorded in 1973 [MB1].", cached = cached)
        val context =
            InsightContext(
                HomeFixtures.track(insight = insight),
                aiReady = true,
                insightsOn = true,
                modelAllowed = false,
                offers = offers,
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
        offers: InsightOffers,
        cached: Boolean = true,
    ) {
        for (details in listOf(false, true)) {
            show(cached, offers, details) {
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
        assertFirstLine("As written previously. No update from sources.", InsightOffers(nothingNew = true))

    @Test
    fun afterLookAgainFoundSomethingNew() =
        assertFirstLine(
            "As written previously. New findings from sources: tap Rewrite insight to use them, a paid call.",
            InsightOffers(rewrite = true, somethingNew = true),
        )

    @Test
    fun withSourceOrModelSettingsChanged() =
        assertFirstLine(
            "As written previously. Tap Rewrite insight to write it with the updated source or model settings.",
            InsightOffers(rewrite = true),
        )

    @Test
    fun keptFromBeforeWithNothingToFollow() = assertFirstLine("As written previously.", InsightOffers())

    // A look again on an insight written in this run: written before the look again.
    @Test
    fun afterLookAgainOnAnInsightJustWritten() =
        assertFirstLine(
            "As written previously. No update from sources.",
            InsightOffers(nothingNew = true),
            cached = false,
        )

    @Test
    fun nothingForAnInsightJustWritten() {
        for (details in listOf(false, true)) {
            show(cached = false, offers = InsightOffers(), details = details) {
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
