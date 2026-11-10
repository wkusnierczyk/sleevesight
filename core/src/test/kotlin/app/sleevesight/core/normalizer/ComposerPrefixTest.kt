package app.sleevesight.core.normalizer

import app.sleevesight.core.model.TitleComposer
import app.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A title that names its composer first (#1129): found, kept in the title, left out of the search. */
class ComposerPrefixTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    @Test
    fun `a lead naming a credited artist by surname is the composer, kept in the title, left out of the search`() {
        val title = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
        val track =
            normalizer.normalize(
                title,
                "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
            )
        assertEquals(TitleComposer("Wolfgang Amadeus Mozart", "Mozart: "), track.composer)
        assertEquals(title, track.title)
        assertEquals("Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante", track.searchTitle)
        assertEquals(listOf("composer: Wolfgang Amadeus Mozart"), track.notes)
        assertEquals(normalizer.normalize(title, "Wolfgang Amadeus Mozart").lookupKey, track.lookupKey)
    }

    @Test
    fun `the lead may be the whole name, its last words, or initials before the surname, any case or accent`() {
        val cases =
            listOf(
                "Wolfgang Amadeus Mozart: Requiem" to "Wolfgang Amadeus Mozart",
                "Vaughan Williams: The Lark Ascending" to "Ralph Vaughan Williams, Hilary Hahn",
                "J.S. Bach: Goldberg Variations, BWV 988: Aria" to "Johann Sebastian Bach, Glenn Gould",
                "C.P.E. Bach: Solfeggio" to "Carl Philipp Emanuel Bach",
                "SAINT-SAENS: The Swan" to "Camille Saint-Saëns, Yo-Yo Ma",
                "Gould: Interview" to "Johann Sebastian Bach, Glenn Gould",
            )
        for ((title, artists) in cases) {
            val track = normalizer.normalize(title, artists)
            assertEquals(title.substringAfter(": "), track.searchTitle, title)
            assertEquals(title, track.title, title)
        }
        val gould = normalizer.normalize("Gould: Interview", "Johann Sebastian Bach, Glenn Gould")
        assertEquals("Glenn Gould", gould.composer?.name)
    }

    @Test
    fun `a lead that names no credited artist is no composer, and the title is searched whole`() {
        val cases =
            listOf(
                "Star Wars: Main Title" to "John Williams",
                "Interlude: The Night" to "Artist",
                // Not the last words of the name, nor an initial of them.
                "Amadeus: Overture" to "Wolfgang Amadeus Mozart",
                "Wolfgang: Overture" to "Wolfgang Amadeus Mozart",
                "M.: Overture" to "Wolfgang Amadeus Mozart",
                // No whitespace after the colon, or nothing after it.
                "Mozart:Requiem" to "Wolfgang Amadeus Mozart",
                "Re: Stacks" to "Bon Iver",
            )
        for ((title, artists) in cases) {
            val track = normalizer.normalize(title, artists)
            assertNull(track.composer, title)
            assertEquals(title, track.searchTitle, title)
            assertEquals(emptyList(), track.notes, title)
        }
    }

    @Test
    fun `the search title is the title whole once the title no longer starts with the lead`() {
        val track = normalizer.normalize("Mozart: Requiem", "Wolfgang Amadeus Mozart")
        assertEquals("Requiem", track.searchTitle)
        assertEquals("Моцарт: Реквием", track.copy(title = "Моцарт: Реквием").searchTitle)
    }

    @Test
    fun `the first credited artist the lead names is the composer`() {
        val bachs = listOf("Johann Sebastian Bach", "Carl Philipp Emanuel Bach")
        val found = ComposerPrefix.find("Bach: Aria", bachs, normalizer::foldForKey)
        assertEquals(TitleComposer("Johann Sebastian Bach", "Bach: "), found)
    }
}
