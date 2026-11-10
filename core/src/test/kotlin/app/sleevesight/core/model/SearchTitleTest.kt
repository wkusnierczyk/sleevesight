package app.sleevesight.core.model

import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.searchUrl
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * What a title that leads with its credited composer is searched by (#1179): its work part, and the
 * key its search's findings are kept under; any other title as before. Genius's search leaves the
 * lead out; LRCLIB keeps it, as the players' names do.
 */
class SearchTitleTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val performers = "Khatia Buniatishvili, Academy of St. Martin in the Fields"

    private fun mozart(title: String) = normalizer.normalize(title, "Wolfgang Amadeus Mozart, $performers")

    private val k545 = mozart("Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante")

    @Test
    fun `the work is the searched title before its first colon, a quoted nickname left out`() {
        assertEquals("Piano Sonata No. 16 in C Major, K. 545", k545.searchWork)
        assertEquals(
            "Piano Concerto No. 20 in D Minor, K.466",
            mozart("Mozart: Piano Concerto No. 20 in D Minor, K.466: II. Romance").searchWork,
        )
        assertEquals(
            "Symphony No. 41 in C Major, K. 551",
            mozart("Mozart: Symphony No. 41 in C Major, K. 551, “Jupiter”: I. Allegro vivace").searchWork,
        )
        val franck =
            normalizer.normalize(
                "Franck: Violin Sonata in A Major, FWV 8: I. Allegretto ben moderato",
                "César Franck, Renaud Capuçon",
            )
        assertEquals("Violin Sonata in A Major, FWV 8", franck.searchWork)
    }

    @Test
    fun `the work is cut at the first colon whatever follows it`() {
        assertEquals(
            "Piano Sonata No. 16 in C Major, K. 545",
            mozart("Mozart: Piano Sonata No. 16 in C Major, K. 545:II. Andante").searchWork,
        )
        assertNull(mozart("Mozart: Piano Sonata No. 16 in C Major, K. 545:  ").searchWork)
    }

    @Test
    fun `a nickname in any of the usual pairs of quotes is left out of the work`() {
        val nicknames =
            listOf(
                "\"Jupiter\"",
                "\u201CJupiter\u201D",
                "\u201EJupiter\u201C",
                "\u201EJupiter\u201D",
                "\u201AJupiter\u2018",
                "\u2018Jupiter\u2019",
                "\u00ABJupiter\u00BB",
                "\u00BBJupiter\u00AB",
            )
        for (nickname in nicknames) {
            val track = mozart("Mozart: Sinfonie Nr. 41 in C-Dur, KV 551 $nickname: I. Allegro vivace")
            assertEquals("Sinfonie Nr. 41 in C-Dur, KV 551", track.searchWork, nickname)
        }
    }

    @Test
    fun `a work with no digit is too generic, and a catalogue number's colon is not cut at`() {
        val haydn = "Joseph Haydn, Philharmonia Hungarica, Antal Doráti"
        assertNull(normalizer.normalize("Haydn: Symphony \"Surprise\": II. Andante", haydn).searchWork)
        assertEquals(
            "Symphony No. 94 in G Major, Hob. I:94",
            normalizer
                .normalize(
                    "Haydn: Symphony No. 94 in G Major, Hob. I:94 \"Surprise\": II. Andante",
                    haydn,
                ).searchWork,
        )
        assertNull(mozart("Mozart: Serenade \"Eine kleine Nachtmusik\": I. Allegro").searchWork)
        val williams = "John Williams, London Symphony Orchestra"
        assertNull(normalizer.normalize("Williams: Star Wars: Main Title", williams).searchWork)
        assertEquals(
            "Turandot, Act 3",
            normalizer
                .normalize(
                    "Puccini: Turandot, Act 3: \"Nessun dorma\"",
                    "Giacomo Puccini, Luciano Pavarotti",
                ).searchWork,
        )
    }

    @Test
    fun `no work without a movement, without the composer's lead, or in a title the lead is not left out of`() {
        assertNull(normalizer.normalize("Allegri: Miserere mei, Deus", "Gregorio Allegri, Stephen Cleobury").searchWork)
        assertNull(normalizer.normalize("Star Wars: Main Title", "John Williams, London Symphony Orchestra").searchWork)
        assertNull(normalizer.normalize("Interlude: Reprise", "Some Band, Other Artist").searchWork)
        // A title in another script the lead is not found in (the language search's native title).
        assertNull(k545.copy(title = "Моцарт: Соната для фортепиано № 16: II. Анданте").searchWork)
    }

    @Test
    fun `the search key marks only a title searched without its composer's lead`() {
        assertNotEquals(k545.lookupKey, k545.searchKey)
        val pop = normalizer.normalize("Star Wars: Main Title", "John Williams")
        assertEquals(pop.lookupKey, pop.searchKey)
        val sundown = normalizer.normalize("Sundown", "Gordon Lightfoot")
        assertEquals(sundown.lookupKey, sundown.searchKey)
    }

    @Test
    fun `Genius's search leaves out the composer's lead, any other title as before`() {
        assertEquals(
            "https://genius.com/search?q=Piano%20Sonata%20No.%2016%20in%20C%20Major%2C%20K.%20545" +
                "%20%22Sonata%20facile%22%3A%20II.%20Andante%20Wolfgang%20Amadeus%20Mozart",
            searchUrl(k545),
        )
        assertEquals(
            "https://genius.com/search?q=Star%20Wars%3A%20Main%20Title%20John%20Williams",
            searchUrl(normalizer.normalize("Star Wars: Main Title", "John Williams")),
        )
    }

    @Test
    fun `LRCLIB is asked by the whole title, as the players name the track`() {
        val playing =
            NowPlaying(
                title = k545.title,
                artists = "Wolfgang Amadeus Mozart, $performers",
                album = "Mozart: Piano Concertos Nos. 20 & 23",
                duration = 457.seconds,
                player = "com.spotify.music",
            )
        assertEquals(
            LibraryQuery("Wolfgang Amadeus Mozart", k545.title, "Mozart: Piano Concertos Nos. 20 & 23", 457),
            LibraryQuery.of(normalizer.normalize(playing), playing),
        )
    }
}
