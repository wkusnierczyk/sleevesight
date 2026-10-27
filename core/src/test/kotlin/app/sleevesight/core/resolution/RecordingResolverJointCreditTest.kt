package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A joint credit (#661): the player names as one artist ("Ann Lee & The Rivets") what MusicBrainz
 * credits as several artists joined by their join phrases.
 */
class RecordingResolverJointCreditTest {
    private fun joint(
        mbid: String,
        date: String?,
        vararg credits: MbCredit,
        title: String = "Song",
        lengthSeconds: Int? = 200,
    ) = recording(mbid, title, "unused", lengthSeconds, date).copy(credits = credits.toList())

    private val ann = MbCredit("Ann Lee", "ann", "Ann Lee", " & ")
    private val rivets = credit("The Rivets", "rivets")

    @Test
    fun `a joint credit the player names as one artist is accepted when nothing fits by name`() =
        runTest {
            val search = listOf(joint("joint", "2025-08-22", ann, rivets))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Ann Lee & The Rivets"))
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("joint", resolution.recordingMbid)
            assertEquals("Ann Lee & The Rivets", resolution.artistCredit)
            assertEquals("2025-08-22", resolution.firstReleaseDate)
            // No one MusicBrainz artist is the pair, so it gets no id.
            assertEquals(listOf(ResolvedArtist("Ann Lee & The Rivets", null)), resolution.artists)
            // Its artists give the genre labels.
            assertEquals(
                listOf(ResolvedArtist("Ann Lee", "ann"), ResolvedArtist("The Rivets", "rivets")),
                resolution.jointArtists,
            )
        }

    @Test
    fun `a joint credit may mix a credited name with MusicBrainz's name for another artist`() =
        runTest {
            // Neither all as credited ("Ann & The Rivets") nor all as MusicBrainz names them ("Ann Lee &
            // Rivets Band") is the name; "Ann Lee" as MusicBrainz names her and "The Rivets" as credited is.
            val mixed =
                listOf(joint("mixed", "2025", ann.copy(name = "Ann"), MbCredit("The Rivets", "rivets", "Rivets Band")))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(mixed)), track("Song", "Ann Lee & The Rivets"))
            assertEquals("mixed", resolution.recordingMbid)
        }

    @Test
    fun `one joint name two different pairs write on one recording collides`() =
        runTest {
            val versus =
                listOf(
                    joint(
                        "versus",
                        "1970",
                        ann,
                        rivets.copy(joinPhrase = " vs. "),
                        ann.copy(artistMbid = "other-ann"),
                        rivets,
                    ),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(versus)), track("Song", "Ann Lee & The Rivets"))
            assertIs<Outcome.Rejected>(resolution.outcome)
            assertNull(resolution.recordingMbid)
        }

    @Test
    fun `an artist accepted under a joint credit gets no id, even from a performer of the same name`() =
        runTest {
            val chosen = joint("chosen", "1975", ann, rivets)
            val detail =
                MbRecordingDetail(
                    mbid = "chosen",
                    title = "Song",
                    credits = chosen.credits,
                    firstReleaseDate = "1975",
                    works = emptyList(),
                    artistRelations =
                        listOf(
                            MbArtistRelation("performer", "pair", "Ann Lee & The Rivets", emptyList()),
                        ),
                    wikidataIds = emptyList(),
                )
            val musicBrainz = FakeMusicBrainz(Lookup.Found(listOf(chosen)), mapOf("chosen" to detail))
            val resolution = resolve(musicBrainz, track("Song", "Ann Lee & The Rivets"))
            assertEquals("chosen", resolution.recordingMbid)
            assertEquals(listOf(ResolvedArtist("Ann Lee & The Rivets", null)), resolution.artists)
        }

    @Test
    fun `an honorific variant that fits wins over a joint credit on another recording`() =
        runTest {
            // The joint credit's recording is the earlier, so it would be chosen if it were weighed.
            val search =
                listOf(
                    joint("joint", "1970", ann, rivets),
                    recording("variant", "Song", "Mr. Ann Lee & The Rivets", 200, "1990"),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Ann Lee & The Rivets"))
            assertEquals("variant", resolution.recordingMbid)
        }

    @Test
    fun `credits that write the name only with another between them are not a joint credit`() =
        runTest {
            val apart = listOf(joint("apart", "1970", ann, credit("X", "x").copy(joinPhrase = " & "), rivets))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(apart)), track("Song", "Ann Lee & The Rivets"))
            assertNull(resolution.recordingMbid)
            assertIs<Outcome.Rejected>(resolution.outcome)
        }

    @Test
    fun `a joint credit within a longer one counts, written with and or the artists' own names`() =
        runTest {
            val featuring =
                listOf(
                    joint(
                        "featuring",
                        "2025",
                        ann.copy(joinPhrase = " and "),
                        rivets.copy(joinPhrase = " feat. "),
                        credit("Bo"),
                    ),
                )
            val one = resolve(FakeMusicBrainz(Lookup.Found(featuring)), track("Song", "Ann Lee & The Rivets"))
            assertEquals("featuring", one.recordingMbid)
            // Credited as "Ann", MusicBrainz's artist "Ann Lee".
            val asCredited = listOf(joint("own-names", "2025", ann.copy(name = "Ann"), rivets))
            val two = resolve(FakeMusicBrainz(Lookup.Found(asCredited)), track("Song", "Ann Lee & The Rivets"))
            assertEquals("own-names", two.recordingMbid)
        }

    @Test
    fun `a joint credit is not weighed beside a fitting credit by name, and does not collide with it`() =
        runTest {
            // The joint credit's recording is the earlier, so it would be chosen if it counted.
            val search =
                listOf(
                    joint("joint", "1970", ann, rivets),
                    recording("named", "Song", "Ann Lee & The Rivets", 200, "1980"),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Ann Lee & The Rivets"))
            assertEquals("named", resolution.recordingMbid)
            assertEquals(
                listOf(ResolvedArtist("Ann Lee & The Rivets", "mbid-Ann Lee & The Rivets")),
                resolution.artists,
            )
        }

    @Test
    fun `one joint name that two different runs of artists share collides`() =
        runTest {
            val search =
                listOf(
                    joint("ours", "1970", ann, rivets),
                    joint("namesake", "1980", ann.copy(artistMbid = "other-ann"), rivets),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Ann Lee & The Rivets"))
            assertIs<Outcome.Rejected>(resolution.outcome)
            assertNull(resolution.recordingMbid)
        }

    @Test
    fun `part of the name, another partner, another join or a misfit length is not a joint credit`() =
        runTest {
            val misses =
                mapOf(
                    "part" to listOf(joint("part", "1970", credit("Ann Lee"))),
                    "partner" to listOf(joint("partner", "1970", ann, credit("The Rockets"))),
                    "join" to listOf(joint("join", "1970", ann.copy(joinPhrase = " with "), rivets)),
                    "length" to listOf(joint("length", "1970", ann, rivets, lengthSeconds = 230)),
                )
            for ((case, search) in misses) {
                val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Song", "Ann Lee & The Rivets"))
                assertNull(resolution.recordingMbid, case)
                // A near miss where the title and length fit, nothing found where they do not.
                assertEquals(case != "length", resolution.outcome is Outcome.Rejected, case)
            }
        }

    @Test
    fun `the joint credit's artists are the track's own when the work's original is sought`() =
        runTest {
            val chosen = joint("chosen", "1975", ann, rivets)
            val detail =
                MbRecordingDetail(
                    mbid = "chosen",
                    title = "Song",
                    credits = chosen.credits,
                    firstReleaseDate = "1975",
                    works = listOf(MbWorkLink("work", "Song", cover = false)),
                    artistRelations = emptyList(),
                    wikidataIds = emptyList(),
                )
            val work =
                MbWork(
                    mbid = "work",
                    title = "Song",
                    artistRelations = emptyList(),
                    wikidataIds = emptyList(),
                    performances =
                        listOf(
                            MbPerformance("chosen", "Song", chosen.credits, "1974", emptyList()),
                            // Their earlier take, credited to Ann Lee alone, is theirs too, not an original by others.
                            MbPerformance("demo", "Song (demo)", listOf(credit("Ann Lee", "ann")), "1972", emptyList()),
                            MbPerformance("later", "Song", listOf(credit("Someone Else")), "1990", emptyList()),
                        ),
                )
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(listOf(chosen)),
                    mapOf("chosen" to detail),
                    mapOf(
                        "work" to work,
                    ),
                )
            val resolution = resolve(musicBrainz, track("Song", "Ann Lee & The Rivets"))
            assertEquals("chosen", resolution.recordingMbid)
            assertFalse(resolution.cover)
            assertNull(resolution.original)
        }
}
