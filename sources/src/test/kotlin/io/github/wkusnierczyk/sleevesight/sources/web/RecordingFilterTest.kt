package io.github.wkusnierczyk.sleevesight.sources.web

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingFilterTest {
    private val filter = RecordingFilter(JvmAccentFolder)

    private fun track(
        title: String,
        vararg artists: String,
    ) = NormalizedTrack(title, artists.toList(), artists.first(), null, null, emptyList(), "")

    private fun about(
        track: NormalizedTrack,
        title: String?,
        snippet: String = "",
        url: String = "https://example.com/page",
    ) = filter.isAbout(track, title, snippet, url)

    @Test
    fun `folds case, accents and punctuation`() {
        assertEquals("fishin in a dory", filter.fold("  Fishin' In A Dory! "))
        assertEquals("charlie dee", filter.fold("Charlie Dée"))
        assertEquals("ispini na heireann", filter.fold("Ispíní na hÉireann"))
        assertEquals("東京 2020", filter.fold("東京・2020"))
    }

    @Test
    fun `needs the title and a credited artist, from the title or the snippet`() {
        val sundown = track("Sundown", "Gordon Lightfoot")
        assertTrue(about(sundown, "Sundown | Spotify", "a song by GORDON LIGHTFOOT"))
        assertTrue(about(sundown, null, "Gordon Lightfoot - Sundown (Official Audio)"))
        assertFalse(about(sundown, "Sundown | Spotify", "a song by someone else"))
        assertFalse(about(sundown, "Gordon Lightfoot", "If You Could Read My Mind"))
    }

    @Test
    fun `matches whole words only`() {
        val sun = track("Sun", "Gordon Lightfoot")
        assertFalse(about(sun, "Sundown by Gordon Lightfoot"))
        val reem = track("Bad Energy", "Reem")
        assertFalse(about(reem, "Bad Energy by Reemix"))
        assertTrue(about(reem, "Bad Energy - Reem - Chart History"))
    }

    @Test
    fun `any one of several credited artists will do`() {
        val young = track("Forever Young", "dearoldfriend", "Winston Hunte")
        assertTrue(about(young, "Forever Young", "Winston Hunte's cover"))
        assertFalse(about(young, "Forever Young - Alphaville"))
    }

    @Test
    fun `accented names match unaccented text`() {
        assertTrue(about(track("Apple Trees", "Charlie Dée"), "Apple Trees by Charlie Dee"))
    }

    @Test
    fun `drops the pipeline's own sources, subdomains included`() {
        val sundown = track("Sundown", "Gordon Lightfoot")
        val title = "Sundown (Gordon Lightfoot song)"
        assertFalse(about(sundown, title, url = "https://en.wikipedia.org/wiki/Sundown"))
        assertFalse(about(sundown, title, url = "https://www.wikiwand.com/en/Sundown"))
        assertFalse(about(sundown, title, url = "https://musicbrainz.org/release/x"))
        assertFalse(about(sundown, title, url = "https://www.last.fm/music/Gordon+Lightfoot"))
        assertFalse(about(sundown, title, url = "HTTPS://user@WWW.DISCOGS.COM:443/x"))
        assertTrue(about(sundown, title, url = "https://notwikipedia.org/x"))
        assertTrue(about(sundown, title, url = "https://example.com/en.wikipedia.org"))
    }
}
