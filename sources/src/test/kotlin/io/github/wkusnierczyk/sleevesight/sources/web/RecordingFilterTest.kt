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

    private fun from(
        title: String,
        from: String,
        vararg artists: String,
    ) = track(title, *artists).copy(from = from)

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
        assertEquals("ray johnson and the sou westers", filter.fold("Ray Johnson & The Sou' Westers"))
        assertEquals("rock and roll", filter.fold("Rock&Roll"))
    }

    @Test
    fun `apostrophes are dropped, so Dont matches Don't`() {
        assertEquals("baby please dont go", filter.fold("Baby, Please Don\u2019t Go"))
        assertEquals("fishin in a dory", filter.fold("Fishin' In A Dory"))
        assertEquals("sou westers", filter.fold("Sou\u02BC Westers"))
        assertEquals("gordon lightfoot s sundown", filter.fold("Gordon Lightfoot\u2019s Sundown"))
        assertEquals("them s single", filter.fold("Them's single"))
        val baby = track("Baby, Please Don't Go", "Them", "Van Morrison")
        assertTrue(
            about(baby, "Baby Please Dont Go - Them featuring Van Morrison", "Them's single, Baby, Please Don't Go."),
        )
    }

    @Test
    fun `a track that folds to nothing cannot be checked`() {
        assertFalse(filter.canCheck(track("Sundown", "!!!")))
        assertFalse(filter.canCheck(track("!!!", "Gordon Lightfoot")))
        assertFalse(filter.canCheck(from("Theme", "???", "London Music Works")))
        assertTrue(filter.canCheck(track("Sundown", "Gordon Lightfoot")))
    }

    @Test
    fun `and matches an ampersand`() {
        val dory = track("Fishin' In A Dory", "Ray Johnson And The Sou' Westers")
        assertTrue(about(dory, "The Newfoundland I Love", "Ray Johnson & The Sou' Westers: Fishin' In A Dory"))
        assertTrue(
            about(track("Apple Trees & Buzzing Bees", "Charlie Dée"), "Apple Trees and Buzzing Bees by Charlie Dee"),
        )
    }

    @Test
    fun `needs the work the song is from when the player names it`() {
        val theme = from("Theme", "Coronation Street", "London Music Works")
        assertFalse(about(theme, "Doctor Who Theme - song by London Music Works"))
        assertFalse(about(theme, "Doctor Who Theme - song by London Music Works", "also: Coronation Street Theme"))
        assertTrue(about(theme, "Theme from Coronation Street - London Music Works"))
        assertTrue(about(theme, "Coronation Street theme", "recorded by London Music Works"))
    }

    @Test
    fun `a result titled after the artist without the song is dropped`() {
        val dark = track("I Will Follow You Into The Dark", "Yanjoco")
        assertFalse(about(dark, "Caravan - song by Yanjoco", "More by Yanjoco: I Will Follow You Into The Dark"))
        assertFalse(about(dark, "Yanjoco", "I Will Follow You Into The Dark"))
        assertTrue(about(dark, "Below soundtrack", "I Will Follow You Into The Dark - Yanjoco"))
    }

    @Test
    fun `common-word artists give way to a distinctive one`() {
        val baby = track("Baby, Please Don't Go", "Them", "Van Morrison")
        assertEquals(listOf("van morrison"), filter.requiredArtists(baby))
        assertFalse(about(baby, "Baby, Please Don't Go - AC/DC", "many bands covered it before them"))
        assertTrue(about(baby, "Baby, Please Don't Go", "Them, with Van Morrison singing"))
        // With no distinctive artist, every credited one counts.
        val twelve = track("Glittery", "To Twelve")
        assertEquals(listOf("to twelve"), filter.requiredArtists(twelve))
        assertTrue(about(twelve, "Glittery - To Twelve"))
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
