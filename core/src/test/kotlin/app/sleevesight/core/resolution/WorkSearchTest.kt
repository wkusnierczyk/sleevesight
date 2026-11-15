package app.sleevesight.core.resolution

import app.sleevesight.core.sources.Resolution
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The work of a recording that links none, searched for among its composer's works (#1299): only with a
 * composer the title leads with, only a work that composer's relationship names, no arrangement, whose
 * title fits the track's (a part's a part's, numbers included), and only when one alone does. The Barber
 * case itself replays MusicBrainz's answers in `:sources` (`MusicBrainzClassicalFoundTest`).
 */
class WorkSearchTest {
    private val barber = "Samuel Barber"

    // Rattle and the orchestra's recording of [title], linking [work] (none by default); every work the
    // search may find answers a lookup.
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

    // A work [mbid] titled [title], Barber's, composed in 1936.
    private fun work(
        mbid: String,
        title: String,
    ) = MbWork(
        mbid,
        title,
        listOf(MbArtistRelation("composer", "mbid-$barber", barber, emptyList(), begin = "1936")),
        emptyList(),
        emptyList(),
    )

    // A work as the search finds it: [title], by [composer], [others] by their relationships' types, with as
    // many aliases as Barber's Adagio has.
    private fun found(
        mbid: String,
        title: String,
        disambiguation: String? = null,
        composer: String = barber,
        others: Map<String, String> = emptyMap(),
    ) = MbFoundWork(
        mbid,
        title,
        null,
        disambiguation,
        listOf(MbArtistRelation("composer", "mbid-$composer", composer, emptyList())) +
            others.map { (type, name) -> MbArtistRelation(type, "mbid-$name", name, emptyList()) },
        aliases = 12,
    )

    // The track `Barber: [title]` by Barber, Rattle and the orchestra, resolved with the search finding [works].
    private suspend fun resolved(
        title: String,
        vararg works: MbFoundWork,
    ): Pair<Resolution, FakeMusicBrainz> {
        val musicBrainz = fake(title)
        musicBrainz.foundWorks = Lookup.Found(works.toList())
        val track = track("Barber: $title", "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic")
        return resolve(musicBrainz, track, 537) to musicBrainz
    }

    // The piece, a catch-all for its arrangements and an arrangement, as MusicBrainz has Barber's Adagio.
    private val piece = found("piece", "Adagio for Strings")
    private val catchAll = found("catch-all", "Adagio for Strings", "catch‐all for arrangements")
    private val organ =
        found(
            "organ",
            "Adagio for Strings",
            "for organ, Strickland",
            others = mapOf("arranger" to "William Strickland"),
        )

    @Test
    fun `a recording linking no work is given its composer's work of the title, not an arrangement of it`() =
        runTest {
            val (resolution, musicBrainz) = resolved("Adagio for Strings", catchAll, piece, organ)
            assertEquals("r", resolution.recordingMbid)
            assertEquals("piece", resolution.work?.mbid)
            assertEquals(false, resolution.cover)
            assertEquals(false, resolution.provisional)
            assertEquals(listOf("Adagio for Strings" to barber), musicBrainz.worksSearched)
            assertEquals(listOf("piece"), musicBrainz.worksAsked)
            assertEquals("1936", resolution.composed?.from)
        }

    @Test
    fun `the composer orchestrating their own work does not make it an arrangement`() =
        runTest {
            val own = found("piece", "Adagio for Strings", others = mapOf("orchestrator" to barber))
            assertEquals("piece", resolved("Adagio for Strings", own, organ).first.work?.mbid)
        }

    @Test
    fun `a catch-all or an arrangement is never taken, however much recorded, nor with the piece missing`() =
        runTest {
            // Without the piece among the works found, the catch-all and the arrangements are all that is left.
            val arranged =
                listOf(
                    catchAll,
                    found("catch-all-2", "Adagio for Strings", "Catch-all for arrangements"),
                    organ,
                    found(
                        "woodwind",
                        "Adagio for Strings",
                        "arr. John O'Reilly for woodwind band",
                        others = mapOf("arranger" to "John O'Reilly"),
                    ),
                    found("piano", "Adagio for Strings", others = mapOf("vocal arranger" to "Cy Kay")),
                    found("band", "Adagio for Strings", others = mapOf("instrument arranger" to "Ann Lee")),
                    found("strings", "Adagio for Strings", others = mapOf("orchestrator" to "Bo Day")),
                )
            for (work in arranged) {
                val (resolution, musicBrainz) = resolved("Adagio for Strings", work)
                assertNull(resolution.work, work.mbid)
                assertTrue(musicBrainz.worksAsked.isEmpty(), work.mbid)
            }
        }

    @Test
    fun `a disambiguation naming arrangements does not make the original one, only a catch-all's does`() =
        runTest {
            for (said in listOf("original orchestration", "original version, not an arrangement")) {
                val original = found("piece", "Adagio for Strings", said, others = mapOf("orchestrator" to barber))
                assertEquals("piece", resolved("Adagio for Strings", original).first.work?.mbid, said)
            }
            for (said in listOf("catch‐all for arrangements", "catch-all", "Catchall")) {
                assertNull(resolved("Adagio for Strings", found("all", "Adagio for Strings", said)).first.work, said)
            }
        }

    @Test
    fun `a work known by fewer than three names is not taken for the piece`() =
        runTest {
            for (aliases in 0 until CANONICAL_ALIASES) {
                val (resolution, musicBrainz) =
                    resolved(
                        "Adagio for Strings",
                        found("piece", "Adagio for Strings").copy(aliases = aliases),
                    )
                assertNull(resolution.work, "$aliases")
                assertTrue(musicBrainz.worksAsked.isEmpty())
            }
            assertEquals(
                "piece",
                resolved("Adagio for Strings", found("piece", "Adagio for Strings").copy(aliases = 3)).first.work?.mbid,
            )
        }

    @Test
    fun `a work another composed or wrote too is not taken, one with a lyricist is`() =
        runTest {
            // `Für Elise`, production music version, credits Giscard Rasquin as a composer beside Beethoven.
            for (type in listOf("composer", "writer")) {
                val shared = found("shared", "Adagio for Strings", others = mapOf(type to "Giscard Rasquin"))
                assertNull(resolved("Adagio for Strings", shared).first.work, type)
            }
            // An art song or an aria names its lyricist: no reason to doubt it is the piece.
            val song = found("song", "Adagio for Strings", others = mapOf("lyricist" to "Ann Lee"))
            assertEquals("song", resolved("Adagio for Strings", song).first.work?.mbid)
        }

    @Test
    fun `the composer's own arrangement is an arrangement - Ravel's Bolero for two pianos`() =
        runTest {
            val pianos =
                found("pianos", "Adagio for Strings", "arr. Barber for 2 pianos", others = mapOf("arranger" to barber))
            assertNull(resolved("Adagio for Strings", pianos).first.work)
        }

    @Test
    fun `a disambiguation naming one of the work's other artists marks a version of it`() =
        runTest {
            val kreisler =
                found(
                    "kreisler",
                    "Adagio for Strings",
                    "with lyrics by Georg Kreisler",
                    others =
                        mapOf(
                            "lyricist" to "Georg Kreisler",
                        ),
                )
            assertNull(resolved("Adagio for Strings", kreisler).first.work)
            // Naming the composer, or someone the work does not relate to, is no version.
            val named =
                found(
                    "named",
                    "Adagio for Strings",
                    "Barber's own, premiered by Toscanini",
                    others =
                        mapOf(
                            "lyricist" to "Ann Lee",
                        ),
                )
            assertEquals("named", resolved("Adagio for Strings", named).first.work?.mbid)
        }

    @Test
    fun `two works that both fit leave the recording with none`() =
        runTest {
            val (resolution, musicBrainz) =
                resolved("Adagio for Strings", piece, found("other", "Adagio for Strings", "1936 version"))
            assertNull(resolution.work)
            assertTrue(musicBrainz.worksAsked.isEmpty())
        }

    @Test
    fun `a work of the composer's whose title differs by its number, or is another, is not taken`() =
        runTest {
            // Barber's essays for orchestra: the first and the third are not the second.
            val (resolution, musicBrainz) =
                resolved(
                    "Essay for Orchestra No. 2",
                    found("first", "Essay for Orchestra No. 1"),
                    found("third", "Third Essay for Orchestra"),
                    found("music", "Music for a Scene from Shelley"),
                )
            assertEquals("r", resolution.recordingMbid)
            assertNull(resolution.work)
            assertEquals(1, musicBrainz.worksSearched.size)
            assertTrue(musicBrainz.worksAsked.isEmpty())
        }

    @Test
    fun `a whole work's title takes no unnumbered movement of it, nor a movement's the whole work`() =
        runTest {
            // As alike as titles that fit, with the same numbers: the segments tell them apart, with or
            // without a colon before the whole work's subtitle.
            for (whole in listOf(
                "Variations on an Original Theme, op. 36 “Enigma”",
                "Variations on an Original Theme, op. 36: “Enigma”",
            )) {
                val movement = "$whole: Nimrod"
                assertNull(resolved(whole, found("nimrod", movement)).first.work, whole)
                assertNull(resolved(movement, found("enigma", whole)).first.work, whole)
                // Each its own.
                assertEquals(
                    "enigma",
                    resolved(whole, found("enigma", whole), found("nimrod", movement)).first.work?.mbid,
                    whole,
                )
                assertEquals(
                    "nimrod",
                    resolved(movement, found("enigma", whole), found("nimrod", movement)).first.work?.mbid,
                    whole,
                )
                // Another unnumbered movement of the same work is not this one.
                assertNull(resolved(movement, found("dorabella", "$whole: Dorabella")).first.work, whole)
            }
        }

    @Test
    fun `a colon in a catalogue number is no part's - Hob XVI 52`() =
        runTest {
            val sonata = "Piano Sonata in E-flat major, Hob. XVI:52"
            assertEquals(listOf(sonata), segments(sonata))
            assertEquals(listOf(sonata, "I. Allegro"), segments("$sonata: I. Allegro"))
            val (resolution, _) = resolved(sonata, found("sonata", sonata), found("first", "$sonata: I. Allegro"))
            assertEquals("sonata", resolution.work?.mbid)
        }

    @Test
    fun `a part of a work quoting the track's title is taken - an aria for its own title`() =
        runTest {
            val aria = found("aria", "Vanessa: Act I. “Must the winter come so soon?”")
            assertEquals("aria", resolved("Must the Winter Come So Soon?", aria).first.work?.mbid)
        }

    @Test
    fun `a work of the title whose composer is not the lead's is not taken`() =
        runTest {
            // The search matches any artist the work relates to: Barber as its arranger is not its composer.
            val other =
                found("other", "Adagio for Strings", composer = "Tomaso Albinoni", others = mapOf("arranger" to barber))
            val (resolution, musicBrainz) = resolved("Adagio for Strings", other)
            assertNull(resolution.work)
            assertTrue(musicBrainz.worksAsked.isEmpty())
        }

    @Test
    fun `a recording that links its own work, or a track with no composer lead, makes no work search`() =
        runTest {
            val linked = fake("Adagio for Strings", work = "own")
            val barberTrack =
                track("Barber: Adagio for Strings", "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic")
            assertEquals("own", resolve(linked, barberTrack, 537).work?.mbid)
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
            val track = track("Barber: Adagio for Strings", "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic")
            val resolution = resolve(musicBrainz, track, 537)
            assertEquals("r", resolution.recordingMbid)
            assertNull(resolution.work)
            assertEquals(true, resolution.provisional)
        }

    private companion object {
        // The works the tests' searches find, which a lookup answers.
        val FOUND =
            listOf(
                "piece",
                "catch-all",
                "catch-all-2",
                "organ",
                "woodwind",
                "piano",
                "band",
                "strings",
                "other",
                "first",
                "third",
                "music",
                "nimrod",
                "enigma",
                "sonata",
                "aria",
                "dorabella",
                "all",
                "shared",
                "song",
                "pianos",
                "kreisler",
                "named",
            )
    }
}
