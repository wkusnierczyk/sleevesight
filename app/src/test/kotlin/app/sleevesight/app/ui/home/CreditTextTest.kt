package app.sleevesight.app.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.pipeline.RewritePart
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The credit line's words (#1162, [creditText]): "Written by {model}." the first time, "Rewritten by
 * {model}." for a rewrite; after "with", the effort when not the service's default, web search, and a
 * rewrite's other reasons, in order, joined with ", " and a last "and"; the model never among them.
 * The picked examples, word for word.
 */
@RunWith(RobolectricTestRunner::class)
class CreditTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun said(credit: Credit) = creditText(credit, resources)

    @Test
    fun writtenAtTheDefaultEffort() = assertEquals("Written by Claude Haiku 5.5.", said(Credit(HAIKU)))

    @Test
    fun writtenWithAnEffortOtherThanTheDefault() =
        assertEquals("Written by Claude Haiku 5.5 with medium effort.", said(Credit(HAIKU, ReasoningEffort.MEDIUM)))

    @Test
    fun rewrittenAfterAModelChangeAtTheDefaultEffort() =
        assertEquals(
            "Rewritten by Claude Haiku 5.5.",
            said(Credit(HAIKU, rewritten = true, parts = setOf(RewritePart.MODEL))),
        )

    @Test
    fun rewrittenWithMediumEffortAndNewFindings() =
        assertEquals(
            "Rewritten by Claude Haiku 5.5 with medium effort and new findings from sources.",
            said(
                Credit(
                    HAIKU,
                    ReasoningEffort.MEDIUM,
                    rewritten = true,
                    parts = setOf(RewritePart.MODEL, RewritePart.NEW_FINDINGS),
                ),
            ),
        )

    // Every reason, in [RewritePart]'s order, whatever order they come in.
    @Test
    fun everyReasonInOrderWithCommasAndALastAnd() =
        assertEquals(
            "Rewritten by Claude Haiku 5.5 with high effort, new findings from sources, updated sources, " +
                "AI service knowledge switched on and the updated app’s instructions.",
            said(
                Credit(
                    HAIKU,
                    ReasoningEffort.HIGH,
                    rewritten = true,
                    parts =
                        setOf(
                            RewritePart.INSTRUCTIONS,
                            RewritePart.KNOWLEDGE_ON,
                            RewritePart.UPDATED_SOURCES,
                            RewritePart.NEW_FINDINGS,
                        ),
                ),
            ),
        )

    @Test
    fun aRewriteForOneReasonAtTheDefaultEffort() =
        assertEquals(
            "Rewritten by Claude Haiku 5.5 with AI service knowledge switched off.",
            said(Credit(HAIKU, rewritten = true, parts = setOf(RewritePart.KNOWLEDGE_OFF))),
        )

    // A genre's reason from web pages (#695): web search joins the same list.
    @Test
    fun webSearchJoinsTheList() {
        assertEquals("Written by Claude Haiku 5.5 with web search.", said(Credit(HAIKU, web = true)))
        assertEquals(
            "Written by Claude Haiku 5.5 with low effort and web search.",
            said(Credit(HAIKU, ReasoningEffort.LOW, web = true)),
        )
    }

    private companion object {
        const val HAIKU = "Claude Haiku 5.5"
    }
}
