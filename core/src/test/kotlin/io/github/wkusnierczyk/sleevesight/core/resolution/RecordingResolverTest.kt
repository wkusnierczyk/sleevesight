package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecordingResolverTest {
    private val names = NameMatcher(JvmAccentFolder)
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun track(
        title: String,
        artists: String,
    ): NormalizedTrack = normalizer.normalize(title, artists)

    private class FakeMusicBrainz(
        val search: Lookup<List<MbRecording>>,
        val recordings: Map<String, MbRecordingDetail> = emptyMap(),
        val works: Map<String, MbWork> = emptyMap(),
    ) : MusicBrainzLookup {
        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ) = search

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> =
            recordings[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound

        override suspend fun work(mbid: String): Lookup<MbWork> =
            works[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
    }

    private fun credit(
        name: String,
        mbid: String = "mbid-$name",
    ) = MbCredit(name, mbid, name)

    private fun recording(
        mbid: String,
        title: String,
        artist: String,
        lengthSeconds: Int?,
        date: String?,
    ) = MbRecording(
        mbid = mbid,
        title = title,
        lengthMs = lengthSeconds?.let { it * 1000L },
        credits = listOf(credit(artist)),
        firstReleaseDate = date,
        releases =
            listOfNotNull(
                date?.let {
                    MbRelease("release-$mbid", "Album $mbid", it, MbReleaseGroup("group-$mbid", "Album $mbid", "Album"))
                },
            ),
        score = 100,
    )

    private suspend fun resolve(
        musicBrainz: MusicBrainzLookup,
        track: NormalizedTrack,
        seconds: Int? = 200,
    ): Resolution = RecordingResolver(musicBrainz, names).resolve(track, seconds?.seconds)

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
                    listOf(MbPerformance("later", "Song", listOf(credit("Later Act")), "1990", emptyList())),
                )
            val resolution =
                resolve(
                    FakeMusicBrainz(Lookup.Found(search), mapOf("ours" to detail), mapOf("w" to work)),
                    track("Song", "Writer"),
                )
            assertFalse(resolution.cover)
            assertNull(resolution.original)
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

    @Test
    fun `without a length, a name two MusicBrainz artists share is rejected as ambiguous`() =
        runTest {
            val search =
                listOf(
                    recording("a", "Wonderland", "Reem", 200, "2020").copy(credits = listOf(credit("Reem", "reem-1"))),
                    recording("b", "Wonderland", "Reem", 210, "2024").copy(credits = listOf(credit("Reem", "reem-2"))),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Wonderland", "Reem"), null)
            assertEquals(
                Outcome.Rejected("several MusicBrainz artists are named Reem, and no length tells them apart"),
                resolution.outcome,
            )
            assertNull(resolution.recordingMbid)
        }

    @Test
    fun `dates compare at the precision both have`() {
        assertTrue(RecordingResolver.isEarlier("1975", "1978-09-18"))
        assertFalse(RecordingResolver.isEarlier("1978", "1978-09-18"))
        assertFalse(RecordingResolver.isEarlier("1978-09-18", "1978"))
    }
}
