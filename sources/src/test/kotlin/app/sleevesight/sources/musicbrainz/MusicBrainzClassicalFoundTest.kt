package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.pipeline.ArtistSplit
import app.sleevesight.core.pipeline.AuthorsName
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Classical recordings MusicBrainz holds that were not found (#1289), and the work of one that links none
 * (#1299), as Spotify names them, replayed from `fixtures/http/musicbrainz-1289/`.
 */
class MusicBrainzClassicalFoundTest {
    private val net = ReplayNetClient("musicbrainz-1289")
    private val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)

    // The requests made, as the search's query or the path looked up.
    private val asked: List<String>
        get() = net.sent.map { it.query["query"] ?: it.url.removePrefix(MusicBrainzClient.BASE) }

    @Test
    fun `an orchestra credited under its English name is found by its alias - Barber's Adagio for Strings`() =
        runTest {
            // MusicBrainz credits Rattle and the Berliner Philharmoniker; Spotify the Berlin Philharmonic,
            // one of the orchestra's aliases. Before, the orchestra's credit stood for none of the track's
            // artists, so the recording was not taken as theirs and nothing was found.
            val track =
                RecordedTracks.normalize(
                    "Barber: Adagio for Strings",
                    "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic",
                )
            val resolution = resolver.resolve(track, 537_426.milliseconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("53c2e131-de75-4ab4-a80e-164b3adfc28f", resolution.recordingMbid)
            assertEquals(
                ResolvedArtist("Berlin Philharmonic", "dea28aa9-1086-4ffa-8739-0ccc759de1ce"),
                resolution.artists.last(),
            )
            // MusicBrainz's credit as it writes it, not the alias.
            assertEquals("Sir Simon Rattle & Berliner Philharmoniker", resolution.artistCredit)
            assertEquals(
                listOf(
                    "recording:\"Adagio for Strings\" AND artist:\"Sir Simon Rattle\"",
                    "recording:\"Barber: Adagio for Strings\" AND artist:\"Samuel Barber\"",
                    "/artist/dea28aa9-1086-4ffa-8739-0ccc759de1ce",
                    "/recording/53c2e131-de75-4ab4-a80e-164b3adfc28f",
                    "work:\"Adagio for Strings\" AND artist:\"Samuel Barber\"",
                    "/work/1ebe1abf-daf2-3e5e-b3ad-b6c5a4ec5727",
                ),
                asked,
            )
        }

    @Test
    fun `a recording that links no work finds its composer's work by a search - Barber's Adagio for Strings`() =
        runTest {
            // MusicBrainz links `53c2e131` to no work (#1299). The search of Barber's works by the title finds
            // five titled `Adagio for Strings`, each naming him its composer: the piece (`1ebe1abf`, which he
            // orchestrated himself), a catch-all for its arrangements (its disambiguation says so), and three
            // arrangements (for organ, woodwind band and clarinet choir, each with its arranger). The piece,
            // the one work left, is taken.
            val track =
                RecordedTracks.normalize(
                    "Barber: Adagio for Strings",
                    "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic",
                )
            val resolution = resolver.resolve(track, 537_426.milliseconds)
            assertEquals("53c2e131-de75-4ab4-a80e-164b3adfc28f", resolution.recordingMbid)
            assertEquals(false, resolution.provisional)
            val work = assertNotNull(resolution.work)
            assertEquals("1ebe1abf-daf2-3e5e-b3ad-b6c5a4ec5727", work.mbid)
            assertEquals("Adagio for Strings", work.title)
            assertEquals(false, resolution.cover)
            assertNull(resolution.original)
            // Barber composed it, in 1936 by the work's composer relationship; the others perform it. The
            // work has no type, so the roles do not call it classical; the title's lead does (`ArtistSplit`).
            val roles = assertNotNull(resolution.roles)
            assertEquals(ArtistRoles(listOf("Samuel Barber"), listOf("Sir Simon Rattle", "Berlin Philharmonic")), roles)
            val split = assertNotNull(ArtistSplit.of(track, roles, resolution.lyrics))
            assertEquals(AuthorsName.COMPOSER, split.name)
            assertEquals("Sir Simon Rattle", split.lead)
            assertEquals("1936", resolution.composed?.from)
            // The piece's Wikidata item, for its Wikipedia lines.
            assertTrue("Q346715" in resolution.wikidataIds, "${resolution.wikidataIds}")
        }

    @Test
    fun `a misspelled aria credited after its composer and librettists is found - Nessun doma - Turandot`() =
        runTest {
            // Spotify credits Puccini and two librettists before Pavarotti, and misspells the title. The
            // searches by the first three credited artists find nothing; the loose search by the title's
            // words and every credited artist finds the recording, credited to three of them.
            val track =
                RecordedTracks.normalize(
                    "Nessun doma - Turandot",
                    "Giacomo Puccini, Giuseppe Adami, Carlo Simoni, Luciano Pavarotti, Leone Magiera, " +
                        "Orchestra Da Camera Di Bologna",
                )
            val resolution = resolver.resolve(track, 171_666.milliseconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("4cf239b6-3d54-4338-9a24-fa1f6fc27068", resolution.recordingMbid)
            assertEquals("876aa6e9-0a0a-3408-bbfa-0565582cf8e0", resolution.work?.mbid)
            assertEquals(
                listOf(
                    "recording:\"Nessun doma - Turandot\" AND artist:\"Giacomo Puccini\"",
                    "recording:\"Nessun doma - Turandot\" AND artist:\"Giuseppe Adami\"",
                    "recording:\"Nessun doma - Turandot\" AND artist:\"Carlo Simoni\"",
                    "recording:(nessun~ doma~ turandot~) AND artist:(\"Giacomo Puccini\" OR \"Giuseppe Adami\" OR " +
                        "\"Carlo Simoni\" OR \"Luciano Pavarotti\" OR \"Leone Magiera\" OR " +
                        "\"Orchestra Da Camera Di Bologna\")",
                    "/recording/4cf239b6-3d54-4338-9a24-fa1f6fc27068",
                    "/work/876aa6e9-0a0a-3408-bbfa-0565582cf8e0",
                ),
                asked,
            )
            // Puccini composed it; Adami, a librettist the work names, is no performer; Pavarotti, on the
            // recording, leads. MusicBrainz's other librettist is Renato Simoni, not Spotify's "Carlo
            // Simoni", who stays among the performers, after those the recording has.
            assertEquals(
                ArtistRoles(
                    listOf("Giacomo Puccini"),
                    listOf("Luciano Pavarotti", "Leone Magiera", "Orchestra Da Camera Di Bologna", "Carlo Simoni"),
                    classical = true,
                ),
                resolution.roles,
            )
            val split = assertNotNull(ArtistSplit.of(track, assertNotNull(resolution.roles), resolution.lyrics))
            assertEquals(AuthorsName.AUTHORS, split.name)
            assertEquals(listOf("Giuseppe Adami", "Renato Simoni"), split.words)
            assertEquals("Luciano Pavarotti", split.lead)
            assertEquals("1921", resolution.composed?.from)
        }

    @Test
    fun `a recording that links no work is lent the aria the artist's own links - Nessun Dorma by Pavarotti`() =
        runTest {
            // The album is not on MusicBrainz. Of Pavarotti's recordings titled `Nessun Dorma` that fit, the
            // earliest (`d303669a`, 1994) links no work; his `Turandot: “Nessun dorma”` of 1993, which fits
            // too, links the aria, which is lent to it: the recording and its dates stay `d303669a`'s.
            val track = RecordedTracks.normalize("Nessun Dorma", "Luciano Pavarotti")
            val resolution = resolver.resolve(track, 196_360.milliseconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("d303669a-786f-423e-a3ed-f638e7f58018", resolution.recordingMbid)
            assertEquals("1994", resolution.recordingFirstReleased)
            assertEquals(false, resolution.provisional)
            assertEquals("876aa6e9-0a0a-3408-bbfa-0565582cf8e0", resolution.work?.mbid)
            assertEquals(
                listOf(
                    "recording:\"Nessun Dorma\" AND artist:\"Luciano Pavarotti\"",
                    "recording:\"Nessun Dorma\" AND artist:\"Luciano Pavarotti\" AND firstreleasedate:[* TO 1994]",
                    "/recording/d303669a-786f-423e-a3ed-f638e7f58018",
                    "/recording/2dede7cc-05a6-4ed5-9274-bc7a18c0e3ad",
                    "/work/876aa6e9-0a0a-3408-bbfa-0565582cf8e0",
                ),
                asked,
            )
            // The composer is not credited on the track, so none is told apart; the work still dates the
            // music and names who wrote its words.
            assertEquals(ArtistRoles(emptyList(), listOf("Luciano Pavarotti"), classical = true), resolution.roles)
            assertEquals("1921", resolution.composed?.from)
            assertEquals(listOf("Giuseppe Adami", "Renato Simoni"), resolution.lyrics.librettists)
        }
}
