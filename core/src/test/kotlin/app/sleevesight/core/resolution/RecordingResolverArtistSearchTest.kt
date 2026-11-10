// Lengths and dates are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The search by title and artist (#1129): without a leading composer, which MusicBrainz's titles do
 * not have, and by each credited artist in turn while none finds anything, the composer last.
 */
class RecordingResolverArtistSearchTest {
    private val title = "Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
    private val mozart = "Wolfgang Amadeus Mozart"
    private val classical =
        track("Mozart: $title", "$mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields")
    private val nothing: Lookup<List<MbRecording>> = Lookup.Found(emptyList())

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
    fun `a classical track is searched without the composer's lead, by a performer first`() =
        runTest {
            val musicBrainz =
                FakeMusicBrainz(nothing).apply { searchesBy["Khatia Buniatishvili"] = Lookup.Found(listOf(performed)) }
            val resolution = resolve(musicBrainz, classical, 457)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("e0a505da", resolution.recordingMbid)
            assertEquals(listOf(title to "Khatia Buniatishvili"), musicBrainz.searched)
        }

    @Test
    fun `when the first artist's search finds nothing, the next one's is made`() =
        runTest {
            val found = recording("found", "Song", "B", 200, "1999")
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["B"] = Lookup.Found(listOf(found)) }
            val resolution = resolve(musicBrainz, track("Song", "A, B, C"))
            assertEquals("found", resolution.recordingMbid)
            assertEquals(listOf("Song" to "A", "Song" to "B"), musicBrainz.searched)
        }

    @Test
    fun `the searches by artist stop at three, however many artists are credited`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            val resolution = resolve(musicBrainz, track("Song", "A, B, C, D, E"))
            assertEquals(Outcome.None, resolution.outcome)
            assertEquals(listOf("A", "B", "C"), musicBrainz.searched.map { it.second })
            assertEquals(RecordingResolver.ARTIST_SEARCHES, musicBrainz.searched.size)
        }

    @Test
    fun `a search that is not found counts as nothing found, and the next artist is searched`() =
        runTest {
            val found = recording("found", "Song", "B", 200, "1999")
            val musicBrainz = FakeMusicBrainz(Lookup.NotFound).apply { searchesBy["B"] = Lookup.Found(listOf(found)) }
            assertEquals("found", resolve(musicBrainz, track("Song", "A, B")).recordingMbid)
        }

    @Test
    fun `a failed search ends the searches, and MusicBrainz could not be reached`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["B"] = Lookup.Failed("Timeout") }
            val resolution = resolve(musicBrainz, track("Song", "A, B, C"))
            assertEquals(Outcome.Unreachable, resolution.outcome)
            assertEquals(listOf("A", "B"), musicBrainz.searched.map { it.second })
        }

    @Test
    fun `a first search that finds something decides, even when none of it fits`() =
        runTest {
            val other = recording("other", "Song", "Someone Else", 200, "1999")
            val found = recording("found", "Song", "B", 200, "1999")
            val musicBrainz =
                FakeMusicBrainz(Lookup.Found(listOf(other))).apply { searchesBy["B"] = Lookup.Found(listOf(found)) }
            val resolution = resolve(musicBrainz, track("Song", "A, B"))
            assertNull(resolution.recordingMbid)
            assertEquals(listOf("A"), musicBrainz.searched.map { it.second })
        }

    @Test
    fun `a title whose lead names no credited artist is searched whole, by the first artist`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            resolve(musicBrainz, track("Star Wars: Main Title", "John Williams, London Symphony Orchestra"))
            assertEquals(
                listOf(
                    "Star Wars: Main Title" to "John Williams",
                    "Star Wars: Main Title" to "London Symphony Orchestra",
                ),
                musicBrainz.searched,
            )
        }

    @Test
    fun `the search by date of a full page is by the title and the artist that found it`() =
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
    fun `the composer is searched by last, after the other credited artists`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            resolve(musicBrainz, track("Bach: Aria", "Johann Sebastian Bach, Glenn Gould"))
            assertEquals(listOf("Aria" to "Glenn Gould", "Aria" to "Johann Sebastian Bach"), musicBrainz.searched)
        }
}
