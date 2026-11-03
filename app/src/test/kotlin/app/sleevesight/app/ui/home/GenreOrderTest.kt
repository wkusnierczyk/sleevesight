package app.sleevesight.app.ui.home

import app.sleevesight.core.genres.TrackGenres
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The chips' order of names (#875): the language's alphabetical order, case aside. */
class GenreOrderTest {
    private val names = listOf("Zydeco", "Électro", "bebop", "Blues", "Ambient")

    @Test
    fun namesSortAsTheLanguageSortsThemCaseAndAccentsAside() {
        assertEquals(
            listOf("Ambient", "bebop", "Blues", "Électro", "Zydeco"),
            names.sortedWith(genreOrder(Locale.ENGLISH)),
        )
        // Without the language's collation, an accented letter would sort after every other.
        assertEquals("Électro", names.sortedWith(TrackGenres.CASE_FOLDED).last())
    }
}
