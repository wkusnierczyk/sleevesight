package io.github.wkusnierczyk.sleevesight.core.resolution

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The resolver's reading of a recording's work: covers, originals and who recorded it first. */
class RecordingWorkTest {
    @Test
    fun `an earlier recording of the work by another artist makes a cover, even unmarked`() =
        runTest {
            val search = listOf(recording("ours", "Song", "Cover Act", 200, "1978-09-18"))
            val detail =
                MbRecordingDetail(
                    "ours",
                    "Song",
                    listOf(credit("Cover Act")),
                    "1978-09-18",
                    listOf(MbWorkLink("w", "Song", cover = false)),
                    emptyList(),
                    listOf("Q1"),
                )
            val work =
                MbWork(
                    mbid = "w",
                    title = "Song",
                    artistRelations = listOf(MbArtistRelation("composer", "mbid-writer", "Writer", emptyList())),
                    wikidataIds = listOf("Q2"),
                    performances =
                        listOf(
                            MbPerformance("ours", "Song", listOf(credit("Cover Act")), null, emptyList()),
                            MbPerformance("first", "Song", listOf(credit("Original Act")), "1975", emptyList()),
                            MbPerformance("later", "Song", listOf(credit("Later Act")), "1990", emptyList()),
                        ),
                )
            val musicBrainz = FakeMusicBrainz(Lookup.Found(search), mapOf("ours" to detail), mapOf("w" to work))
            val resolution = resolve(musicBrainz, track("Song", "Cover Act"))
            assertTrue(resolution.cover)
            assertFalse(resolution.earliest)
            assertEquals("first", resolution.original?.recordingMbid)
            assertEquals("Original Act", resolution.original?.artistCredit)
            assertEquals(listOf("Q1", "Q2"), resolution.wikidataIds)
        }

    @Test
    fun `the original artist's own recording is not a cover`() =
        runTest {
            val search = listOf(recording("ours", "Song", "Writer", 200, "1974"))
            val detail =
                MbRecordingDetail(
                    "ours",
                    "Song",
                    listOf(credit("Writer")),
                    "1974",
                    listOf(MbWorkLink("w", "Song", cover = false)),
                    emptyList(),
                    emptyList(),
                )
            val work =
                MbWork(
                    "w",
                    "Song",
                    emptyList(),
                    emptyList(),
                    listOf(
                        MbPerformance("ours", "Song", listOf(credit("Writer")), "1974-03", emptyList()),
                        MbPerformance("later", "Song", listOf(credit("Later Act")), "1990", emptyList()),
                    ),
                )
            val resolution =
                resolve(
                    FakeMusicBrainz(Lookup.Found(search), mapOf("ours" to detail), mapOf("w" to work)),
                    track("Song", "Writer"),
                )
            assertFalse(resolution.cover)
            assertNull(resolution.original)
            // Recorded 1974, before the only other dated recording (1990): the artist's own, first.
            assertTrue(resolution.earliest)
        }

    @Test
    fun `a collaborator the player does not name is not one of the track's artists`() =
        runTest {
            // The player names A; the recording credits A and B. B's own 1960 recording of the work is
            // someone else's: A was not first, and the 2000 recording is an unmarked cover of B's.
            val search =
                listOf(
                    recording("ours", "Song", "A", 200, "2000")
                        .copy(credits = listOf(credit("A"), credit("B"))),
                )
            val detail =
                MbRecordingDetail(
                    "ours",
                    "Song",
                    listOf(credit("A"), credit("B")),
                    "2000",
                    listOf(MbWorkLink("w", "Song", cover = false)),
                    emptyList(),
                    emptyList(),
                )
            val work =
                MbWork(
                    "w",
                    "Song",
                    emptyList(),
                    emptyList(),
                    listOf(
                        MbPerformance("ours", "Song", listOf(credit("A"), credit("B")), "2000", emptyList()),
                        MbPerformance("b", "Song", listOf(credit("B")), "1960", emptyList()),
                        MbPerformance("c", "Song", listOf(credit("C")), "1980", emptyList()),
                    ),
                )
            val resolution =
                resolve(
                    FakeMusicBrainz(Lookup.Found(search), mapOf("ours" to detail), mapOf("w" to work)),
                    track("Song", "A"),
                )
            assertFalse(resolution.earliest)
            assertTrue(resolution.cover)
            assertEquals("b", resolution.original?.recordingMbid)
        }

    @Test
    fun `a release date alone does not make the track's artists first`() =
        runTest {
            // Released 1974 but undated as a recording: others' recordings carry no release date to set
            // against it, so the 1990 recording does not show ours to be first.
            val search = listOf(recording("ours", "Song", "Cover Act", 200, "1974"))
            val detail =
                MbRecordingDetail(
                    "ours",
                    "Song",
                    listOf(credit("Cover Act")),
                    "1974",
                    listOf(MbWorkLink("w", "Song", cover = false)),
                    emptyList(),
                    emptyList(),
                )
            val work =
                MbWork(
                    "w",
                    "Song",
                    emptyList(),
                    emptyList(),
                    listOf(
                        MbPerformance("first", "Song", listOf(credit("Original Act")), null, emptyList()),
                        MbPerformance("later", "Song", listOf(credit("Later Act")), "1990", emptyList()),
                    ),
                )
            val resolution =
                resolve(
                    FakeMusicBrainz(Lookup.Found(search), mapOf("ours" to detail), mapOf("w" to work)),
                    track("Song", "Cover Act"),
                )
            assertFalse(resolution.cover)
            assertFalse(resolution.earliest)
        }

    private fun ownSong(performances: List<MbPerformance>): FakeMusicBrainz {
        val search = listOf(recording("ours", "Song", "Writer", 200, "2005"))
        val detail =
            MbRecordingDetail(
                "ours",
                "Song",
                listOf(credit("Writer")),
                "2005",
                listOf(MbWorkLink("w", "Song", cover = false)),
                emptyList(),
                emptyList(),
            )
        return FakeMusicBrainz(
            Lookup.Found(search),
            mapOf("ours" to detail),
            mapOf(
                "w" to MbWork("w", "Song", emptyList(), emptyList(), performances),
            ),
        )
    }

    @Test
    fun `an artist's own song is not a cover when an own recording of it is undated`() =
        runTest {
            // Only a 2005 recording was found; the artist's undated original and a 1990 cover are on the work.
            val musicBrainz =
                ownSong(
                    listOf(
                        MbPerformance("ours", "Song", listOf(credit("Writer")), null, emptyList()),
                        MbPerformance("first", "Song", listOf(credit("Writer")), null, emptyList()),
                        MbPerformance("cover", "Song", listOf(credit("Coverer")), "1990", emptyList()),
                    ),
                )
            val resolution = resolve(musicBrainz, track("Song", "Writer"))
            assertFalse(resolution.cover)
            assertNull(resolution.original)
        }

    @Test
    fun `a recording by someone else is earlier only if it predates every dated one of ours`() =
        runTest {
            val musicBrainz =
                ownSong(
                    listOf(
                        MbPerformance("first", "Song", listOf(credit("Writer")), "1970", emptyList()),
                        MbPerformance("cover", "Song", listOf(credit("Coverer")), "1990", emptyList()),
                    ),
                )
            val resolution = resolve(musicBrainz, track("Song", "Writer"))
            assertFalse(resolution.cover)
            assertNull(resolution.original)
        }

    @Test
    fun `a chosen recording with no date at all does not make a later cover the original`() =
        runTest {
            val search = listOf(recording("ours", "Song", "Writer", 200, null))
            val detail =
                MbRecordingDetail(
                    "ours",
                    "Song",
                    listOf(credit("Writer")),
                    null,
                    listOf(MbWorkLink("w", "Song", cover = false)),
                    emptyList(),
                    emptyList(),
                )
            val performances =
                listOf(
                    MbPerformance("ours", "Song", listOf(credit("Writer")), null, emptyList()),
                    MbPerformance("live", "Song", listOf(credit("Writer")), "2005", emptyList()),
                    MbPerformance("cover", "Song", listOf(credit("Coverer")), "1990", emptyList()),
                )
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(search),
                    mapOf("ours" to detail),
                    mapOf(
                        "w" to MbWork("w", "Song", emptyList(), emptyList(), performances),
                    ),
                )
            val resolution = resolve(musicBrainz, track("Song", "Writer"))
            assertFalse(resolution.cover)
            assertNull(resolution.original)
        }

    @Test
    fun `dates compare at the precision both have`() {
        assertTrue(RecordingResolver.isEarlier("1975", "1978-09-18"))
        assertFalse(RecordingResolver.isEarlier("1978", "1978-09-18"))
        assertFalse(RecordingResolver.isEarlier("1978-09-18", "1978"))
    }
}
