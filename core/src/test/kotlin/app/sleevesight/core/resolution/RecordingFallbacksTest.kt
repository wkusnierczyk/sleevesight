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
