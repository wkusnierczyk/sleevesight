package app.sleevesight.sources.wikipedia

import app.sleevesight.sources.language.RoutedNet
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A Wikipedia other than English's (#902): its API's and its articles' host. */
class WikipediaHostTest {
    @Test
    fun `the client asks the Wikipedia of its subdomain, English's by default`() =
        runTest {
            val net = RoutedNet { """{"query":{"pages":[]}}""" }
            WikipediaClient(net).lead("Sundown")
            WikipediaClient(net, "hi").lead("तुम ही हो")
            WikipediaClient(net, "zh-yue").pages(listOf("月亮代表我的心"))
            assertEquals(
                listOf(
                    "https://en.wikipedia.org/w/api.php",
                    "https://hi.wikipedia.org/w/api.php",
                    "https://zh-yue.wikipedia.org/w/api.php",
                ),
                net.sent.map { it.url },
            )
            assertEquals(WikipediaClient.API, WikipediaClient.apiOf("en"))
        }

    @Test
    fun `an article's address is on its Wikipedia's host, its title percent-encoded`() {
        assertEquals("https://en.wikipedia.org/wiki/Why%3F_(Bronski_Beat_song)", articleUrl("Why? (Bronski Beat song)"))
        assertEquals(
            "https://hi.wikipedia.org/wiki/%E0%A4%A4%E0%A5%81%E0%A4%AE_%E0%A4%B9%E0%A5%80_%E0%A4%B9%E0%A5%8B",
            articleUrl("तुम ही हो", "hi"),
        )
    }
}
