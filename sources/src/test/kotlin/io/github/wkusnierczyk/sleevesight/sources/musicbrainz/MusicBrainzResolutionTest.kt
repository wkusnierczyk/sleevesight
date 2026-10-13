// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.sources.musicbrainz

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.resolution.RecordingResolver
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import io.github.wkusnierczyk.sleevesight.fixtures.BelowSoundtrack
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import io.github.wkusnierczyk.sleevesight.sources.RecordedTracks
import io.github.wkusnierczyk.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Resolution against recorded MusicBrainz responses for the fixture's regression cases. */
class MusicBrainzResolutionTest {
    private val net = ReplayNetClient("musicbrainz")
    private val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)

    private suspend fun resolve(n: Int): Resolution =
        resolver.resolve(RecordedTracks.track(n), RecordedTracks.duration(n))

    @Test
    fun `rich data - Sundown resolves to the 1974 recording, with its work and credits`() =
        runTest {
            val resolution = resolve(RegressionCase.RICH_DATA.tracks.single())
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("c4599250-9001-400a-831a-1f7c31b5f781", resolution.recordingMbid)
            assertEquals("1974-01", resolution.firstReleaseDate)
            assertEquals("Sundown", resolution.firstRelease?.title)
            assertEquals(
                listOf(ResolvedArtist("Gordon Lightfoot", "9d97b077-b28d-4ba8-a3d9-c71926e3b2b6")),
                resolution.artists,
            )
            assertFalse(resolution.cover)
            assertEquals(listOf("Q7639816"), resolution.wikidataIds)
        }

    @Test
    fun `compilation release year - the first release is the song's, not the compilation's`() =
        runTest {
            val (groove, snowbird) = RegressionCase.COMPILATION_RELEASE_YEAR.tracks
            assertEquals("2002", BelowSoundtrack.albumFor(groove).year)
            assertEquals("1978-09-18", resolve(groove).firstReleaseDate)
            assertEquals("2023", BelowSoundtrack.albumFor(snowbird).year)
            assertEquals("1969", resolve(snowbird).firstReleaseDate)
        }

    @Test
    fun `featured artist - Them's 1964 single, a cover of the 1935 original, Van Morrison left unresolved`() =
        runTest {
            val resolution = resolve(RegressionCase.FEATURED_ARTIST.tracks.single())
            assertEquals("e99a93c2-6f63-40e6-b52d-050b85ac53ce", resolution.recordingMbid)
            assertEquals("1964-11-06", resolution.firstReleaseDate)
            assertEquals(
                listOf(
                    ResolvedArtist("Them", "dbd8e1e8-b3d5-4c5f-9343-bbe5e86380a6"),
                    ResolvedArtist("Van Morrison", null),
                ),
                resolution.artists,
            )
            assertTrue(resolution.cover)
            assertEquals("1935-10-31", resolution.original?.date)
            assertEquals("Sonny Boy Williamson with Big Joe Williams", resolution.original?.artistCredit)
        }

    @Test
    fun `a cover MusicBrainz marks is a cover even when its original is undated`() =
        runTest {
            val resolution = resolve(13)
            assertTrue(resolution.cover)
            assertEquals("New York Groove", resolution.work?.title)
            assertNull(resolution.original)
        }

    @Test
    fun `same-name artist - nothing on MusicBrainz for Reem is none, with no artist id guessed`() =
        runTest {
            for (n in RegressionCase.SAME_NAME_ARTIST.tracks) {
                val resolution = resolve(n)
                assertEquals(Outcome.None, resolution.outcome, "track $n")
                assertEquals(emptyList(), resolution.artists)
            }
        }

    @Test
    fun `nothing documented and unknown acts resolve to none`() =
        runTest {
            for (n in RegressionCase.NOTHING_DOCUMENTED.tracks.filter { it != 30 } + listOf(45, 61, 76)) {
                assertEquals(Outcome.None, resolve(n).outcome, "track $n")
            }
        }

    @Test
    fun `version suffix - Fisherman's Blues resolves to a recording with a length, first released in 1988`() =
        runTest {
            val resolution = resolve(RegressionCase.VERSION_SUFFIX.tracks.single())
            assertEquals("51b75c9a-8990-42d6-ad81-228d65805070", resolution.recordingMbid)
            assertEquals("1988-10-17", resolution.firstReleaseDate)
        }

    @Test
    fun `the search sends the normalized title and the primary artist, and never asks for tags or genres`() =
        runTest {
            resolve(RegressionCase.VERSION_SUFFIX.tracks.single())
            val search = net.sent.first()
            assertEquals("recording:\"Fisherman's Blues\" AND artist:\"The Waterboys\"", search.query["query"])
            for (request in net.sent) {
                val inc = request.query["inc"].orEmpty()
                assertFalse("tags" in inc || "genres" in inc, inc)
            }
        }

    @Test
    fun `the adapter gives the resolution's outcome and templated lines`() =
        runTest {
            val resolution = resolve(RegressionCase.FEATURED_ARTIST.tracks.single())
            val at = Instant.parse("2026-10-14T00:00:00Z")
            val clock =
                object : Clock {
                    override fun now() = at
                }
            val result = MusicBrainzSource(clock).lookup(RecordedTracks.track(63), CompletableDeferred(resolution))
            assertEquals(Outcome.SongArticle, result.outcome)
            assertEquals(
                "“Baby, Please Don’t Go” by Them was first released on 6 November 1964, " +
                    "on the single “Baby Please Don't Go / Gloria”.",
                result.evidence.first().text,
            )
            assertEquals(listOf(Scope.ORIGINAL_SONG), result.evidence.filter { "1935" in it.text }.map { it.scope })
        }
}
