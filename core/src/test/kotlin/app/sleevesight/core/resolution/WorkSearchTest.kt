package app.sleevesight.core.resolution

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The work of a recording that links none, searched for among its composer's works (#1299): only with a
 * composer the title leads with, only a work that composer's relationship names whose title fits the
 * track's, numbers included. The Barber case itself replays MusicBrainz's answers in `:sources`
 * (`MusicBrainzClassicalFoundTest`).
 */
class WorkSearchTest {
    private val barber = "Samuel Barber"

    // Rattle and the orchestra's recording of [title], linking [work] (none by default).
    private fun fake(
        title: String,
        work: String? = null,
    ): FakeMusicBrainz {
        val recording =
            recording("r", title, "Sir Simon Rattle", 537, "2009").let {
                val credits = listOf(credit("Sir Simon Rattle").copy(joinPhrase = " & "), credit("Berlin Philharmonic"))
                it.copy(credits = credits)
            }
        val detail =
            MbRecordingDetail(
                recording.mbid,
                recording.title,
                recording.credits,
                recording.firstReleaseDate,
                listOfNotNull(work?.let { MbWorkLink(it, title, cover = false) }),
                emptyList(),
                emptyList(),
            )
        return FakeMusicBrainz(
            search = Lookup.Found(listOf(recording)),
            recordings = mapOf("r" to detail),
            works = (listOfNotNull(work) + FOUND).associateWith { work(it, title) },
        )
    }

    // A work [mbid] titled [title], [composer]'s.
    private fun work(
        mbid: String,
        title: String,
        composer: String = barber,
    ) = MbWork(
        mbid,
        title,
        listOf(MbArtistRelation("composer", "mbid-$composer", composer, emptyList(), begin = "1936")),
        emptyList(),
        emptyList(),
    )

    // A work as the search finds it: [title], by [composer] (and [others], by their relationships' types),
    // linking [recordings] recordings.
    private fun found(
        mbid: String,
        title: String,
        recordings: Int,
        composer: String = barber,
        others: Map<String, String> = emptyMap(),
    ) = MbFoundWork(
        mbid,
        title,
        null,
        listOf(MbArtistRelation("composer", "mbid-$composer", composer, emptyList())) +
            others.map { (type, name) -> MbArtistRelation(type, "mbid-$name", name, emptyList()) },
        recordings,
    )

    private fun barberTrack(title: String) =
        track("Barber: $title", "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic")

    @Test
    fun `a recording linking no work is given its composer's work of the title, the most recorded of them`() =
        runTest {
            val musicBrainz = fake("Adagio for Strings")
            musicBrainz.foundWorks =
                Lookup.Found(
                    listOf(
                        found("catch-all", "Adagio for Strings", 205),
                        found("piece", "Adagio for Strings", 313),
                        found("organ", "Adagio for Strings", 6, others = mapOf("arranger" to "William Strickland")),
                    ),
                )
            val resolution = resolve(musicBrainz, barberTrack("Adagio for Strings"), 537)
            assertEquals("r", resolution.recordingMbid)
            assertEquals("piece", resolution.work?.mbid)
            assertEquals(false, resolution.cover)
            assertEquals(false, resolution.provisional)
            assertEquals(listOf("Adagio for Strings" to barber), musicBrainz.worksSearched)
            assertEquals(listOf("piece"), musicBrainz.worksAsked)
            assertEquals("1936", resolution.composed?.from)
        }

    @Test
    fun `a work of the composer's whose title differs by its number, or is another, is not taken`() =
        runTest {
            // Barber's essays for orchestra: the first and the third are not the second.
            val musicBrainz = fake("Essay for Orchestra No. 2")
            musicBrainz.foundWorks =
                Lookup.Found(
                    listOf(
                        found("first", "Essay for Orchestra No. 1", 40),
                        found("third", "Third Essay for Orchestra", 12),
                        found("music", "Music for a Scene from Shelley", 9),
                    ),
                )
            val resolution = resolve(musicBrainz, barberTrack("Essay for Orchestra No. 2"), 537)
            assertEquals("r", resolution.recordingMbid)
            assertNull(resolution.work)
            assertEquals(1, musicBrainz.worksSearched.size)
            assertTrue(musicBrainz.worksAsked.isEmpty())
        }

    @Test
    fun `a work of the title whose composer is not the lead's is not taken`() =
        runTest {
            // The search matches any artist the work relates to: Barber as its arranger is not its composer.
            val musicBrainz = fake("Adagio for Strings")
            musicBrainz.foundWorks =
                Lookup.Found(
                    listOf(
                        found(
                            "other",
                            "Adagio for Strings",
                            50,
                            composer = "Tomaso Albinoni",
                            others = mapOf("arranger" to barber),
                        ),
                    ),
                )
            val resolution = resolve(musicBrainz, barberTrack("Adagio for Strings"), 537)
            assertNull(resolution.work)
            assertTrue(musicBrainz.worksAsked.isEmpty())
        }

    @Test
    fun `a recording that links its own work, or a track with no composer lead, makes no work search`() =
        runTest {
            val linked = fake("Adagio for Strings", work = "own")
            assertEquals("own", resolve(linked, barberTrack("Adagio for Strings"), 537).work?.mbid)
            assertTrue(linked.worksSearched.isEmpty())

            val pop =
                FakeMusicBrainz(
                    search = Lookup.Found(listOf(recording("p", "Winter Rose", "Ann Lee", 200, "2001"))),
                    recordings =
                        mapOf(
                            "p" to
                                MbRecordingDetail(
                                    "p",
                                    "Winter Rose",
                                    listOf(credit("Ann Lee")),
                                    "2001",
                                    emptyList(),
                                    emptyList(),
                                    emptyList(),
                                ),
                        ),
                )
            assertNull(resolve(pop, track("Winter Rose", "Ann Lee"), 200).work)
            assertTrue(pop.worksSearched.isEmpty())
        }

    @Test
    fun `a work search that fails leaves the recording kept, provisional, with no work`() =
        runTest {
            val musicBrainz = fake("Adagio for Strings")
            musicBrainz.foundWorks = Lookup.Failed("Timeout")
            val resolution = resolve(musicBrainz, barberTrack("Adagio for Strings"), 537)
            assertEquals("r", resolution.recordingMbid)
            assertNull(resolution.work)
            assertEquals(true, resolution.provisional)
        }

    private companion object {
        // The works the tests' searches find, which a lookup answers.
        val FOUND = listOf("catch-all", "piece", "organ", "first", "third", "music", "other")
    }
}
