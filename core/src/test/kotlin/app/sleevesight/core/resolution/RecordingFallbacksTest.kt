package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmAccentFolder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bounded fallbacks of #1289 leave pop tracks as they were: the loose search, the reading of a title
 * as a part of a work, and the work looked for among the artist's other recordings. The classical cases
 * themselves replay MusicBrainz's answers in `:sources` (`MusicBrainzClassicalFoundTest`).
 */
class RecordingFallbacksTest {
    private val names = NameMatcher(JvmAccentFolder)

    private fun duo(
        mbid: String,
        title: String,
        seconds: Int,
    ) = recording(mbid, title, "Ann Lee", seconds, "2001").let {
        it.copy(credits = listOf(credit("Ann Lee").copy(joinPhrase = " & "), credit("Bo Day")))
    }

    @Test
    fun `the loose search's song of another title by the same artists, at the same length, is no match`() =
        runTest {
            // A duet MusicBrainz does not have: the loose search finds the pair's other song, as long.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.loose = Lookup.Found(listOf(duo("other", "Summer Rain", 200)))
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), 200)
            assertEquals(Outcome.None, resolution.outcome)
            assertEquals(listOf(listOf("winter", "rose") to listOf("Ann Lee", "Bo Day")), musicBrainz.looseSearched)
        }

    @Test
    fun `the loose search finds a title misspelled by a letter, by two of the track's artists`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.loose = Lookup.Found(listOf(duo("rose", "Winter Rose", 200)))
            val resolution = resolve(musicBrainz, track("Winter Rse", "Ann Lee, Bo Day"), 200)
            assertEquals("rose", resolution.recordingMbid)
        }

    @Test
    fun `a recording the loose search finds credited to one of the track's artists alone is no match`() =
        runTest {
            // One name and a length are too little beside a title found loosely.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.loose = Lookup.Found(listOf(recording("solo", "Winter Rose", "Ann Lee", 200, "2001")))
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), 200)
            assertEquals(Outcome.None, resolution.outcome)
        }

    @Test
    fun `a track by one artist, or with no length, makes no loose search`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), null)
            assertEquals(emptyList(), musicBrainz.looseSearched)
        }

    @Test
    fun `a track whose recording fits makes no loose search`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(listOf(duo("rose", "Winter Rose", 200))))
            assertEquals("rose", resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), 200).recordingMbid)
            assertEquals(emptyList(), musicBrainz.looseSearched)
        }

    @Test
    fun `a pop recording that links a work is kept, and the artist's other recordings are not looked up`() =
        runTest {
            // `Live: “Winter Rose”` reads as a part of a work, and fits; the recording read as written comes
            // first, and links a work, so nothing else is asked.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Live: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings =
                        mapOf(
                            "plain" to
                                MbRecordingDetail(
                                    "plain",
                                    "Winter Rose",
                                    plain.credits,
                                    "2001",
                                    listOf(MbWorkLink("w", "Winter Rose", cover = false)),
                                    emptyList(),
                                    emptyList(),
                                ),
                        ),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertEquals(listOf("plain"), musicBrainz.recordingsAsked)
        }

    // A recording's detail linking [work] (none for null) of [type].
    private fun detail(
        r: MbRecording,
        work: String?,
        type: String? = null,
    ) = MbRecordingDetail(
        r.mbid,
        r.title,
        r.credits,
        r.firstReleaseDate,
        listOfNotNull(work?.let { MbWorkLink(it, r.title, cover = false, type = type) }),
        emptyList(),
        emptyList(),
    )

    @Test
    fun `a pop recording that links no work is not replaced by the artist's live one that links a song`() =
        runTest {
            // Neither a composer lead nor a classical work: a pop match stays, whatever the other links.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Live: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Song")),
                )
            assertEquals("plain", resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200).recordingMbid)
        }

    @Test
    fun `a duet is not replaced by a part of a work credited to one of its two artists`() =
        runTest {
            // The aria is a classical work, but its recording names Ann Lee alone: Bo Day's identity would
            // be lost.
            val plain = duo("plain", "Winter Rose", 200)
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                )
            assertEquals("plain", resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), 200).recordingMbid)
        }

    @Test
    fun `a part of a work by the same artists, linking an aria, stands in for a recording that links none`() =
        runTest {
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("piece", resolution.recordingMbid)
            assertEquals("w", resolution.work?.mbid)
        }

    @Test
    fun `a part of a work whose lookup fails leaves the recording kept provisional`() =
        runTest {
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null)),
                )
            musicBrainz.failingRecordings += "piece"
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertTrue(resolution.provisional)
        }

    @Test
    fun `the loose search's recording by the two artists of one joint credit stands for one artist, no match`() =
        runTest {
            // `Ann Lee & Bo Day` is one of the track's two artists: a recording crediting Ann Lee and Bo Day
            // stands for that one alone.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.loose = Lookup.Found(listOf(duo("rose", "Winter Rose", 200)))
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee & Bo Day, Cy Kay"), 200)
            assertEquals(1, musicBrainz.looseSearched.size)
            assertNull(resolution.recordingMbid)
        }

    @Test
    fun `a title is read as a part of a work only in MusicBrainz's form for one, the part in quotes`() {
        assertEquals(1.0, names.pieceSimilarity("Nessun Dorma", "Turandot: “Nessun dorma”"))
        assertEquals(1.0, names.pieceSimilarity("Nessun Dorma", "Turandot: Atto III. “Nessun dorma!”"))
        // The work after a dash, or before a colon, is left out; one letter missing still fits.
        val misspelled =
            assertNotNull(names.pieceSimilarity("Nessun doma - Turandot", "Turandot: “Nessun dorma” (Vincerò)"))
        assertTrue(misspelled >= ResolutionThresholds.TITLE_SIMILARITY, "$misspelled")
        assertEquals(1.0, names.pieceSimilarity("Turandot: Nessun dorma", "Turandot: Atto III. „Nessun dorma“"))
        // No quoted part, or no work before a colon: not a part of a work.
        assertNull(names.pieceSimilarity("Song", "Interlude: Song"))
        assertNull(names.pieceSimilarity("Song", "“Song”"))
        // Another part's title does not fit.
        val other = assertNotNull(names.pieceSimilarity("Song", "Live: “Another Song Entirely”"))
        assertTrue(other < ResolutionThresholds.TITLE_SIMILARITY, "$other")
    }
}
