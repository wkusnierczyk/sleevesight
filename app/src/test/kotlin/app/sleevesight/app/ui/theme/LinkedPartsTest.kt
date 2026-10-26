package app.sleevesight.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** A string resource's placeholders as links ([linkedParts], #541). */
class LinkedPartsTest {
    private val page = TextPart.Link("sleevesight.app/api-keys", "https://sleevesight.app/api-keys")
    private val guide = TextPart.Link("the guide", "https://sleevesight.app/docs/")

    @Test
    fun eachPlaceholderBecomesItsLinkAndTheWordsAroundThemStay() {
        assertEquals(
            listOf(TextPart.Words("Make it at "), page, TextPart.Words(". More in "), guide, TextPart.Words(".")),
            linkedParts("Make it at %1\$s. More in %2\$s.", page, guide),
        )
    }

    @Test
    fun theLinksGoWhereTheirNumbersAreNotInTheOrderGiven() {
        assertEquals(
            listOf(guide, TextPart.Words(", then "), page),
            linkedParts("%2\$s, then %1\$s", page, guide),
        )
    }
}
