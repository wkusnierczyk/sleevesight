package io.github.wkusnierczyk.sleevesight.sources.lastfm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LastFmBioTest {
    @Test
    fun `tags, the Read more link and the licence note go, entities are decoded`() {
        val html =
            "Ann &amp; Bob&#39;s band &#x2014; &quot;live&quot;. " +
                "<a href=\"https://www.last.fm/music/X\">Read more on Last.fm</a> User-contributed text is " +
                "available under the Creative Commons By-SA License; additional terms may apply."
        assertEquals("Ann & Bob's band — \"live\".", LastFmBio.plainText(html))
    }

    @Test
    fun `sentences split at a full stop before a capital, not at initials or abbreviations`() {
        assertEquals(
            listOf(
                "J. R. Smith and Dr. Jones met in St. John's in 1970.",
                "They played folk.",
                "Did it last?",
                "\"Yes,\" they said.",
                "Their 2nd album sold 1.5 million copies.",
            ),
            LastFmBio.split(
                "J. R. Smith and Dr. Jones met in St. John's in 1970. They played folk. Did it last? " +
                    "\"Yes,\" they said. Their 2nd album sold 1.5 million copies.",
            ),
        )
    }

    @Test
    fun `at most three sentences of a quotable length, in order, never cut`() {
        val long = "A".repeat(LastFmBio.MAX_LENGTH) + " is too long."
        val summary =
            "Short one. The first sentence is long enough. $long The second one is long enough too. " +
                "The third one is long enough as well. The fourth one is not quoted."
        assertEquals(
            listOf(
                "The first sentence is long enough.",
                "The second one is long enough too.",
                "The third one is long enough as well.",
            ),
            LastFmBio.sentences(summary),
        )
    }

    @Test
    fun `a disambiguation bio gives no sentence`() {
        for (summary in listOf(
            "There are several artists named Reem. 1) A rapper from Denmark.",
            "There are at least 3 artists with this name: 1) a band.",
            "there is more than one artist with this name.",
        )) {
            assertTrue(LastFmBio.sentences(summary).isEmpty(), summary)
        }
    }

    @Test
    fun `a summary cut off mid-sentence loses its tail`() {
        val summary =
            "A complete first sentence here. And then the band rele " +
                "<a href=\"https://www.last.fm/music/X\">Read more on Last.fm</a>"
        assertEquals(listOf("A complete first sentence here."), LastFmBio.sentences(summary))
        assertEquals(
            listOf("A complete first sentence here.", "A complete last sentence, \"quoted.\""),
            LastFmBio.split("A complete first sentence here. A complete last sentence, \"quoted.\""),
        )
        assertEquals(
            listOf("A complete first sentence here."),
            LastFmBio.sentences("A complete first sentence here. Then they toured Europe and..."),
        )
    }

    @Test
    fun `a page several artists share is recognised in each form`() {
        for (summary in listOf(
            "There are several artists named Reem. 1) A rapper.",
            "Multiple artists share this name. A rapper from Denmark.",
            "1) Reem is a rapper from Denmark. 2) Reem is a singer.",
            "1. Reem is a rapper from Denmark. 2. Reem is a singer.",
        )) {
            assertTrue(LastFmBio.sharedPage(summary), summary)
            assertTrue(LastFmBio.sentences(summary).isEmpty(), summary)
        }
        assertFalse(LastFmBio.sharedPage("Their album sold 1.5 million copies in 2001. It went 2. place."))
    }

    @Test
    fun `an empty bio gives no sentence`() {
        assertTrue(LastFmBio.sentences(null).isEmpty())
        assertTrue(LastFmBio.sentences(" <a href=\"https://www.last.fm/music/X\">Read more on Last.fm</a>").isEmpty())
    }
}
