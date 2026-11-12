// Lengths and dates are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.core.resolution

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A date known only to the year or the month (#1203): not earlier than a day in it, and, on a
 * recording with no dated release listed, still the track's first-release date.
 */
class RecordingResolverYearOnlyTest {
    private suspend fun chosen(search: List<MbRecording>): String? =
        resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Artist")).recordingMbid

    @Test
    fun `a recording dated by the year alone does not beat one dated to a day in that year`() =
        runTest {
            val search =
                listOf(
                    recording("compilation", "Song", "Artist", 200, "2015"),
                    recording("single", "Song", "Artist", 200, "2015-08-07"),
                )
            assertEquals("single", chosen(search))
        }

    @Test
    fun `a recording dated by the month alone does not beat one dated to a day in that month`() =
        runTest {
            val search =
                listOf(
                    recording("compilation", "Song", "Artist", 200, "2015-08"),
                    recording("single", "Song", "Artist", 200, "2015-08-07"),
                )
            assertEquals("single", chosen(search))
        }

    @Test
    fun `a year alone still beats a day in a later year`() =
        runTest {
            val search =
                listOf(
                    recording("a-later", "Song", "Artist", 200, "2016-01-01"),
                    recording("z-earlier", "Song", "Artist", 200, "2015"),
                )
            // Ids that order the other way, so only the date picks it.
            assertEquals("z-earlier", chosen(search))
        }

    @Test
    fun `a recording whose listed releases are undated keeps its own first-release date`() =
        runTest {
            // *FIFA 16 Soundtrack*'s *Way Down We Go*: MusicBrainz dates the recording `2015`, and its
            // search lists its one release undated.
            val undated =
                recording("compilation", "Song", "Artist", 200, "2015").let { recording ->
                    recording.copy(releases = recording.releases.map { it.copy(date = null) })
                }
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(listOf(undated))), track("Song", "Artist"))
            assertNull(resolution.firstRelease)
            assertEquals("2015", resolution.firstReleaseDate)
        }
}
