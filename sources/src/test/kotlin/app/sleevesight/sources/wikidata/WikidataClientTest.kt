package app.sleevesight.sources.wikidata

import kotlin.test.Test
import kotlin.test.assertEquals

class WikidataClientTest {
    @Test
    fun `a time keeps only the precision Wikidata gives it`() {
        assertEquals("+1964-00-00T00:00:00Z", WikidataClient.atPrecision("+1964-01-01T00:00:00Z", 9))
        assertEquals("+1978-09-00T00:00:00Z", WikidataClient.atPrecision("+1978-09-01T00:00:00Z", 10))
        assertEquals("+1974-03-25T00:00:00Z", WikidataClient.atPrecision("+1974-03-25T00:00:00Z", 11))
        assertEquals("", WikidataClient.atPrecision("+1960-01-01T00:00:00Z", 8))
    }
}
