package app.sleevesight.cli

import app.sleevesight.core.settings.Settings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class TracklistTest {
    private val fixtures = System.getProperty("sleevesight.fixtures")

    @Test
    fun `tracks are read in order, as a player reports them, the release and length optional`() {
        val list =
            Tracklist.parse(
                """
                {"name": "An album", "tracks": [
                  {"title": "One", "artist": "A, B", "album": "The Album", "dur": "3:05"},
                  {"title": "Two", "artist": "A"}
                ]}
                """.trimIndent(),
            )

        assertEquals("An album", list.name)
        assertEquals(listOf("One", "Two"), list.tracks.map { it.title })
        val first = list.tracks.first()
        assertEquals("A, B", first.artists)
        assertEquals("The Album", first.album)
        assertEquals(185.seconds, first.duration)
        assertEquals(Settings.SPOTIFY, first.player)
        assertNull(list.tracks[1].album)
        assertNull(list.tracks[1].duration)
    }

    @Test
    fun `a file it cannot use is refused with the reason`() {
        val bad =
            listOf(
                "not json",
                "[]",
                """{"tracks": []}""",
                """{"tracks": [{"artist": "A"}]}""",
                """{"tracks": [{"title": "One", "artist": " "}]}""",
                """{"tracks": [{"title": "One", "artist": "A", "dur": "185"}]}""",
                """{"tracks": [{"title": "One", "artist": "A", "length": "3:05"}]}""",
                """{"tracks": [{"title": 1, "artist": "A"}]}""",
            )
        for (text in bad) assertFailsWith<IllegalArgumentException>(text) { Tracklist.parse(text) }
    }

    @Test
    fun `the committed tracklists read`() {
        val files = File(fixtures, "tracklists").listFiles { f -> f.extension == "json" }.orEmpty()
        assertEquals(true, files.isNotEmpty())
        for (file in files) {
            val list = Tracklist.read(file)
            assertEquals(true, list.tracks.all { it.album != null && it.duration != null }, file.name)
        }
    }
}
