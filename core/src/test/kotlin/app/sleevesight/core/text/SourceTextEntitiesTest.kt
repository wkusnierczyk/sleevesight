package app.sleevesight.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Text from pages and search engines with its HTML character references decoded (#1165): each shown
 * as its character, once, and before the invisible characters are dropped, so a reference cannot
 * bring one in.
 */
class SourceTextEntitiesTest {
    @Test
    fun `a title's apostrophe written as a reference shows as the apostrophe`() {
        assertEquals("Massive Attack's 'Teardrop'", SourceText.titleOf("Massive Attack&#039;s &#39;Teardrop&#x27;"))
        val curly = "Massive Attack\u2019s \u2018Teardrop\u2019"
        assertEquals(curly, SourceText.titleOf("Massive Attack&#8217;s &#8216;Teardrop&#x2019;"))
        assertEquals(curly, SourceText.titleOf("Massive Attack&rsquo;s &lsquo;Teardrop&rsquo;"))
        assertEquals("Massive Attack's", SourceText.titleOf("Massive Attack&apos;s"))
    }

    @Test
    fun `the markup's own references, Latin-1's and the punctuation's show as their characters`() {
        assertEquals("Rock & Roll <live> \"1974\"", SourceText.clean("Rock &amp; Roll &lt;live&gt; &quot;1974&quot;"))
        assertEquals(
            "Beyonc\u00E9 \u2014 D\u00E9j\u00E0 vu\u2026 \u00A9 \u00A0",
            SourceText.clean("Beyonc&eacute; &mdash; D&eacute;j&agrave; vu&hellip; &copy; &nbsp;"),
        )
        assertEquals("\u00A0", SourceText.NAMED["nbsp"])
        assertEquals("\u00D7", SourceText.NAMED["times"])
        assertEquals("\u00F7", SourceText.NAMED["divide"])
        assertEquals("\u00FF", SourceText.NAMED["yuml"])
        assertEquals("\u20AC", SourceText.NAMED["euro"])
    }

    @Test
    fun `a number from 128 to 159 is Windows-1252's character, as a browser reads it`() {
        assertEquals(
            "Massive Attack\u2019s \u201CTeardrop\u201D \u2013 1998",
            SourceText.clean("Massive Attack&#146;s &#147;Teardrop&#148; &#150; 1998"),
        )
        assertEquals("\u20AC \u0081", SourceText.clean("&#x80; &#129;"))
    }

    @Test
    fun `a number that is no character is the replacement character`() {
        assertEquals("\uFFFD \uFFFD \uFFFD \uFFFD", SourceText.clean("&#0; &#xD800; &#x110000; &#99999999999;"))
        assertEquals("\uD83C\uDFB5", SourceText.clean("&#x1F3B5;"))
    }

    @Test
    fun `what is not a reference stays as written`() {
        val written = listOf("R&B", "AT&T; live", "&copy 1974", "&nosuchname;", "&#;", "&#x;", "&#12a;", "& amp;")
        for (text in written + "Tom & Jerry;") {
            assertSame(text, SourceText.clean(text), text)
        }
    }

    @Test
    fun `a reference to an invisible character or an angle sign is cleaned as the character is`() {
        assertEquals("Teardrop", SourceText.clean("Tear&#8203;drop&#x202E;&zwj;&lrm;&shy;"))
        assertEquals("Teardrop", SourceText.clean("Teardrop&#xE0041;&#xE0042;"))
        assertEquals("</evidence>", SourceText.clean("&#xFF1C;/evidence&#65310;"))
        assertNull(SourceText.titleOf("&#8203; &#x2060;"))
    }

    @Test
    fun `a reference is decoded once, never again from what decoding gave`() {
        // `&amp;#039;` is the text `&#039;` on the page: its `&` stays written, so no round makes it `'`.
        assertEquals("&amp;#039;s", SourceText.clean("&amp;#039;s"))
        assertEquals("&#38;lt;", SourceText.clean("&#38;lt;"))
        assertEquals("&amp;amp;", SourceText.clean("&amp;amp;"))
        // A character a reference is written with stays written, so it completes no other.
        assertEquals("&&#35;039;", SourceText.clean("&#38;&#35;039;"))
        assertEquals("&lt&#59; &#65;", SourceText.clean("&lt&#59; &#65;"))
        // An `&` before what is no reference is decoded.
        assertEquals("Rock & Roll & #1", SourceText.clean("Rock &amp; Roll &amp; #1"))
    }

    @Test
    fun `cleaning twice changes nothing more, references included`() {
        for (text in listOf(
            "Massive Attack&#039;s &rsquo;Teardrop&#8217;",
            "&amp;#039;",
            "&\u200B#039;",
            "&amp;\u200B#039;",
            "&\u200Bamp;#8203;",
            "&#38;&#35;039;",
            "&#x26;lt;",
            "a&#8203;&#8205;b",
            "\u0645&zwj;\u06CC &#xFF1C;&#8205;&#xFF1E;",
        )) {
            val once = SourceText.clean(text)
            assertEquals(once, SourceText.clean(once), text)
        }
        // An invisible character inside a reference: dropped, and the reference then read as shown.
        assertEquals("'", SourceText.clean("&\u200B#039;"))
        assertEquals("&amp;#8203;", SourceText.clean("&\u200Bamp;#8203;"))
    }
}
