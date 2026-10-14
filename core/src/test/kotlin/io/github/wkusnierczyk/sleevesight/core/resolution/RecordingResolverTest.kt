package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingResolverTest {
    @Test
    fun `of the matching recordings the earliest released is chosen, not a later remaster`() =
        runTest {
            val search =
                listOf(
                    recording("remaster", "Song", "Artist", 201, "2026-07-13"),
                    recording("original", "Song", "Artist", 203, "1974-01"),
                    recording("live", "Song (Live)", "Artist", 230, "1970"),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Artist"))
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("original", resolution.recordingMbid)
            assertEquals("1974-01", resolution.firstReleaseDate)
            assertEquals(listOf(ResolvedArtist("Artist", "mbid-Artist")), resolution.artists)
        }

    @Test
    fun `a recording outside the duration tolerance is not accepted`() =
        runTest {
            val tolerance = ResolutionThresholds.DURATION_TOLERANCE.inWholeSeconds.toInt()
            val search = listOf(recording("long", "Song", "Artist", 200 + tolerance + 1, "1974"))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Artist"))
            assertEquals(Outcome.None, resolution.outcome)
            assertNull(resolution.recordingMbid)
        }

    @Test
    fun `titles match across punctuation, apostrophes and case`() =
        runTest {
            val search = listOf(recording("them", "Baby Please Don’t Go", "Them", 160, "1964-11-06"))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Baby, Please Don't Go", "Them"), 162)
            assertEquals("them", resolution.recordingMbid)
        }

    @Test
    fun `a band the artist leads is credited, the earliest chosen, and the band's id is not the artist's`() =
        runTest {
            // Misirlou (#77): the 1962 original is credited to the band, later releases to Dick Dale alone.
            val search =
                listOf(
                    recording("solo", "Misirlou", "Dick Dale", 136, "2012-09-19"),
                    recording("band", "Misirlou", "Dick Dale and His Del-Tones", 135, "1962-04"),
                )
            val detail =
                MbRecordingDetail(
                    mbid = "band",
                    title = "Misirlou",
                    credits = listOf(credit("Dick Dale and His Del-Tones")),
                    firstReleaseDate = "1962-04",
                    works = emptyList(),
                    artistRelations =
                        listOf(MbArtistRelation("instrument", "mbid-Dick Dale", "Dick Dale", listOf("guitar"))),
                    wikidataIds = emptyList(),
                )
            val withDetail = FakeMusicBrainz(Lookup.Found(search), recordings = mapOf("band" to detail))
            val resolution = resolve(withDetail, track("Misirlou", "Dick Dale"), 135)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("band", resolution.recordingMbid)
            assertEquals("Dick Dale and His Del-Tones", resolution.artistCredit)
            // From the performer relationship; never the band's own id.
            assertEquals(listOf(ResolvedArtist("Dick Dale", "mbid-Dick Dale")), resolution.artists)
            // Without a recording that shows Dick Dale in the band, the band is an unknown artist of
            // his name beside his solo recording: a possible collision, rejected.
            val unconfirmed = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Misirlou", "Dick Dale"), 135)
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Dick Dale have a matching recording"),
                unconfirmed.outcome,
            )
        }

    // [recording] (credited to a band), whose detail names [performers] (name to id) as performing on it.
    private fun banded(
        recording: MbRecording,
        vararg performers: Pair<String, String>,
    ): Pair<MbRecording, MbRecordingDetail> =
        recording to
            MbRecordingDetail(
                mbid = recording.mbid,
                title = recording.title,
                credits = recording.credits,
                firstReleaseDate = recording.firstReleaseDate,
                works = emptyList(),
                artistRelations = performers.map { (name, id) -> MbArtistRelation("vocal", id, name, emptyList()) },
                wikidataIds = emptyList(),
            )

    private fun searching(
        solo: List<MbRecording>,
        vararg bands: Pair<MbRecording, MbRecordingDetail>,
    ) = FakeMusicBrainz(
        Lookup.Found(solo + bands.map { it.first }),
        recordings = bands.associate { (recording, detail) -> recording.mbid to detail },
    )

    @Test
    fun `a band whose recording does not show the artist is not theirs, but may still collide`() =
        runTest {
            val musicBrainz =
                searching(
                    listOf(recording("echo", "The Killing Moon", "Echo", 335, "2019")),
                    banded(
                        recording("bunnymen", "The Killing Moon", "Echo & the Bunnymen", 333, "1984"),
                        "Ian McCulloch" to "mbid-ian",
                    ),
                )
            // Echo & the Bunnymen's recording does not show an Echo, so it cannot credit the singer
            // Echo; nor can it be ruled out as another Echo's (its leader may be unlisted): rejected.
            val resolution = resolve(musicBrainz, track("The Killing Moon", "Echo"), 335)
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Echo have a matching recording"),
                resolution.outcome,
            )
            // Alone, the band's recording is a near miss, as before band credits counted.
            val bandOnly =
                searching(
                    emptyList(),
                    banded(
                        recording("bunnymen", "The Killing Moon", "Echo & the Bunnymen", 333, "1984"),
                        "Ian McCulloch" to "mbid-ian",
                    ),
                )
            assertEquals(
                Outcome.Rejected("MusicBrainz has “The Killing Moon” by Echo & the Bunnymen, not by Echo"),
                resolve(bandOnly, track("The Killing Moon", "Echo"), 335).outcome,
            )
        }

    @Test
    fun `a namesake's solo recording and the real leader's band collide`() =
        runTest {
            val alexSmith =
                recording(
                    "solo",
                    "Song",
                    "Alex Smith",
                    200,
                    "2000",
                ).copy(credits = listOf(credit("Alex Smith", "artist-a")))
            val haley =
                searching(
                    listOf(recording("namesake", "Rock Around the Clock", "Bill Haley", 130, "2020")),
                    banded(
                        recording("comets", "Rock Around the Clock", "Bill Haley & His Comets", 130, "1954"),
                        "Bill Haley" to "mbid-real-haley",
                    ),
                )
            val rejected = resolve(haley, track("Rock Around the Clock", "Bill Haley"), 130)
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Bill Haley have a matching recording"),
                rejected.outcome,
            )
            assertNull(rejected.recordingMbid)
            // Alex Smith solo, and an orchestra of that name led by another Alex Smith.
            val smith =
                searching(
                    listOf(alexSmith),
                    banded(
                        recording("orchestra", "Song", "Alex Smith and His Orchestra", 200, "1960"),
                        "Alex Smith" to "artist-b",
                    ),
                )
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Alex Smith have a matching recording"),
                resolve(smith, track("Song", "Alex Smith"), 200).outcome,
            )
            // An orchestra whose recording names no Alex Smith is not confirmed his, nor ruled out.
            val unrelated =
                searching(
                    listOf(alexSmith),
                    banded(
                        recording("orchestra", "Song", "Alex Smith and His Orchestra", 200, "1960"),
                        "Someone Else" to "mbid-else",
                    ),
                )
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Alex Smith have a matching recording"),
                resolve(unrelated, track("Song", "Alex Smith"), 200).outcome,
            )
        }

    @Test
    fun `a leader under a variant of the name does not confirm the band`() =
        runTest {
            val alexSmith =
                recording(
                    "solo",
                    "Song",
                    "Alex Smith",
                    200,
                    "2000",
                ).copy(credits = listOf(credit("Alex Smith", "artist-a")))
            // The orchestra's leader as "Alexander Smith": unconfirmed, so a possible collision.
            val variant =
                searching(
                    listOf(alexSmith),
                    banded(
                        recording("orchestra", "Song", "Alex Smith and His Orchestra", 200, "1960"),
                        "Alexander Smith" to "artist-b",
                    ),
                )
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Alex Smith have a matching recording"),
                resolve(variant, track("Song", "Alex Smith"), 200).outcome,
            )
        }

    @Test
    fun `a namesake's solo recording beside the real leader's band unconfirmed within the cap collides`() =
        runTest {
            val rejected = Outcome.Rejected("several MusicBrainz artists named Bill Haley have a matching recording")
            val namesake = recording("namesake", "Rock Around the Clock", "Bill Haley", 130, "2020")
            // A Comets recording with no performers listed.
            val bare =
                searching(
                    listOf(namesake),
                    banded(recording("comets", "Rock Around the Clock", "Bill Haley & His Comets", 130, "1954")),
                )
            assertEquals(rejected, resolve(bare, track("Rock Around the Clock", "Bill Haley"), 130).outcome)
            // Four Comets recordings, Bill Haley listed only on the fourth, beyond the three tried.
            val comets =
                (1..4).map { n ->
                    banded(
                        recording("comets-$n", "Rock Around the Clock", "Bill Haley & His Comets", 130, "195$n"),
                        *(if (n == 4) arrayOf("Bill Haley" to "mbid-real-haley") else emptyArray()),
                    )
                }
            val capped = searching(listOf(namesake), *comets.toTypedArray())
            assertEquals(rejected, resolve(capped, track("Rock Around the Clock", "Bill Haley"), 130).outcome)
        }

    @Test
    fun `two same-name artists on one recording, or two leaders of one band, collide`() =
        runTest {
            val rejected = Outcome.Rejected("several MusicBrainz artists named Alex Smith have a matching recording")
            val joint =
                recording("joint", "Song", "Alex Smith", 200, "1970")
                    .copy(credits = listOf(credit("Alex Smith", "artist-a"), credit("Alex Smith", "artist-b")))
            assertEquals(
                rejected,
                resolve(FakeMusicBrainz(Lookup.Found(listOf(joint))), track("Song", "Alex Smith"), 200).outcome,
            )
            val twoLeaders =
                searching(
                    emptyList(),
                    banded(
                        recording("first", "Song", "Alex Smith and His Orchestra", 200, "1960"),
                        "Alex Smith" to "artist-a",
                    ),
                    banded(
                        recording("second", "Song", "Alex Smith and His Orchestra", 200, "1961"),
                        "Alex Smith" to "artist-b",
                    ),
                )
            assertEquals(rejected, resolve(twoLeaders, track("Song", "Alex Smith"), 200).outcome)
        }

    @Test
    fun `a band credit beside a credit by name collides unless the band's leader is the same artist`() =
        runTest {
            val rejected = Outcome.Rejected("several MusicBrainz artists named Alex Smith have a matching recording")
            val mixed =
                recording("mixed", "Song", "Alex Smith", 200, "1970").copy(
                    credits =
                        listOf(
                            credit("Alex Smith", "artist-a"),
                            credit("Alex Smith and His Orchestra", "band-b"),
                        ),
                )
            // The band's leader is another Alex Smith, or MusicBrainz does not say who leads it.
            for (performers in listOf(arrayOf("Alex Smith" to "artist-b"), emptyArray())) {
                val musicBrainz = searching(emptyList(), banded(mixed, *performers))
                assertEquals(rejected, resolve(musicBrainz, track("Song", "Alex Smith"), 200).outcome)
            }
            val same =
                resolve(
                    searching(emptyList(), banded(mixed, "Alex Smith" to "artist-a")),
                    track("Song", "Alex Smith"),
                    200,
                )
            assertEquals(Outcome.SongArticle, same.outcome)
            assertEquals("artist-a", same.artists.single().mbid)
        }

    @Test
    fun `a leader confirmed on another of the band's recordings keeps their id on the chosen one`() =
        runTest {
            val musicBrainz =
                searching(
                    emptyList(),
                    banded(recording("first", "Song", "Alex Smith and His Orchestra", 200, "1960")),
                    banded(
                        recording("second", "Song", "Alex Smith and His Orchestra", 200, "1961"),
                        "Alex Smith" to "artist-a",
                    ),
                )
            val resolution = resolve(musicBrainz, track("Song", "Alex Smith"), 200)
            assertEquals("first", resolution.recordingMbid)
            assertEquals(listOf(ResolvedArtist("Alex Smith", "artist-a")), resolution.artists)
        }

    @Test
    fun `two bands whose leaders are different artists of one name collide, the same leader's do not`() =
        runTest {
            val different =
                searching(
                    emptyList(),
                    banded(
                        recording("orchestra", "Song", "Alex Smith and His Orchestra", 200, "1960"),
                        "Alex Smith" to "artist-a",
                    ),
                    banded(
                        recording("trio", "Song", "Alex Smith with the Trio", 200, "1970"),
                        "Alex Smith" to "artist-b",
                    ),
                )
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists named Alex Smith have a matching recording"),
                resolve(different, track("Song", "Alex Smith"), 200).outcome,
            )
            val same =
                searching(
                    emptyList(),
                    banded(
                        recording("orchestra", "Song", "Alex Smith and His Orchestra", 200, "1960"),
                        "Alex Smith" to "artist-a",
                    ),
                    banded(
                        recording("trio", "Song", "Alex Smith with the Trio", 200, "1970"),
                        "Alex Smith" to "artist-a",
                    ),
                )
            val resolution = resolve(same, track("Song", "Alex Smith"), 200)
            assertEquals("orchestra", resolution.recordingMbid)
            assertEquals(listOf(ResolvedArtist("Alex Smith", "artist-a")), resolution.artists)
        }

    @Test
    fun `a band led by someone else, or by a name that only starts the same, is not the artist's`() =
        runTest {
            for (band in listOf("Y and His Del-Tones", "Dick Daley and His Del-Tones")) {
                val search = listOf(recording("other", "Misirlou", band, 135, "1962-04"))
                val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Misirlou", "Dick Dale"), 135)
                assertEquals(
                    Outcome.Rejected("MusicBrainz has “Misirlou” by $band, not by Dick Dale"),
                    resolution.outcome,
                    band,
                )
                assertNull(resolution.recordingMbid)
            }
        }

    @Test
    fun `a same-title recording by another artist is rejected, never accepted`() =
        runTest {
            val search = listOf(recording("other", "Wonderland", "Reema", 208, "2024"))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Wonderland", "Reem"), 208)
            assertTrue(resolution.outcome is Outcome.Rejected, "${resolution.outcome}")
            assertNull(resolution.recordingMbid)
            assertEquals(emptyList(), resolution.artists)
        }

    @Test
    fun `nothing found is none, and a failed search unreachable`() =
        runTest {
            assertEquals(Resolution.NONE, resolve(FakeMusicBrainz(Lookup.Found(emptyList())), track("Song", "Artist")))
            assertEquals(Resolution.NONE, resolve(FakeMusicBrainz(Lookup.NotFound), track("Song", "Artist")))
            assertEquals(
                Resolution.UNREACHABLE,
                resolve(FakeMusicBrainz(Lookup.Failed("Offline")), track("Song", "Artist")),
            )
        }

    @Test
    fun `each credited artist is resolved separately, from the credit or a performer relationship, never guessed`() =
        runTest {
            val search = listOf(recording("r", "Song", "Band", 200, "1964"))
            val detail =
                MbRecordingDetail(
                    mbid = "r",
                    title = "Song",
                    credits = listOf(credit("Band")),
                    firstReleaseDate = "1964",
                    works = emptyList(),
                    artistRelations = listOf(MbArtistRelation("vocal", "mbid-singer", "Singer", listOf("lead vocals"))),
                    wikidataIds = emptyList(),
                )
            val musicBrainz = FakeMusicBrainz(Lookup.Found(search), recordings = mapOf("r" to detail))
            val resolution = resolve(musicBrainz, track("Song", "Band, Singer, Stranger"))
            assertEquals(
                listOf(
                    ResolvedArtist("Band", "mbid-Band"),
                    ResolvedArtist("Singer", "mbid-singer"),
                    ResolvedArtist("Stranger", null),
                ),
                resolution.artists,
            )
        }

    @Test
    fun `with the player's length, a fitting measured recording beats an earlier one with no length`() =
        runTest {
            val search =
                listOf(
                    recording("studio", "Song", "Artist", 200, "1975"),
                    recording("bootleg", "Song", "Artist", null, "1974"),
                )
            assertEquals(
                "studio",
                resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Artist"), 200).recordingMbid,
            )
            assertEquals(
                "bootleg",
                resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Artist"), null).recordingMbid,
            )
        }

    private fun reems(
        lengthA: Int?,
        lengthB: Int?,
    ) = FakeMusicBrainz(
        Lookup.Found(
            listOf(
                recording("a", "Wonderland", "Reem", lengthA, "2020").copy(credits = listOf(credit("Reem", "reem-1"))),
                recording("b", "Wonderland", "Reem", lengthB, "2024").copy(credits = listOf(credit("Reem", "reem-2"))),
            ),
        ),
    )

    @Test
    fun `a name two MusicBrainz artists share is rejected as ambiguous, with or without a length`() =
        runTest {
            val rejected = Outcome.Rejected("several MusicBrainz artists named Reem have a matching recording")
            for ((musicBrainz, seconds) in listOf(
                reems(200, 210) to null,
                reems(null, null) to 200,
                reems(200, 200) to 200,
            )) {
                val resolution = resolve(musicBrainz, track("Wonderland", "Reem"), seconds)
                assertEquals(rejected, resolution.outcome, "player length $seconds")
                assertNull(resolution.recordingMbid)
            }
        }
}
