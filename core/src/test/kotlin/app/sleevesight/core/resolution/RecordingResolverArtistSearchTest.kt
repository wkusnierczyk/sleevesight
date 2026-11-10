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
    fun `the searches by artist stop at four, however many artists are credited`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            val resolution = resolve(musicBrainz, track("Song", "A, B, C, D, E"))
            assertEquals(Outcome.None, resolution.outcome)
            assertEquals(listOf("A", "B", "C", "D"), musicBrainz.searched.map { it.second })
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
    fun `a title whose lead names no credited artist is searched whole, by each credited artist in turn`() =
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

    @Test
    fun `with performers credited, a recording credited to the composer alone is not the track's`() =
        runTest {
            // Gould's search finds nothing; the composer's finds an `Aria` credited to Bach alone,
            // which may be anyone's performance.
            val bach = "Johann Sebastian Bach"
            val composerOnly = recording("bach-only", "Aria", bach, 200, "1990")
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy[bach] = Lookup.Found(listOf(composerOnly)) }
            val resolution = resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould"))
            assertNull(resolution.recordingMbid)
            assertEquals(
                Outcome.Rejected("MusicBrainz has “Aria” by $bach, not by Glenn Gould"),
                resolution.outcome,
            )
        }

    @Test
    fun `with performers credited, a recording crediting the composer and a performer is the track's`() =
        runTest {
            val bach = "Johann Sebastian Bach"
            val both =
                recording("gould", "Aria", bach, 200, "1981")
                    .copy(credits = listOf(credit(bach), credit("Glenn Gould")))
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy[bach] = Lookup.Found(listOf(both)) }
            val resolution = resolve(musicBrainz, track("Bach: Aria", "$bach, Glenn Gould"))
            assertEquals("gould", resolution.recordingMbid)
        }

    @Test
    fun `a full page credited to the composer alone gives no date to search by`() =
        runTest {
            val bach = "Johann Sebastian Bach"
            val page =
                (1..MusicBrainzLookup.SEARCH_PAGE).map { recording("bach-$it", "Aria", bach, 200, "${1950 + it}") }
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy[bach] = Lookup.Found(page) }
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
            val alone = FakeMusicBrainz(Lookup.Found(listOf(exact)))
            assertEquals("exact", resolve(alone, track("Band: Song", "Band, Guest")).recordingMbid)
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

    @Test
    fun `a composer, a soloist, an orchestra and a conductor are all searched, the composer last`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(nothing)
            resolve(musicBrainz, track("Mozart: Requiem", "$mozart, Soloist, Orchestra, Conductor"))
            assertEquals(listOf("Soloist", "Orchestra", "Conductor", mozart), musicBrainz.searched.map { it.second })
        }

    @Test
    fun `a fallback search's recording that credits a performer the player does not is not the track's`() =
        runTest {
            // The soloist's search finds nothing; the orchestra's finds its recording of the work with
            // another pianist, which fits the title and length.
            val other =
                recording("other", "Song", "Orchestra", 200, "1985")
                    .copy(credits = listOf(credit("Orchestra"), credit("Other Pianist")))
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["Orchestra"] = Lookup.Found(listOf(other)) }
            val resolution = resolve(musicBrainz, track("Song", "Soloist, Orchestra"))
            assertNull(resolution.recordingMbid)
            assertEquals(listOf("Soloist", "Orchestra"), musicBrainz.searched.map { it.second })
        }

    @Test
    fun `a fallback search's recording crediting only some of the player's artists is the track's`() =
        runTest {
            val ours = recording("ours", "Song", "Orchestra", 200, "1985")
            val musicBrainz = FakeMusicBrainz(nothing).apply { searchesBy["Orchestra"] = Lookup.Found(listOf(ours)) }
            assertEquals("ours", resolve(musicBrainz, track("Song", "Soloist, Orchestra, Conductor")).recordingMbid)
        }

    @Test
    fun `the first search's recording may credit others besides, as before`() =
        runTest {
            val featuring =
                recording("feat", "Song", "A", 200, "1985").copy(credits = listOf(credit("A"), credit("Someone")))
            assertEquals(
                "feat",
                resolve(FakeMusicBrainz(Lookup.Found(listOf(featuring))), track("Song", "A, B")).recordingMbid,
            )
        }
}
