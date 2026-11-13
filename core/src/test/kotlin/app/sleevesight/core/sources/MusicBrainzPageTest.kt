package app.sleevesight.core.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A page is on MusicBrainz by the host a browser opens (#1245), not by one later in its address. */
class MusicBrainzPageTest {
    @Test
    fun `a page is on MusicBrainz by the host a browser opens`() {
        assertTrue(isMusicBrainzPage("https://musicbrainz.org/recording/1"))
        assertTrue(isMusicBrainzPage("https://musicbrainz.org\\@a.example/x"))
        assertFalse(isMusicBrainzPage("https://a.example#@musicbrainz.org/x"))
    }

    @Test
    fun `a subdomain counts, with a trailing dot too`() {
        assertTrue(isMusicBrainzPage("https://beta.musicbrainz.org./x"))
        assertFalse(isMusicBrainzPage("https://notmusicbrainz.org/x"))
    }

    @Test
    fun `an address that is no web address is no MusicBrainz page`() {
        assertFalse(isMusicBrainzPage("ftp://musicbrainz.org/x"))
    }
}
