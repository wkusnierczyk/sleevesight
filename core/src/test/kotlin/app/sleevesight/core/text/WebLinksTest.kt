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
}
