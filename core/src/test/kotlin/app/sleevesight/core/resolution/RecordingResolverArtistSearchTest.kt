// Lengths and dates are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmAccentFolder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The search by title and artist (#1129): one search, without a leading composer, which MusicBrainz's
 * titles do not have, and by the first performer, the composer moved last; when it finds nothing that
 * fits, one more by the title as written and the composer, the search made before.
 */
class RecordingResolverArtistSearchTest {
    private val title = "Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
    private val mozart = "Wolfgang Amadeus Mozart"
    private val classical =
        track("Mozart: $title", "$mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields")
    private val nothing: Lookup<List<MbRecording>> = Lookup.Found(emptyList())
    private val bach = "Johann Sebastian Bach"

    // MusicBrainz's recording: its own title, credited to the soloist alone here.
    private val performed =
        recording(
            "e0a505da",
            "Piano Sonata no. 16 in C major, K. 545 \"Sonata facile\": II. Andante",
            "Khatia Buniatishvili",
            457,
            "2024-10-25",
        )

    @Test
    fun `a classical track is searched once, without the composer's lead, by its first performer`() =
        runTest {
            val musicBrainz =
                FakeMusicBrainz(nothing).apply { searchesBy["Khatia Buniatishvili"] = Lookup.Found(listOf(performed)) }
            val resolution = resolve(musicBrainz, classical, 457)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("e0a505da", resolution.recordingMbid)
            assertEquals(listOf(title to "Khatia Buniatishvili"), musicBrainz.searched)
        }

    @Test
    fun `without a composer, one search by the first artist, however many are credited`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            assertEquals(Outcome.None, resolve(musicBrainz, track("Song", "A, B, C, D, E")).outcome)
            assertEquals(listOf("Song" to "A"), musicBrainz.searched)
        }

    @Test
    fun `a title whose lead names no credited artist is searched whole, once, by the first artist`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            resolve(musicBrainz, track("Star Wars: Main Title", "John Williams, London Symphony Orchestra"))
            assertEquals(listOf("Star Wars: Main Title" to "John Williams"), musicBrainz.searched)
        }

    @Test
    fun `with nothing that fits found by the performer, the title as written is searched by the composer`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould"))
            assertEquals(listOf("Aria" to "Glenn Gould", "Bach: Aria" to bach), musicBrainz.searched)
        }

    @Test
    fun `a performer's search that finds what fits is the only one`() =
        runTest {
            val both =
                recording(
                    "gould",
                    "Aria",
                    bach,
                    200,
                    "1981",
                ).copy(credits = listOf(credit(bach), credit("Glenn Gould")))
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["Glenn Gould"] = Lookup.Found(listOf(both)) }
            assertEquals("gould", resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould")).recordingMbid)
            assertEquals(listOf("Aria" to "Glenn Gould"), musicBrainz.searched)
        }

    @Test
    fun `a lead that is no composer still finds its recording by the search as written`() =
        runTest {
            // `Band` is taken for a composer, so the search is by Guest, who has nothing; the search
            // made before #1129, `Band: Song` by Band, finds Band's recording.
            val exact = recording("exact", "Band: Song", "Band", 200, "1990")
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["Band"] = Lookup.Found(listOf(exact)) }
            assertEquals("exact", resolve(musicBrainz, track("Band: Song", "Band, Guest")).recordingMbid)
            assertEquals(listOf("Song" to "Guest", "Band: Song" to "Band"), musicBrainz.searched)
        }

    @Test
    fun `when the search as written finds nothing either, the first search's answer stands`() =
        runTest {
            // Gould's search finds a near miss, another artist's `Aria`; the composer's finds nothing.
            val other = recording("other", "Aria", "Someone Else", 200, "1999")
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["Glenn Gould"] = Lookup.Found(listOf(other)) }
            val resolution = resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould"))
            assertEquals(
                Outcome.Rejected("MusicBrainz has “Aria” by Someone Else, not by Glenn Gould"),
                resolution.outcome,
            )
        }

    @Test
    fun `a failed first search is MusicBrainz unreachable, with no search after it`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(Lookup.Failed("Timeout"))
            assertEquals(Outcome.Unreachable, resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould")).outcome)
            assertEquals(listOf("Aria" to "Glenn Gould"), musicBrainz.searched)
        }

    @Test
    fun `the search by date of a full page is by the title and the artist searched`() =
        runTest {
            val page =
                listOf(performed) +
                    (1 until MusicBrainzLookup.SEARCH_PAGE).map {
                        recording("live-$it", performed.title, "Khatia Buniatishvili", 600 + it, "2025")
                    }
            val musicBrainz =
                FakeMusicBrainz(nothing).apply { searchesBy["Khatia Buniatishvili"] = Lookup.Found(page) }
            musicBrainz.before = Lookup.Found(listOf(performed))
            assertEquals("e0a505da", resolve(musicBrainz, classical, 457).recordingMbid)
            assertEquals(listOf(title to "Khatia Buniatishvili"), musicBrainz.beforeSearched)
        }

    @Test
    fun `with performers credited, a recording credited to the composer alone is not the track's`() =
        runTest {
            // Gould's search finds nothing; the composer's finds an `Aria` credited to Bach alone, which
            // may be anyone's performance.
            val composerOnly = recording("bach-only", "Aria", bach, 200, "1990")
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy[bach] = Lookup.Found(listOf(composerOnly)) }
            val resolution = resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould"))
            assertNull(resolution.recordingMbid)
            assertEquals(Outcome.Rejected("MusicBrainz has “Aria” by $bach, not by Glenn Gould"), resolution.outcome)
        }

    @Test
    fun `a full page credited to the composer alone gives no date to search by`() =
        runTest {
            val page =
                (1..MusicBrainzLookup.SEARCH_PAGE).map { recording("bach-$it", "Aria", bach, 200, "${1950 + it}") }
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["Glenn Gould"] = Lookup.Found(page) }
            resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould"))
            assertEquals(emptyList(), musicBrainz.beforeAsked)
        }

    @Test
    fun `a lead naming the only credited artist is no composer, and the title is searched whole`() =
        runTest {
            // No performer named beside Chopin: any recording credited to Chopin titled `Nocturne`
            // would do, so none is taken.
            val chopin = "Frédéric Chopin"
            val nocturne = recording("nocturne", "Nocturne", chopin, 200, "1990")
            val musicBrainz = FakeMusicBrainz(Lookup.Found(listOf(nocturne)))
            assertNull(resolve(musicBrainz, track("Chopin: Nocturne", chopin)).recordingMbid)
            assertEquals(listOf("Chopin: Nocturne" to chopin), musicBrainz.searched)
            val band = FakeMusicBrainz(Lookup.Found(listOf(recording("song", "Song", "Band", 200, "1970"))))
            assertNull(resolve(band, track("Band: Song", "Band")).recordingMbid)
            assertEquals(listOf("Band: Song" to "Band"), band.searched)
        }

    @Test
    fun `a recording whose title is the one as written is weighed against every credited artist`() =
        runTest {
            // `Band` is taken for a composer, but the recording titled `Band: Song` is read as written,
            // so Band's credit counts; Guest's older `Song`, read without the lead, does not beat it.
            val exact = recording("exact", "Band: Song", "Band", 200, "1990")
            val guests = recording("guests", "Song", "Guest", 200, "1970")
            val musicBrainz = FakeMusicBrainz(Lookup.Found(listOf(guests, exact)))
            assertEquals("exact", resolve(musicBrainz, track("Band: Song", "Band, Guest")).recordingMbid)
        }

    @Test
    fun `a long title as written beats an earlier recording of it without the lead, however close the two`() =
        runTest {
            val written = "Band: A Very Long Song Title With Several Words"
            val stripped = "A Very Long Song Title With Several Words"
            // The premise: without the lead, the title still fits the one as written.
            assertTrue(
                NameMatcher(
                    JvmAccentFolder,
                ).titleSimilarity(written, stripped) >= ResolutionThresholds.TITLE_SIMILARITY,
            )
            val older = recording("older", stripped, "Guest", 200, "1970")
            val exact = recording("exact", written, "Band", 200, "1990")
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(listOf(older, exact))), track(written, "Band, Guest"))
            assertEquals("exact", resolution.recordingMbid)
        }
}
