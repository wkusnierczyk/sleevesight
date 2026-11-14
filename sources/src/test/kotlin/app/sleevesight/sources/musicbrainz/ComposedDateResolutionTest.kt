// Playlist positions are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.sources.ComposedDate
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * When the music was composed (#1132), from the composer relationship of the work the resolution looks
 * up anyway, against recorded MusicBrainz responses: no request of its own.
 */
class ComposedDateResolutionTest {
    @Test
    fun `K 545 - Buniatishvili's 2024 recording of music composed in 1788, kept with MusicBrainz's result`() =
        runTest {
            val net = ReplayNetClient("musicbrainz-1129")
            val track =
                RecordedTracks.normalize(
                    "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
                    "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
                )
            val resolution = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names).resolve(track, 457.seconds)
            val work = "https://musicbrainz.org/work/9727cab0-f302-3b71-b937-5ccd416dd36e"
            assertEquals(ComposedDate("1788", "1788", SourceId.MUSICBRAINZ, work), resolution.composed)
            assertEquals("2024-10-25", resolution.firstReleaseDate)
            // The movement's sonata (#1133), whose Wikidata item the identity check asks for: the
            // movement has none.
            assertEquals("a2ad223d-d73b-3775-9580-fd25b4d986e1", resolution.work?.whole?.mbid)
            // The adapter keeps it with MusicBrainz's result, for a replay.
            val result = MusicBrainzSource().lookup(track, CompletableDeferred(resolution))
            assertEquals(resolution.composed, result.composed)
        }

    @Test
    fun `Nessun dorma - Turandot's composition from 1921 to 1924`() =
        runTest {
            val track =
                RecordedTracks.normalize(
                    "Turandot: \"Nessun dorma!\"",
                    "Giacomo Puccini, Luciano Pavarotti, Zubin Mehta, Los Angeles Philharmonic",
                )
            val client = MusicBrainzClient(ReplayNetClient("musicbrainz-1180"))
            val resolver = RecordingResolver(client, RecordedTracks.names)
            val composed = resolver.resolve(track, 204.seconds).composed
            assertEquals("1921" to "1924", composed?.let { it.from to it.to })
        }

    @Test
    fun `Roll Over Beethoven - a song composed in the year of its single, which Dates leaves unsaid`() =
        runTest {
            val resolver = RecordingResolver(MusicBrainzClient(ReplayNetClient("musicbrainz")), RecordedTracks.names)
            val resolution = resolver.resolve(RecordedTracks.track(55), RecordedTracks.duration(55))
            assertEquals("1956" to "1956", resolution.composed?.let { it.from to it.to })
            assertEquals("1956-05", resolution.firstReleaseDate)
        }
}
