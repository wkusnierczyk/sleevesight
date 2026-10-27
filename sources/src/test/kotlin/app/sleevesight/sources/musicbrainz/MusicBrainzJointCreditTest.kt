package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.RecordedTracks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A recording MusicBrainz credits to two artists that the player names as one (#661): Spotify plays
 * "Hop Bico (Instrumental)" by "Derya Yıldırım & Grup Şimşek", which MusicBrainz credits "Derya
 * Yıldırım" and "Grup Şimşek", joined by " & ". MusicBrainz's answers, recorded on 2026-10-27, are in
 * this module's test resources, `musicbrainz/hop-bico/`.
 */
class MusicBrainzJointCreditTest {
    private val net = HopBicoReplies()
    private val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)

    @Test
    fun `Hop Bico (Instrumental) resolves to its recording, with the year of the album the player names`() =
        runTest {
            val track = Normalizer(JvmAccentFolder).normalize("Hop Bico (Instrumental)", ARTIST)
            val resolution = resolver.resolve(track, (2 * 60 + 47).seconds)

            assertEquals(
                "recording:\"Hop Bico (Instrumental)\" AND artist:\"Derya Yıldırım & Grup Şimşek\"",
                net.sent.first().query["query"],
            )
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals(RECORDING, resolution.recordingMbid)
            assertEquals("Derya Yıldırım & Grup Şimşek", resolution.artistCredit)
            assertEquals(listOf(ResolvedArtist(ARTIST, null)), resolution.artists)
            // The pair has no id of its own; its two artists give MusicBrainz's genre labels.
            val labels = MusicBrainzSource().lookup(track, CompletableDeferred(resolution)).labelArtists
            assertEquals(
                listOf(
                    ResolvedArtist("Derya Yıldırım", "961f11f3-3c55-4b0e-ab30-53c0dc8d956e"),
                    ResolvedArtist("Grup Şimşek", "d1eb7b63-94ee-47ab-8747-3e1f5e9b45e8"),
                ),
                labels,
            )
            assertEquals("2025-08-22", resolution.firstReleaseDate)
            assertEquals(
                "2025",
                ReleaseDate.albumYear(
                    "Yarın Yoksa (Instrumentals)",
                    resolution.releaseDates,
                    null,
                    RecordedTracks.names::key,
                ),
            )
        }

    /** Answers the search and the recording's lookup from the recorded replies, and nothing else. */
    private class HopBicoReplies : NetClient {
        val sent = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            sent += request
            val file =
                when (request.url) {
                    "${MusicBrainzClient.BASE}/recording" -> "search.json"
                    "${MusicBrainzClient.BASE}/recording/$RECORDING" -> "recording-$RECORDING.json"
                    else -> throw AssertionError("No recorded response for ${request.url}")
                }
            val body = checkNotNull(javaClass.getResource("/musicbrainz/hop-bico/$file")).readText()
            return NetResult.Success(NetResponse(200, emptyMap(), body.encodeToByteArray()))
        }

        override fun close() = Unit
    }

    private companion object {
        const val ARTIST = "Derya Yıldırım & Grup Şimşek"
        const val RECORDING = "856396e4-73d0-4cad-a00d-18b444780631"
    }
}
