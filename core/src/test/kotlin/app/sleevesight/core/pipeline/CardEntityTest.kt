package app.sleevesight.core.pipeline

import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.ReleaseDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardEntityTest {
    private val albums = NameMatcher(JvmAccentFolder)

    private fun album(
        title: String,
        vararg dates: ReleaseDate,
    ) = CardEntity.Album.of(title, "Gordon Lightfoot", dates.toList(), albums::albumKey)

    @Test
    fun `an album is the one release group its name names`() {
        val sundown = ReleaseDate("Sundown", "1974-01", "rg-sundown", "Album")
        val gold = ReleaseDate("Gord's Gold", "1975", "rg-gold", "Album")
        val reissue = ReleaseDate("Sundown", "2003", "rg-sundown", "Album")

        assertEquals(
            CardEntity.Album("Sundown", "Gordon Lightfoot", "rg-sundown"),
            album("Sundown", sundown, gold, reissue),
        )
        // A title track is on its single of the album's name too: the album is the album's group.
        val single = ReleaseDate("Sundown", "1974-04", "rg-sundown-single", "Single")
        assertEquals("rg-sundown", album("Sundown", single, sundown, gold).group)
        // An edition the player names reads as the album without it, as for the year.
        assertEquals("rg-sundown", album("Sundown (Remastered)", sundown, gold).group)
    }

    @Test
    fun `an album that names no release group, or several, has none`() {
        val first = ReleaseDate("Love Songs", "1980", "rg-love-1", "Album")
        val second = ReleaseDate("Love Songs", "1999", "rg-love-2", "Album")

        assertNull(album("Love Songs", first, second).group)
        assertNull(album("Love Songs", first.copy(type = "Single"), second.copy(type = "Single")).group)
        assertNull(album("Summertime Dream", first).group)
        assertNull(album("Sundown").group)
    }
}
