package app.sleevesight.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Only `http` and `https` addresses are web links the app opens (#1040). */
class WebLinksTest {
    @Test
    fun `http and https addresses are kept as they are`() {
        assertEquals("https://www.last.fm/music/Cher", WebLinks.of("https://www.last.fm/music/Cher"))
        assertEquals("http://example.com", WebLinks.of("http://example.com"))
        assertEquals("https://example.com:8443/x", WebLinks.of("https://example.com:8443/x"))
        assertEquals("https://example.com/", WebLinks.of("  https://example.com/\n"))
    }

    @Test
    fun `the scheme is given in lower case, the rest as it is`() {
        // Android matches an intent's scheme case-sensitively: a browser takes `https`, not `HTTPS`.
        assertEquals("https://Example.com/A?b=1#c", WebLinks.of("HTTPS://Example.com/A?b=1#c"))
        assertEquals("http://Example.com/HTTPS://x", WebLinks.of("HtTp://Example.com/HTTPS://x"))
    }

    @Test
    fun `another scheme is dropped`() {
        val others =
            listOf(
                "intent://scan/#Intent;scheme=zxing;package=com.example;end",
                "javascript:alert('https://example.com')",
                "file:///sdcard/secret.txt",
                "content://com.example.provider/data",
                "market://details?id=com.example",
                "tel:+15550100",
                "mailto:someone@example.com",
                "ftp://example.com/file",
                "data:text/html,<b>hi</b>",
                "sleevesight://settings",
            )
        for (url in others) assertNull(WebLinks.of(url), url)
    }

    @Test
    fun `an address with no scheme, no host or white space inside is dropped`() {
        val broken =
            listOf(
                null,
                "",
                "   ",
                "www.example.com",
                "/music/Cher",
                "https://",
                "https:///path",
                "https://user@/path",
                "https://exa mple.com",
                "https://example.com/\u0000",
                "https:example.com",
            )
        for (url in broken) assertNull(WebLinks.of(url), url.toString())
    }

    @Test
    fun `an address with a backslash or user info is dropped`() {
        // Browsers read these hosts differently from how they look (#1245).
        val odd =
            listOf(
                "https://a.example\\@b.example/x",
                "https://a.example\\b.example/x",
                "https://a.example/x\\y",
                "https://user@b.example/x",
                "https://a@b@c.example/x",
                "https://user:pass@b.example",
                "https://[::1",
                "https://ex%61mple.com/",
            )
        for (url in odd) assertNull(WebLinks.of(url), url)
        // An `@` past the authority is no user info.
        assertEquals("https://a.example/x@y", WebLinks.of("https://a.example/x@y"))
        assertEquals("https://a.example?q=@b.example", WebLinks.of("https://a.example?q=@b.example"))
        assertEquals("https://a.example#@b.example/x", WebLinks.of("https://a.example#@b.example/x"))
    }

    @Test
    fun `the host is read as browsers read it`() {
        assertEquals("a.example", WebLinks.host("https://a.example#@b.example/x"))
        assertEquals("a.example", WebLinks.host("https://a.example?@b.example/x"))
        assertEquals("a.example", WebLinks.host("https://a.example\\@b.example/x"))
        assertEquals("c.example", WebLinks.host("https://a@b@c.example/x"))
        assertEquals("b.example", WebLinks.host("https://user:pass@b.example:8443/x"))
        assertEquals("example.com", WebLinks.host("https://example.com:8443/x"))
        assertEquals("example.com", WebLinks.host("HTTPS://EXAMPLE.Com/A"))
        assertEquals("[::1]", WebLinks.host("http://[::1]:8080/x"))
        assertEquals("example.com", WebLinks.host("https:\\\\example.com\\x"))
    }

    @Test
    fun `an ordinary address's host is its host`() {
        assertEquals("en.wikipedia.org", WebLinks.host("https://en.wikipedia.org/wiki/Sundown"))
        assertEquals("www.wikidata.org", WebLinks.host("https://www.wikidata.org/wiki/Q1?x=1"))
        assertEquals("www.last.fm", WebLinks.host("https://www.last.fm/music/Cher"))
        assertEquals("example.com", WebLinks.host("http://example.com"))
        assertEquals("example.com", WebLinks.host("https://example.com?x=1"))
        assertEquals("example.com", WebLinks.host("https://example.com#top"))
    }

    @Test
    fun `an address that is no web address has no host`() {
        val none =
            listOf(
                null,
                "",
                "not a url",
                "ftp://example.com/file",
                "javascript:alert('https://example.com')",
                "https://",
                "https://:8080/x",
                "https://exa mple.com",
                "https://[]/x",
                "https://ex%61mple.com/",
            )
        for (url in none) assertNull(WebLinks.host(url), url.toString())
    }
}
