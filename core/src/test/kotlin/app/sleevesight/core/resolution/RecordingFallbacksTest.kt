package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmAccentFolder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // A recording's work lookup naming [composer] its composer.
    private fun work(composer: String) =
        MbWork(
            "w",
            "Winter Rose",
            listOf(MbArtistRelation("composer", "mbid-$composer", composer, emptyList())),
            emptyList(),
            emptyList(),
        )

    @Test
    fun `a pop recording that links no work is not lent the work the artist's live one links, a song`() =
        runTest {
            // Neither a composer lead nor a classical work: a pop match keeps no work, whatever the other links.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Live: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Song")),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertNull(resolution.work)
        }

    @Test
    fun `a title leading with a credited artist is no classical context - a song they did not compose is not lent`() =
        runTest {
            // `Ann Lee: Winter Rose` names a credited artist before a colon, as `Band: Song` does; the song
            // `Ann Lee: Live: “Winter Rose”` links is Cy Kay's, so it is not this track's composer's work.
            val plain = duo("plain", "Ann Lee: Winter Rose", 200)
            val piece = duo("piece", "Ann Lee: Live: “Winter Rose”", 201)
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Song")),
                    works = mapOf("w" to work("Cy Kay")),
                )
            val resolution = resolve(musicBrainz, track("Ann Lee: Winter Rose", "Ann Lee, Bo Day"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertNull(resolution.work)
            // The same work composed by the artist the title leads with is lent.
            val theirs = FakeMusicBrainz(musicBrainz.search, musicBrainz.recordings, mapOf("w" to work("Ann Lee")))
            val lent = resolve(theirs, track("Ann Lee: Winter Rose", "Ann Lee, Bo Day"), 200)
            assertEquals("plain", lent.recordingMbid)
            assertEquals("w", lent.work?.mbid)
        }

    @Test
    fun `a duet keeps both its artists and is lent no work by a part of a work credited to one of them`() =
        runTest {
            val plain = duo("plain", "Winter Rose", 200)
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertEquals(listOf("mbid-Ann Lee", "mbid-Bo Day"), resolution.artists.map { it.mbid })
            assertNull(resolution.work)
        }

    @Test
    fun `a recording linking no work is lent the aria its artists' part of a work links, and is kept`() =
        runTest {
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertEquals("2001", resolution.firstReleaseDate)
            assertEquals("w", resolution.work?.mbid)
        }

    @Test
    fun `a conductor the chosen recording names keeps his id when the work is lent`() =
        runTest {
            // Both credit Ann Lee alone; the chosen recording names Bo Day its conductor, the other does not.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val conducted =
                detail(plain, null).copy(
                    artistRelations = listOf(MbArtistRelation("conductor", "bo-id", "Bo Day", emptyList())),
                )
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to conducted, "piece" to detail(piece, "w", "Aria")),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee, Bo Day"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertEquals(listOf("mbid-Ann Lee", "bo-id"), resolution.artists.map { it.mbid })
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
    fun `the chosen recording's lookup failing once, answered when asked again, leaves it provisional`() =
        runTest {
            // Whether it links a work was not known when the others were weighed.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                )
            musicBrainz.failingOnce += "plain"
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertTrue(resolution.provisional)
        }

    @Test
    fun `the loose search's choice weighed with a lookup that failed is provisional`() =
        runTest {
            // Two recordings by the pair: the later one's relationships could not be weighed.
            val early = duo("early", "Winter Rose", 200).copy(firstReleaseDate = "1999")
            val late = duo("late", "Winter Rose", 200).copy(firstReleaseDate = "2005")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(emptyList()),
                    recordings = mapOf("early" to detail(early, null)),
                )
            musicBrainz.loose = Lookup.Found(listOf(early, late))
            musicBrainz.failingRecordings += "late"
            val resolution = resolve(musicBrainz, track("Winter Rse", "Ann Lee, Bo Day"), 200)
            assertEquals("early", resolution.recordingMbid)
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
    fun `a composer lead is lent only that composer's work, of any type - Schubert's Ave Maria, no Otello aria`() =
        runTest {
            val plain = recording("plain", "Ave Maria", "Angela Gheorghiu", 300, "2001")
            val piece = recording("piece", "Otello: Atto IV. “Ave Maria”", "Angela Gheorghiu", 303, "1995")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                    works = mapOf("w" to work("Giuseppe Verdi")),
                )
            val resolution =
                resolve(musicBrainz, track("Schubert: Ave Maria", "Franz Schubert, Angela Gheorghiu"), 300)
            assertEquals("plain", resolution.recordingMbid)
            assertNull(resolution.work)
        }

    @Test
    fun `the loose search's title differing by a number is no match - Gymnopedie No 1 and No 2, Part 1 and Part 2`() =
        runTest {
            for ((wanted, found) in listOf(
                "Gymnopédie No. 1" to "Gymnopédie No. 2",
                "Winter Rose Part 1" to "Winter Rose Part 2",
            )) {
                val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
                musicBrainz.loose = Lookup.Found(listOf(duo("other", found, 200)))
                val resolution = resolve(musicBrainz, track(wanted, "Ann Lee, Bo Day"), 200)
                assertEquals(1, musicBrainz.looseSearched.size, wanted)
                assertNull(resolution.recordingMbid, wanted)
            }
        }

    @Test
    fun `a namesake's part of a work lends no work`() =
        runTest {
            // Another Ann Lee: the same track artist stood for, but not the same person.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece =
                recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
                    .copy(credits = listOf(credit("Ann Lee", "other-ann")))
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to detail(piece, "w", "Aria")),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("plain", resolution.recordingMbid)
            assertNull(resolution.work)
        }

    @Test
    fun `a lent work is this recording's own performance of it - no cover, not instrumental, no languages`() =
        runTest {
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val piece = recording("piece", "Opera: “Winter Rose”", "Ann Lee", 201, "1999")
            val other =
                detail(piece, null).copy(
                    works =
                        listOf(
                            MbWorkLink(
                                "w",
                                "Winter Rose",
                                cover = true,
                                languages = listOf("ita"),
                                instrumental = true,
                                type = "Aria",
                            ),
                        ),
                )
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, piece)),
                    recordings = mapOf("plain" to detail(plain, null), "piece" to other),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertEquals("w", resolution.work?.mbid)
            assertFalse(resolution.cover)
            assertFalse(resolution.lyrics.instrumental)
            assertEquals(emptyList(), resolution.lyrics.languages)
        }

    @Test
    fun `without a composer lead only the first part of a work found is weighed for a work to lend`() =
        runTest {
            // The first links a song, which is not lent; the second, linking an aria, is not looked up.
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val first = recording("first", "Live: “Winter Rose”", "Ann Lee", 201, "1990")
            val second = recording("second", "Opera: “Winter Rose”", "Ann Lee", 201, "1995")
            val musicBrainz =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(plain, first, second)),
                    recordings =
                        mapOf(
                            "plain" to detail(plain, null),
                            "first" to detail(first, "s", "Song"),
                            "second" to detail(second, "w", "Aria"),
                        ),
                )
            val resolution = resolve(musicBrainz, track("Winter Rose", "Ann Lee"), 200)
            assertNull(resolution.work)
            assertEquals(listOf("plain", "first"), musicBrainz.recordingsAsked)
        }

    @Test
    fun `a track resolved under an honorific variant makes no loose search and keeps its recording`() =
        runTest {
            val plain = recording("plain", "Winter Rose", "Ann Lee", 200, "2001")
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(listOf(plain)))
            musicBrainz.loose = Lookup.Found(listOf(duo("other", "Winter Rose", 200)))
            val resolution = resolve(musicBrainz, track("Winter Rose", "Mr. Ann Lee, Bo Day"), 200)
            assertEquals(emptyList(), musicBrainz.looseSearched)
            assertEquals("plain", resolution.recordingMbid)
        }

    @Test
    fun `a medley quoting two titles, or a part of a work of no length beside the player's, is no match`() =
        runTest {
            val medley = recording("medley", "Medley: “Yesterday” / “Michelle”", "Ann Lee", 200, "2001")
            assertNull(
                resolve(
                    FakeMusicBrainz(Lookup.Found(listOf(medley))),
                    track("Yesterday", "Ann Lee"),
                    200,
                ).recordingMbid,
            )
            val lengthless = recording("live", "Live: “Yesterday”", "Ann Lee", null, "2001")
            assertNull(
                resolve(
                    FakeMusicBrainz(Lookup.Found(listOf(lengthless))),
                    track("Yesterday", "Ann Lee"),
                    200,
                ).recordingMbid,
            )
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
