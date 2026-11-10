package app.sleevesight.core.resolution

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A performance of a composed work is no cover of its earliest recording by someone else (#1178):
 * MusicBrainz's cover mark alone decides, and no recording is named as the original or as ours first.
 */
class RecordingComposedWorkTest {
    // Ours, recorded in 2024, of a work someone else recorded in 1949 and a third artist in 1960.
    private fun musicBrainz(
        work: MbWork,
        cover: Boolean = false,
        ourSession: String? = "2024-03",
        credits: List<MbCredit> = listOf(credit("Pianist")),
    ): FakeMusicBrainz {
        val ours = recording("ours", "Andante", "Pianist", 200, "2024-10-25").copy(credits = credits)
        val detail =
            MbRecordingDetail(
                "ours",
                "Andante",
                credits,
                "2024-10-25",
                listOf(MbWorkLink("w", "Andante", cover = cover)),
                emptyList(),
                emptyList(),
            )
        val performances =
            listOf(
                MbPerformance("ours", "Andante", credits, ourSession, emptyList()),
                MbPerformance("first", "Andante", listOf(credit("Earlier Pianist")), "1949", emptyList()),
                MbPerformance("later", "Andante", listOf(credit("Later Pianist")), "1960", emptyList()),
            )
        return FakeMusicBrainz(
            Lookup.Found(listOf(ours)),
            mapOf("ours" to detail),
            mapOf("w" to work.copy(performances = performances)),
        )
    }

    private fun work(
        type: String? = null,
        parents: List<MbParentWork> = emptyList(),
        composer: String = "Composer",
    ) = MbWork(
        mbid = "w",
        title = "Andante",
        artistRelations = listOf(MbArtistRelation("composer", "mbid-$composer", composer, emptyList(), "1788")),
        wikidataIds = emptyList(),
        performances = emptyList(),
        type = type,
        parents = parents,
    )

    @Test
    fun `the work's composer credited on the track but not on the recording - no cover, no original`() =
        runTest {
            val musicBrainz = musicBrainz(work(composer = "Wolfgang Amadeus Mozart"))
            val resolution = resolve(musicBrainz, track("Andante", "Wolfgang Amadeus Mozart, Pianist"))
            assertEquals("ours", resolution.recordingMbid)
            assertFalse(resolution.cover)
            assertNull(resolution.original)
            assertFalse(resolution.earliest)
            // With no original, its releases are not looked up.
            assertEquals(emptyList(), musicBrainz.releasesAsked)
        }

    @Test
    fun `a work of a classical form - no cover, no original`() =
        runTest {
            val resolution = resolve(musicBrainz(work(type = "Sonata")), track("Andante", "Pianist"))
            assertFalse(resolution.cover)
            assertNull(resolution.original)
        }

    @Test
    fun `a movement with no type of its own takes its parent's - no cover, no original`() =
        runTest {
            val parent = MbParentWork("sonata", "Sonata for Piano no. 16", "Sonata")
            val resolution = resolve(musicBrainz(work(parents = listOf(parent))), track("Andante", "Pianist"))
            assertFalse(resolution.cover)
            assertNull(resolution.original)
        }

    @Test
    fun `a work's own type decides before its parent's - a song from an opera is covered as a song`() =
        runTest {
            val parent = MbParentWork("opera", "A Rock Opera", "Opera")
            val resolution =
                resolve(musicBrainz(work(type = "Song", parents = listOf(parent))), track("Andante", "Pianist"))
            assertTrue(resolution.cover)
            assertEquals("first", resolution.original?.recordingMbid)
        }

    @Test
    fun `a composed work MusicBrainz marks as covered stays a cover, with no original named`() =
        runTest {
            val resolution = resolve(musicBrainz(work(type = "Aria"), cover = true), track("Andante", "Pianist"))
            assertTrue(resolution.cover)
            assertNull(resolution.original)
        }

    @Test
    fun `a composed work's first recording is not called the earliest`() =
        runTest {
            val musicBrainz = musicBrainz(work(type = "Concerto"), ourSession = "1940")
            val resolution = resolve(musicBrainz, track("Andante", "Pianist"))
            assertFalse(resolution.cover)
            assertFalse(resolution.earliest)
            assertNull(resolution.original)
        }

    @Test
    fun `a composer who is also credited on the recording does not make the work composed`() =
        runTest {
            // A writer the player credits beside the singer, on the recording too: as before, the
            // earlier recording by someone else makes this one a cover.
            val credits = listOf(credit("Pianist"), credit("Composer"))
            val resolution =
                resolve(musicBrainz(work(), credits = credits), track("Andante", "Pianist, Composer"))
            assertTrue(resolution.cover)
            assertEquals("first", resolution.original?.recordingMbid)
        }

    @Test
    fun `a song whose writer the track does not credit is still a cover by date`() =
        runTest {
            val resolution = resolve(musicBrainz(work(type = "Song")), track("Andante", "Pianist"))
            assertTrue(resolution.cover)
            assertEquals("first", resolution.original?.recordingMbid)
            assertEquals("Earlier Pianist", resolution.original?.artistCredit)
        }
}
