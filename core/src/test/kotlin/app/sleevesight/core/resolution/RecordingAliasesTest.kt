package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The aliases of a credited artist looked up with no composer leading the title (#1330): the composer
 * credited first among the artists, the orchestra under another name on MusicBrainz. The case itself
 * replays MusicBrainz's answers in `:sources` (`MusicBrainzAliasLookupTest`).
 */
class RecordingAliasesTest {
    // MusicBrainz's orchestra, whose aliases include the player's English name.
    private val orchestra = credit("Berliner Ensemble", "ensemble")

    // A recording credited to the orchestra and the conductor.
    private fun performance(
        mbid: String,
        seconds: Int,
        credits: List<MbCredit> = listOf(orchestra.copy(joinPhrase = " & "), credit("Ann Lee")),
    ) = recording(mbid, "Winter Rose", "Ann Lee", seconds, "2001").copy(credits = credits)

    private val conductor = credit("Ann Lee")

    private val track = track("Winter Rose", "Cy Bard, Berlin Ensemble, Ann Lee")

    @Test
    fun `a recording a later artist's search finds is taken by the alias of an orchestra it credits`() =
        runTest {
            // The searches by the composer and by the orchestra's English name find nothing; the conductor's
            // finds the recording, credited to the orchestra under its own name.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Ann Lee"] = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            val resolution = resolve(musicBrainz, track, 200)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("rose", resolution.recordingMbid)
            assertEquals("ensemble", resolution.artists[1].mbid)
            assertEquals(listOf("ensemble"), musicBrainz.artistNamesAsked)
            // The loose search first, which finds nothing.
            assertEquals(1, musicBrainz.looseSearched.size)
        }

    @Test
    fun `a track the loose search finds makes no alias lookup for what the searches by name left out`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Ann Lee"] = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.loose =
                Lookup.Found(
                    listOf(performance("trio", 200, listOf(credit("Cy Bard").copy(joinPhrase = " & "), conductor))),
                )
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            assertEquals("trio", resolve(musicBrainz, track, 200).recordingMbid)
            assertEquals(emptyList(), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `a recording the loose search finds is taken by the alias of an orchestra it credits`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.loose = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            val resolution = resolve(musicBrainz, track, 200)
            assertEquals("rose", resolution.recordingMbid)
            assertEquals("ensemble", resolution.artists[1].mbid)
            assertEquals(listOf("ensemble"), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `a recording whose other credit has no alias of the track's artists is not taken`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Ann Lee"] = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.loose = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Ensemble of Berlin")
            assertEquals(Outcome.None, resolve(musicBrainz, track, 200).outcome)
            // Looked up once, for both recordings.
            assertEquals(listOf("ensemble"), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `no recording whose length or title does not fit has its credits' aliases looked up`() =
        runTest {
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Ann Lee"] = Lookup.Found(listOf(performance("long", 260)))
            musicBrainz.loose = Lookup.Found(listOf(performance("long", 260)))
            assertEquals(Outcome.None, resolve(musicBrainz, track, 200).outcome)
            assertEquals(emptyList(), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `at most two artists' aliases are looked up for a track, the searches by name and the loose one together`() =
        runTest {
            // The conductor's search finds two recordings, each with another orchestra none of whose names is
            // the track's; the loose search a third, a second further from the track's length, with a third
            // orchestra, whose names are not asked.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Ann Lee"] =
                Lookup.Found(
                    listOf(
                        performance("a", 200, listOf(credit("Orchestra A", "a").copy(joinPhrase = " & "), conductor)),
                        performance("b", 201, listOf(credit("Orchestra B", "b").copy(joinPhrase = " & "), conductor)),
                    ),
                )
            musicBrainz.loose = Lookup.Found(listOf(performance("rose", 201)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            assertEquals(Outcome.None, resolve(musicBrainz, track, 200).outcome)
            assertEquals(listOf("a", "b"), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `a recording a later artist's search finds with other numbers in its title is not weighed`() =
        runTest {
            // `Gymnopédie No. 2` is as alike to `No. 1` as a title may be; the loose search's guard holds for it too.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Ann Lee"] =
                Lookup.Found(listOf(performance("two", 200).copy(title = "Gymnopédie No. 2")))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            val resolution = resolve(musicBrainz, track("Gymnopédie No. 1", "Cy Bard, Berlin Ensemble, Ann Lee"), 200)
            assertEquals(Outcome.None, resolution.outcome)
            assertEquals(emptyList(), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `an alias looked up before the loose search stands for the recording that search finds`() =
        runTest {
            // With a composer leading the title, the first pass looks up the orchestra's names on a recording
            // with a soloist the track does not credit, still not the track's; the loose search then finds the
            // orchestra's and the conductor's, which the alias makes theirs, with no lookup more.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            val soloist =
                listOf(orchestra.copy(joinPhrase = ", "), conductor.copy(joinPhrase = ", "), credit("Dee Ray"))
            musicBrainz.searchesBy["Berlin Ensemble"] = Lookup.Found(listOf(performance("solo", 200, soloist)))
            musicBrainz.loose = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            val resolution = resolve(musicBrainz, track("Bard: Winter Rose", "Cy Bard, Berlin Ensemble, Ann Lee"), 200)
            assertEquals("rose", resolution.recordingMbid)
            assertEquals(listOf("ensemble", "mbid-Dee Ray"), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `a part of a work credited under an honorific variant is kept, with no alias lookup`() =
        runTest {
            // The first search finds the aria, credited to `Sir Ann Lee`, which resolves the track; the
            // conductor's search finds the orchestra's recording, which must not displace it.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            musicBrainz.searchesBy["Cy Bard"] =
                Lookup.Found(
                    listOf(
                        recording(
                            "aria",
                            "Opera: “Winter Rose”",
                            "Ann Lee",
                            200,
                            "2001",
                        ).copy(credits = listOf(credit("Sir Ann Lee"))),
                    ),
                )
            musicBrainz.searchesBy["Ann Lee"] = Lookup.Found(listOf(performance("rose", 200)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            val before =
                resolve(
                    FakeMusicBrainz(search = Lookup.Found(emptyList())).also {
                        it.searchesBy["Cy Bard"] = musicBrainz.searchesBy.getValue("Cy Bard")
                    },
                    track,
                    200,
                )
            val resolution = resolve(musicBrainz, track, 200)
            assertEquals("aria", before.recordingMbid)
            assertEquals("aria", resolution.recordingMbid)
            assertEquals(emptyList(), musicBrainz.artistNamesAsked)
        }

    @Test
    fun `a recording credited to the composer and the orchestra is not taken by the orchestra's alias`() =
        runTest {
            // Another conductor's performance, its credit naming the composer and the orchestra only: the
            // composer, credited first, is no performer, so the orchestra's alias alone does not make it theirs.
            val musicBrainz = FakeMusicBrainz(search = Lookup.Found(emptyList()))
            val credits = listOf(credit("Cy Bard").copy(joinPhrase = "; "), orchestra)
            musicBrainz.searchesBy["Berlin Ensemble"] = Lookup.Found(listOf(performance("other", 200, credits)))
            musicBrainz.artistNames["ensemble"] = listOf("Berliner Ensemble", "Berlin Ensemble")
            assertEquals(Outcome.None, resolve(musicBrainz, track, 200).outcome)
            assertEquals(listOf("ensemble"), musicBrainz.artistNamesAsked)
        }
}
