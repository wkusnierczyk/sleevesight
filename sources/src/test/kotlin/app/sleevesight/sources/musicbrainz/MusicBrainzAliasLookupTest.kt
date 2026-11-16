package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * An orchestra the player names in English, on a track whose composer the player credits first with no
 * lead in the title (#1330), replayed from `fixtures/http/musicbrainz-1330/`, with the orchestra's
 * aliases from `musicbrainz-1289/`.
 */
class MusicBrainzAliasLookupTest {
    private val net = ReplayNetClient("musicbrainz-1330", "musicbrainz-1289")
    private val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)

    // The requests made, as the search's query or the path looked up.
    private val asked: List<String>
        get() = net.sent.map { it.query["query"] ?: it.url.removePrefix(MusicBrainzClient.BASE) }

    @Test
    fun `an orchestra credited under its English name is found by its alias - Beethoven's Ninth by Karajan`() =
        runTest {
            // Spotify credits Beethoven, the Berlin Philharmonic and Karajan, with no composer leading the title;
            // MusicBrainz credits the recording to the Berliner Philharmoniker and Karajan, and titles it without
            // "Choral". The searches by name find no recording within the length's tolerance; the loose search
            // finds it. Before, an alias was looked up only with a composer leading the title, so the
            // orchestra's credit stood for none of the track's artists, the loose search's recording was not
            // taken, and nothing was found.
            val track =
                RecordedTracks.normalize(
                    "Symphony No. 9 in D Minor, Op. 125 \"Choral\": I. Allegro ma non troppo, un poco maestoso",
                    "Ludwig van Beethoven, Berlin Philharmonic, Herbert von Karajan",
                )
            val resolution = resolver.resolve(track, 936_000.milliseconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("eadd2605-6f2f-44d5-acaf-e4ef9aa40bed", resolution.recordingMbid)
            assertEquals(false, resolution.provisional)
            assertEquals(
                ResolvedArtist("Berlin Philharmonic", "dea28aa9-1086-4ffa-8739-0ccc759de1ce"),
                resolution.artists[1],
            )
            // MusicBrainz's credit as it writes it, not the alias.
            assertEquals("Berliner Philharmoniker, Herbert von Karajan", resolution.artistCredit)
            // The movement's work names Beethoven its composer: the Composer row, the others performing it.
            assertEquals("3308d248-b287-38e9-9718-0119d7a3bf9d", resolution.work?.mbid)
            assertEquals(
                ArtistRoles(
                    listOf("Ludwig van Beethoven"),
                    listOf("Berlin Philharmonic", "Herbert von Karajan"),
                    classical = true,
                ),
                resolution.roles,
            )
            val title = "Symphony No. 9 in D Minor, Op. 125 \\\"Choral\\\": I. Allegro ma non troppo, un poco maestoso"
            assertEquals(
                listOf(
                    "recording:\"$title\" AND artist:\"Ludwig van Beethoven\"",
                    "recording:\"$title\" AND artist:\"Berlin Philharmonic\"",
                    "recording:\"$title\" AND artist:\"Herbert von Karajan\"",
                    "recording:(symphony~ no 9 in d minor~ op 125) AND artist:(\"Ludwig van Beethoven\" OR " +
                        "\"Berlin Philharmonic\" OR \"Herbert von Karajan\")",
                    "/artist/dea28aa9-1086-4ffa-8739-0ccc759de1ce",
                    "/recording/eadd2605-6f2f-44d5-acaf-e4ef9aa40bed",
                    "/work/3308d248-b287-38e9-9718-0119d7a3bf9d",
                ),
                asked,
            )
        }
}
