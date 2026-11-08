package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** LRCLIB's query for a track (#997): the length rounded to whole seconds, and none without one. */
class LibraryQueryTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun queryOf(nowPlaying: NowPlaying) = LibraryQuery.of(normalizer.normalize(nowPlaying), nowPlaying)

    private val playing =
        NowPlaying(
            title = "Nasnusa - 2024 Remaster",
            artists = "Sababa 5",
            album = " Kokoro ",
            duration = 233_499.milliseconds,
            player = "com.spotify.music",
        )

    @Test
    fun `the length is rounded to whole seconds, half a second up`() {
        assertEquals(233, LibraryQuery.seconds(233_499.milliseconds))
        assertEquals(234, LibraryQuery.seconds(233_500.milliseconds))
        assertEquals(234, LibraryQuery.seconds(234.seconds))
    }

    @Test
    fun `the first artist, the title as the sources name it, the album trimmed, and the length`() {
        assertEquals(LibraryQuery("Sababa 5", "Nasnusa", "Kokoro", 233), queryOf(playing))
        assertNull(queryOf(playing.copy(album = "  "))?.album)
    }

    @Test
    fun `no query without the player's length, or one LRCLIB does not take`() {
        assertNull(queryOf(playing.copy(duration = null)))
        assertNull(queryOf(playing.copy(duration = 400.milliseconds)))
        assertNull(queryOf(playing.copy(duration = 1.hours + 1.seconds)))
        assertEquals(3600, queryOf(playing.copy(duration = 1.hours))?.seconds)
    }

    @Test
    fun `no query when normalizing removed anything but a suffix known to be the song's own recording`() {
        for (title in listOf(
            "Nasnusa - Off Vocal Version",
            "Nasnusa - No Vocals Version",
            "Nasnusa - Instrumental Version",
            "Nasnusa - Karaoke Version",
            "Nasnusa - Backing Track Version",
            "Nasnusa - Live",
            "Nasnusa - Demo",
            "Nasnusa - Acoustic Version",
            "Nasnusa - Club Remix Version",
            "Nasnusa - Instrumental Edit",
            "Nasnusa - Live Edit",
            "Nasnusa (felt piano)",
        )) {
            assertNull(queryOf(playing.copy(title = title)), title)
        }
        for (title in listOf(
            "Nasnusa - 2024 Remaster",
            "Nasnusa - Remastered 2011",
            "Nasnusa - Remastered",
            "Nasnusa - Radio Edit",
            "Nasnusa - Single Edit",
            "Nasnusa - Promo 7\" Edit",
            "Nasnusa - 12\" Edit",
            "Nasnusa - Edit",
            "Nasnusa - Single Version",
            "Nasnusa - Album Version",
            "Nasnusa - Mono",
            "Nasnusa - Stereo Version",
            "Nasnusa - Explicit Version",
            "Nasnusa - Clean Version",
        )) {
            assertEquals("Nasnusa", queryOf(playing.copy(title = title))?.title, title)
        }
        // A word the title keeps is asked with it, so LRCLIB matches that mix itself; a featured
        // artist moved to the artists, or the work it is from, is no other recording.
        assertEquals("Live Forever", queryOf(playing.copy(title = "Live Forever"))?.title)
        assertEquals("Nasnusa", queryOf(playing.copy(title = "Nasnusa (feat. Yurika Hanashima)"))?.title)
    }
}
