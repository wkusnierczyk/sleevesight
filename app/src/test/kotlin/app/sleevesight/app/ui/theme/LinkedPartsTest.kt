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

    @Test
    fun aPlaceholderWithNoLinkOfItsNumberStaysAsWritten() {
        assertEquals(
            listOf(page, TextPart.Words(" and "), TextPart.Words("%3\$s")),
            linkedParts("%1\$s and %3\$s", page, guide),
        )
    }

    @Test
    fun aPlaceholderWithANumberTooLargeToReadStaysAsWrittenRatherThanFail() {
        assertEquals(
            listOf(TextPart.Words("%99999999999\$s"), TextPart.Words(" or "), page),
            linkedParts("%99999999999\$s or %1\$s", page),
        )
    }

    @Test
    fun wordsGivenAsAPartAreNotReadForPlaceholders() {
        val name = TextPart.Words("rock %1\$s")
        assertEquals(
            listOf(TextPart.Words("Not "), name, TextPart.Words(". "), page),
            linkedParts("Not %1\$s. %2\$s", name, page),
        )
    }
}
