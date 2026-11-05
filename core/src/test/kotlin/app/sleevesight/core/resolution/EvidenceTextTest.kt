package app.sleevesight.core.resolution

import kotlin.test.Test
import kotlin.test.assertEquals

class EvidenceTextTest {
    @Test
    fun `dates read as they did before one formatter wrote them (#957)`() {
        // Byte for byte: the evidence lines are part of every card's key (the evidence fingerprint),
        // so a change here would write every kept card afresh, a paid call each.
        assertEquals("on 25 March 1974", EvidenceText.date("1974-03-25"))
        assertEquals("on 6 November 1964", EvidenceText.date("1964-11-06"))
        assertEquals("in January 1974", EvidenceText.date("1974-01"))
        assertEquals("in 1974", EvidenceText.date("1974"))
        // A month or day of `00` counts as absent, as Wikidata writes an unknown one.
        assertEquals("in March 1974", EvidenceText.date("1974-03-00"))
        assertEquals("in 1974", EvidenceText.date("1974-00-00"))
        assertEquals("in 1974", EvidenceText.date("1974-00-25"))
        // A date with no year is said as given.
        assertEquals("in 19", EvidenceText.date("19"))
        assertEquals("in ", EvidenceText.date(""))
    }
}
