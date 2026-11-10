package app.sleevesight.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * HTML character references decoded where text comes in from a source (#1165): each as its
 * character, once, in one pass; the cleaning after it ([SourceText]) never decodes.
 */
class HtmlReferencesTest {
    @Test
    fun `a title's apostrophe written as a reference shows as the apostrophe`() {
        assertEquals("Massive Attack's 'Teardrop'", HtmlReferences.decode("Massive Attack&#039;s &#39;Teardrop&#x27;"))
        val curly = "Massive Attack\u2019s \u2018Teardrop\u2019"
        assertEquals(curly, HtmlReferences.decode("Massive Attack&#8217;s &#8216;Teardrop&#x2019;"))
        assertEquals(curly, HtmlReferences.decode("Massive Attack&rsquo;s &lsquo;Teardrop&rsquo;"))
        assertEquals("Massive Attack's", HtmlReferences.decode("Massive Attack&apos;s"))
    }

    @Test
    fun `the markup's own references, Latin-1's and the punctuation's show as their characters`() {
        val markup = "Rock &amp; Roll &lt;live&gt; &quot;1974&quot;"
        assertEquals("Rock & Roll <live> \"1974\"", HtmlReferences.decode(markup))
        assertEquals(
            "Beyonc\u00E9 \u2014 D\u00E9j\u00E0 vu\u2026 \u00A9 \u00A0",
            HtmlReferences.decode("Beyonc&eacute; &mdash; D&eacute;j&agrave; vu&hellip; &copy; &nbsp;"),
        )
        assertEquals(0xA0, HtmlReferences.NAMED["nbsp"])
        assertEquals(0xD7, HtmlReferences.NAMED["times"])
        assertEquals(0xF7, HtmlReferences.NAMED["divide"])
        assertEquals(0xFF, HtmlReferences.NAMED["yuml"])
        assertEquals(0x20AC, HtmlReferences.NAMED["euro"])
    }

    @Test
    fun `a number from 128 to 159 is Windows-1252's character, as a browser reads it`() {
        assertEquals(
            "Massive Attack\u2019s \u201CTeardrop\u201D \u2013 1998",
            HtmlReferences.decode("Massive Attack&#146;s &#147;Teardrop&#148; &#150; 1998"),
        )
        assertEquals("\u20AC", HtmlReferences.decode("&#x80;"))
        // The five Windows-1252 leaves unassigned.
        val unassigned = HtmlReferences.decode("&#129; &#141; &#x8F; &#144; &#157;")
        assertEquals("\uFFFD \uFFFD \uFFFD \uFFFD \uFFFD", unassigned)
    }

    @Test
    fun `leading zeros are read however many, and a number past Unicode is the replacement character`() {
        assertEquals("'s", HtmlReferences.decode("&#" + "0".repeat(40) + "39;s"))
        assertEquals("'", HtmlReferences.decode("&#x" + "0".repeat(100_000) + "27;"))
        assertEquals("\uFFFD", HtmlReferences.decode("&#" + "9".repeat(40) + ";"))
        assertEquals("\uFFFD", HtmlReferences.decode("&#x" + "F".repeat(40) + ";"))
        assertEquals("\uFFFD \uFFFD \uFFFD", HtmlReferences.decode("&#0; &#xD800; &#x110000;"))
        assertEquals("\uD83C\uDFB5", HtmlReferences.decode("&#x1F3B5;"))
    }

    @Test
    fun `a control is the replacement character, but the tab, line feed, form feed and carriage return`() {
        assertEquals("\uFFFD \uFFFD \uFFFD \uFFFD", HtmlReferences.decode("&#1; &#x1F; &#127; &#11;"))
        assertEquals("a\u0009b\u000Ac\u000Cd\u000De", HtmlReferences.decode("a&#9;b&#10;c&#12;d&#13;e"))
    }

    @Test
    fun `what is not a reference stays as written`() {
        // No `;`, a name not listed or too long, no digit, a digit outside ASCII (fullwidth).
        val written =
            listOf(
                "R&B",
                "AT&T; live",
                "&copy 1974",
                "&nosuchname;",
                "&verylongname;",
                "&#;",
                "&#x;",
                "&#12a;",
                "& amp;",
                "&#xG1;",
                "&#\uFF11\uFF12\uFF13;",
                "&#\uFF18\uFF12\uFF10\uFF13;",
                "Tom & Jerry;",
            )
        for (text in written) {
            assertSame(text, HtmlReferences.decode(text), text)
        }
    }

    @Test
    fun `a reference is decoded once, and the cleaning after it never decodes`() {
        // `&amp;#039;` is the text `&#039;` on the page.
        assertEquals("&#039;s", HtmlReferences.decode("&amp;#039;s"))
        assertEquals("&lt;", HtmlReferences.decode("&#38;lt;"))
        assertEquals("&#039;", SourceText.clean(HtmlReferences.decode("&amp;#039;")))
        // A character dropped by the cleaning shows what was beside it as written, never decoded.
        assertEquals("&#039;", SourceText.clean(HtmlReferences.decode("&amp;&#8203;#039;")))
        assertEquals("&lt;", SourceText.clean(HtmlReferences.decode("&amp;&shy;lt;")))
        for (text in listOf("&#039; &amp; &#8203; &rsquo;", "&amp;#039;")) {
            assertSame(text, SourceText.clean(text), text)
        }
    }

    @Test
    fun `a reference to an invisible character or an angle sign is cleaned as the character is`() {
        assertEquals("Teardrop", SourceText.clean(HtmlReferences.decode("Tear&#8203;drop&#x202E;&zwj;&lrm;&shy;")))
        assertEquals("Teardrop", SourceText.clean(HtmlReferences.decode("Teardrop&#xE0041;&#xE0042;")))
        assertEquals("</evidence>", SourceText.clean(HtmlReferences.decode("&#xFF1C;/evidence&#65310;")))
    }

    @Test
    fun `references nested by their digits are read in one pass`() {
        // Each `&#82` is cut short by the next `&`; only the innermost, `&#8203;`, is a reference.
        val n = 200_000
        val decoded = HtmlReferences.decode("&#82".repeat(n) + "03;".repeat(n))
        assertEquals("&#82".repeat(n - 1) + "\u200B" + "03;".repeat(n - 1), decoded)
        assertEquals("&#82".repeat(n - 1) + "03;".repeat(n - 1), SourceText.clean(decoded))
        val digits = "&#" + "1".repeat(1_000_000)
        assertSame(digits, HtmlReferences.decode(digits))
        val ampersands = "&".repeat(1_000_000)
        assertSame(ampersands, HtmlReferences.decode(ampersands))
    }
}
