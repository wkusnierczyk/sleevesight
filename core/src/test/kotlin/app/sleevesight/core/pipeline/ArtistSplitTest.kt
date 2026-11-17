package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NormalizedTrack
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
    fun `the work's librettists write its words as its lyricists do, after them, the composer's own not counted`() {
        // #1274: Nessun dorma's work names Puccini its composer and two librettists, no lyricist.
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL), classical = true)
        val libretto = WorkLyrics(librettists = listOf(ADAMI, SIMONI))
        val split = checkNotNull(ArtistSplit.of(nessun, roles, libretto))
        assertEquals(AuthorsName.AUTHORS, split.name)
        assertEquals(listOf(PUCCINI), split.composers)
        assertEquals(listOf(ADAMI, SIMONI), split.words)
        assertEquals(listOf(PUCCINI, ADAMI, SIMONI), split.authors)
        // Lyricists first, then librettists, one credited as both once.
        val both = WorkLyrics(lyricists = listOf(SIMONI), librettists = listOf(ADAMI, SIMONI))
        assertEquals(listOf(SIMONI, ADAMI), ArtistSplit.of(nessun, roles, both)?.words)
        // A composer who wrote their own libretto is no other author: a classical work's Composer, and
        // a songwriter's Author.
        val own = WorkLyrics(librettists = listOf("giacomo puccini"))
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, own)?.name)
        assertEquals(emptyList(), ArtistSplit.of(nessun, roles, own)?.words)
        assertEquals(AuthorsName.AUTHOR, ArtistSplit.of(nessun, roles.copy(classical = false), own)?.name)
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
        // A classical work whose composer wrote its words, its title not leading with them: the composer.
        val classical = roles.copy(classical = true)
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, classical, words)?.name)
        // A work crediting no one with its words besides its composer: the composer alone.
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, WorkLyrics(composersOnly = false))?.name)
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, WorkLyrics(composersOnly = true))?.name)
        // The work's credits not known: Composer alone.
        assertEquals(AuthorsName.COMPOSER, ArtistSplit.of(nessun, roles, null)?.name)
        // Two composers are two authors.
        val two = ArtistRoles(listOf(PUCCINI, MEHTA), listOf(PAVAROTTI))
        assertEquals(AuthorsName.AUTHORS, ArtistSplit.of(nessun, two, null)?.name)
    }

    @Test
    fun `the song's row is Music beside a Composer row, beside Authors on a classical work, never beside Author`() {
        // #1331: by the test that names the Composer row.
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL))
        val libretto = WorkLyrics(librettists = listOf(ADAMI, SIMONI))

        fun of(
            track: NormalizedTrack,
            roles: ArtistRoles,
            lyrics: WorkLyrics?,
        ) = checkNotNull(ArtistSplit.of(track, roles, lyrics)).let { it.name to it.classical }
        // Composer: by a title leading with its composer, the roles' classical form, or neither told.
        assertEquals(AuthorsName.COMPOSER to true, of(k545, ArtistRoles.of(k545), null))
        assertEquals(AuthorsName.COMPOSER to true, of(nessun, roles.copy(classical = true), null))
        assertEquals(AuthorsName.COMPOSER to true, of(nessun, roles, null))
        // Authors: on a classical work (an opera's aria), or not.
        assertEquals(AuthorsName.AUTHORS to true, of(nessun, roles.copy(classical = true), libretto))
        assertEquals(AuthorsName.AUTHORS to false, of(nessun, roles, libretto))
        // Author: a songwriter's own words on no classical work.
        assertEquals(AuthorsName.AUTHOR to false, of(nessun, roles, WorkLyrics(lyricists = listOf(PUCCINI))))
    }

    @Test
    fun `an arranger the title names is no performer`() {
        val chaconne =
            track(
                "Bach: Partita No. 2 in D Minor, BWV 1004: V. Chaconne (Arr. Busoni)",
                "Johann Sebastian Bach, Ferruccio Busoni, Hélène Grimaud",
            )
        val split = checkNotNull(ArtistSplit.of(chaconne, ArtistRoles.of(chaconne), null))
        assertEquals(listOf("Johann Sebastian Bach"), split.composers)
        assertEquals(listOf("Hélène Grimaud"), split.performers)
        assertEquals(AuthorsName.COMPOSER, split.name)
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

    @Test
    fun `a classical work's uncredited composer and librettists are its authors, every one marked uncredited`() {
        // #1298: Spotify credits Nessun Dorma to Pavarotti alone; the aria's work names Puccini and two
        // librettists.
        val alone = track("Nessun Dorma", PAVAROTTI)
        val roles = ArtistRoles(emptyList(), listOf(PAVAROTTI), classical = true, uncredited = listOf(PUCCINI))
        val split = checkNotNull(ArtistSplit.of(alone, roles, WorkLyrics(librettists = listOf(ADAMI, SIMONI))))
        assertEquals(listOf(PUCCINI), split.composers)
        assertEquals(PAVAROTTI, split.lead)
        assertEquals(AuthorsName.AUTHORS, split.name)
        assertEquals(true, split.classical)
        assertEquals(listOf(PUCCINI, ADAMI, SIMONI), split.uncredited)
        // The composer alone: a Composer row, and the song's row Music.
        val composer = checkNotNull(ArtistSplit.of(alone, roles, null))
        assertEquals(AuthorsName.COMPOSER, composer.name)
        assertEquals(listOf(PUCCINI), composer.uncredited)
    }

    @Test
    fun `authors the player credits are not uncredited, librettists from MusicBrainz are`() {
        // Spotify's Nessun doma credits Puccini: only the librettists are MusicBrainz's.
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL), classical = true)
        val libretto = WorkLyrics(librettists = listOf(ADAMI, SIMONI))
        assertEquals(listOf(ADAMI, SIMONI), ArtistSplit.of(nessun, roles, libretto)?.uncredited)
        // Every author credited: nothing to mark (Mozart's K. 545, Beethoven's Ninth).
        assertEquals(emptyList(), ArtistSplit.of(k545, ArtistRoles.of(k545), null)?.uncredited)
        assertEquals(emptyList(), ArtistSplit.of(nessun, roles, null)?.uncredited)
    }

    @Test
    fun `a work not classical keeps its authors unmarked, whoever credits them`() {
        // A song's lyricist from MusicBrainz beside its credited composer: an Authors line, no mark.
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI), classical = false)
        val split = checkNotNull(ArtistSplit.of(nessun, roles, WorkLyrics(lyricists = listOf(ADAMI))))
        assertEquals(AuthorsName.AUTHORS, split.name)
        assertEquals(false, split.classical)
        assertEquals(listOf(PUCCINI, ADAMI), split.authors)
        assertEquals(emptyList(), split.uncredited)
    }

    @Test
    fun `librettists the player credits jointly are credited, and no author is then marked`() {
        val joint = track("Nessun Dorma", "$PUCCINI, $ADAMI & $SIMONI, $PAVAROTTI")
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI), classical = true)
        val split = checkNotNull(ArtistSplit.of(joint, roles, WorkLyrics(librettists = listOf(ADAMI, SIMONI))))
        assertEquals(listOf(PUCCINI, ADAMI, SIMONI), split.authors)
        assertEquals(emptyList(), split.uncredited)
        // One of them alone in the joint credit: the other is MusicBrainz's.
        val one = track("Nessun Dorma", "$PUCCINI, $ADAMI & Someone Else, $PAVAROTTI")
        val libretto = WorkLyrics(librettists = listOf(ADAMI, SIMONI))
        assertEquals(listOf(SIMONI), ArtistSplit.of(one, roles, libretto)?.uncredited)
    }

    private companion object {
        const val MOZART = "Wolfgang Amadeus Mozart"
        const val KHATIA = "Khatia Buniatishvili"
        const val ACADEMY = "Academy of St. Martin in the Fields"
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
        const val LA_PHIL = "Los Angeles Philharmonic"
        const val ADAMI = "Giuseppe Adami"
        const val SIMONI = "Renato Simoni"
    }
}
