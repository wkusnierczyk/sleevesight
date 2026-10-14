// Playlist positions and Wikidata ids are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package io.github.wkusnierczyk.sleevesight.sources.wikidata

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck
import io.github.wkusnierczyk.sleevesight.core.resolution.IdentityCheck.Verdict
import io.github.wkusnierczyk.sleevesight.core.resolution.Lookup
import io.github.wkusnierczyk.sleevesight.core.resolution.RecordingResolver
import io.github.wkusnierczyk.sleevesight.core.resolution.WikidataEvidence
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import io.github.wkusnierczyk.sleevesight.sources.IdentityCases
import io.github.wkusnierczyk.sleevesight.sources.RecordedTracks
import io.github.wkusnierczyk.sleevesight.sources.musicbrainz.MusicBrainzClient
import io.github.wkusnierczyk.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The identity check (step 5) against recorded MusicBrainz and Wikidata responses. */
class IdentityCheckTest {
    private val net = ReplayNetClient("musicbrainz", "wikidata")
    private val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)
    private val check = IdentityCheck(WikidataClient(net), RecordedTracks.names)

    private suspend fun resolution(n: Int): Resolution =
        resolver.resolve(RecordedTracks.track(n), RecordedTracks.duration(n))

    private suspend fun verdict(case: IdentityCases.Case): Verdict =
        check.song(case.item, RecordedTracks.track(case.track), resolution(case.track), case.route)

    private fun case(track: Int) = IdentityCases.songs.single { it.track == track }

    @Test
    fun `every case gets the expected scope or a rejection`() =
        runTest {
            for (case in IdentityCases.songs) {
                val verdict = verdict(case)
                val scope = (verdict as? Verdict.Accepted)?.scope
                assertEquals(case.expected, scope, "track ${case.track}, ${case.item}: $verdict")
                if (case.expected == null) assertIs<Verdict.Rejected>(verdict)
            }
        }

    @Test
    fun `same-name artist - the wrong Reem is rejected, as a song and as an artist`() =
        runTest {
            val n = RegressionCase.SAME_NAME_ARTIST.tracks.first()
            val song = verdict(case(n))
            assertEquals(Verdict.Rejected("Q4091249", "“Reem” is a disambiguation page"), song)
            assertEquals(
                Verdict.Rejected("Q4091249", "“Reem” is a disambiguation page"),
                check.artist("Q4091249", ResolvedArtist("Reem", null)),
            )
        }

    @Test
    fun `covers - by name alone, a same-title song by others is rejected, not taken as the original`() =
        runTest {
            assertEquals(
                Verdict.Rejected(
                    "Q210401",
                    "“Here Comes the Sun” is by The Beatles, Steve Harley & Cockney Rebel and Nina Simone, " +
                        "not by Alexander S.",
                ),
                verdict(case(48)),
            )
            assertIs<Verdict.Rejected>(verdict(case(45)))
            assertTrue(45 in RegressionCase.COVERS.tracks && 48 in RegressionCase.COVERS.tracks)
        }

    @Test
    fun `covers - a soundtrack album is not a song, so track 76 is rejected`() =
        runTest {
            val verdict = verdict(case(76))
            assertEquals(
                Verdict.Rejected(
                    "Q18552823",
                    "“Interstellar: Original Motion Picture Soundtrack” is not a song, single or musical work",
                ),
                verdict,
            )
        }

    @Test
    fun `a same-title song by someone else that is not the title's primary topic is rejected`() =
        runTest {
            assertEquals(
                Verdict.Rejected("Q104092599", "“Willow” is by Taylor Swift, not by Jessiah"),
                verdict(case(61)),
            )
        }

    @Test
    fun `with a resolved artist the performer is matched by MusicBrainz id`() =
        runTest {
            val verdict = verdict(case(1))
            assertIs<Verdict.Accepted>(verdict)
            assertEquals("Sundown (Gordon Lightfoot song)", verdict.item.enwiki)
            assertTrue(net.sent.any { it.query["action"] == "wbgetclaims" && it.query["entity"] == "Q359552" })
        }

    @Test
    fun `MusicBrainz's link and the artist's id lead to the items`() =
        runTest {
            val sundown = resolution(1)
            assertEquals(Lookup.Found(listOf("Q7639816")), check.musicBrainzItems(sundown))
            assertEquals(Lookup.Found("Q359552"), check.artistItem(sundown.artists.single().mbid!!))
            val artist = check.artist("Q359552", sundown.artists.single())
            assertIs<Verdict.Accepted>(artist)
            assertEquals(Scope.ARTIST, artist.scope)
        }

    @Test
    fun `the Wikidata lines are templated in the item's scope`() =
        runTest {
            val verdict = verdict(case(1))
            assertIs<Verdict.Accepted>(verdict)
            val lines = WikidataEvidence.lines(verdict, Instant.parse("2026-10-14T00:00:00Z"))
            assertTrue(lines.isNotEmpty())
            assertTrue(
                lines.all {
                    it.source == SourceId.WIKIDATA &&
                        it.scope == Scope.SONG &&
                        it.url == "https://www.wikidata.org/wiki/Q7639816"
                },
            )
            assertTrue(
                lines.any { it.text == "“Sundown” was first published on 25 March 1974." },
                lines.joinToString { it.text },
            )
        }
}
