package app.sleevesight.core.pipeline

import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.WorkLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A track's artists split between a Composer row and an Artist row (#1199): only when the roles tell
 * a composer apart from a performer; the lead performer the Artist panel's subject, the others its
 * chips; the Composer row *Composer*, *Author* or *Authors*.
 */
class ArtistSplitTest {
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun track(
        title: String,
        artists: String,
    ) = normalizer.normalize(nowPlaying(title, artists))

    private val k545 =
        track(
            "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            "$MOZART, $KHATIA, $ACADEMY",
        )
    private val nessun = track("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA, $LA_PHIL")

    @Test
    fun `a title leading with its composer splits off the composer, the first performer the lead`() {
        val split = checkNotNull(ArtistSplit.of(k545, ArtistRoles.of(k545), null))
        assertEquals(listOf(MOZART), split.composers)
        assertEquals(KHATIA, split.lead)
        assertEquals(listOf(ACADEMY), split.others)
        assertEquals(AuthorsName.COMPOSER, split.name)
        assertEquals(listOf(MOZART), split.authors)
    }

    @Test
    fun `the roles MusicBrainz told split a title with no lead, and the work's lyricists make it Authors`() {
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL))
        val lyrics = WorkLyrics(lyricists = listOf("Giuseppe Adami", "Renato Simoni"))
        val split = checkNotNull(ArtistSplit.of(nessun, roles, lyrics))
        assertEquals(PAVAROTTI, split.lead)
        assertEquals(listOf(MEHTA, LA_PHIL), split.others)
        assertEquals(AuthorsName.AUTHORS, split.name)
        assertEquals(listOf(PUCCINI, "Giuseppe Adami", "Renato Simoni"), split.authors)
        // A lyricist who is the composer is no other author: their words are their own.
        val own = WorkLyrics(lyricists = listOf("giacomo puccini"))
        assertEquals(listOf(PUCCINI), ArtistSplit.of(nessun, roles, own)?.authors)
    }

    @Test
    fun `one author whose words are their own and no title lead is an Author, else a Composer`() {
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI))
        val words = WorkLyrics(lyricists = listOf(PUCCINI))
        assertEquals(AuthorsName.AUTHOR, ArtistSplit.of(nessun, roles, words)?.name)
        // A classical title's composer writing their own words stays the composer.
        val k545Roles = ArtistRoles.of(k545)
        assertEquals(
            AuthorsName.COMPOSER,
            ArtistSplit.of(k545, k545Roles, WorkLyrics(lyricists = listOf(MOZART)))?.name,
        )
        // Librettists are not read (Nessun dorma's work names two): the composer alone.
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, WorkLyrics(composersOnly = false))?.name)
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, WorkLyrics(composersOnly = true))?.name)
        // The work's credits not known: Composer alone.
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, null)?.name)
        // Two composers are two authors.
        val two = ArtistRoles(listOf(PUCCINI, MEHTA), listOf(PAVAROTTI))
        assertEquals(AuthorsName.AUTHORS, ArtistSplit.of(nessun, two, null)?.name)
    }

    @Test
    fun `with no composer told, or no performer, the track keeps one Artist row`() {
        val pop = track("Somebody That I Used To Know", "Gotye, Kimbra")
        assertNull(ArtistSplit.of(pop, ArtistRoles.of(pop), null))
        assertNull(ArtistSplit.of(nessun, ArtistRoles.of(nessun), null))
        // A track credited to its composer alone has no performer to split off.
        val alone = track("Mozart: Eine kleine Nachtmusik, K. 525: I. Allegro", MOZART)
        assertNull(ArtistSplit.of(alone, ArtistRoles.of(alone), null))
    }

    private companion object {
        const val MOZART = "Wolfgang Amadeus Mozart"
        const val KHATIA = "Khatia Buniatishvili"
        const val ACADEMY = "Academy of St. Martin in the Fields"
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
        const val LA_PHIL = "Los Angeles Philharmonic"
    }
}
