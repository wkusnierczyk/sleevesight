package app.sleevesight.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The form the app writes dates in on a phone (#957): English, as the app's text is, in the order
 * and form of the phone's language and region.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneDatesTest {
    private fun locale(tag: String) = dateLocale(Locale.forLanguageTag(tag)).toLanguageTag()

    @Test
    fun `a phone in English keeps its own variety`() {
        assertEquals("en-US", locale("en-US"))
        assertEquals("en-CA", locale("en-CA"))
        assertEquals("en-GB", locale("en-GB"))
        assertEquals("en-AU", locale("en-AU"))
        assertEquals("en-IN", locale("en-IN"))
        assertEquals("en-IE", locale("en-IE"))
    }

    @Test
    fun `a phone in another language gets English in its region, where the platform has it`() {
        assertEquals("en-DE", locale("de-DE"))
        assertEquals("en-CH", locale("de-CH"))
        assertEquals("en-CH", locale("fr-CH"))
        assertEquals("en-US", locale("es-US"))
    }

    @Test
    fun `otherwise international English, never plain English`() {
        assertEquals("en-001", locale("pl-PL"))
        assertEquals("en-001", locale("ar-EG"))
        assertEquals("en-001", locale("en"))
        assertEquals("en-001", locale("de"))
        assertEquals("en-001", dateLocale(Locale.GERMANY, englishRegions = setOf("US")).toLanguageTag())
    }

    @Test
    fun `each form writes the prompts' examples with the phone's patterns`() {
        val examples = { tag: String -> phoneDateForm(Locale.forLanguageTag(tag)).examples }
        assertEquals(listOf("October 17, 2026", "October 2026", "2026"), examples("en-US"))
        assertEquals(listOf("October 17, 2026", "October 2026", "2026"), examples("en-CA"))
        for (tag in listOf("en-GB", "en-AU", "en-IN", "en-IE", "de-DE", "de-CH", "pl-PL")) {
            assertEquals(tag, listOf("17 October 2026", "October 2026", "2026"), examples(tag))
        }
    }
}
