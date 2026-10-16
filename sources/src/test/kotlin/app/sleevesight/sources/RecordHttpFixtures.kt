package app.sleevesight.sources

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetConfig
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.musicbrainz.MusicBrainzClient
import app.sleevesight.sources.testing.RecordingNetClient
import app.sleevesight.sources.testing.ReplayNetClient
import app.sleevesight.sources.wikidata.WikidataClient
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.WikipediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Records the responses the adapter tests replay (`fixtures/http/README.md`). Runs only when
 * `SLEEVESIGHT_RECORD_HTTP` names the sources to record (`musicbrainz`, `wikidata`, comma-separated),
 * so neither CI nor an ordinary build calls a live service. It makes the same requests as the tests,
 * against the real services, through the one real [NetClient] and its rate limits; sources not
 * named are replayed from their recordings, so recording one source leaves the others' unchanged.
 */
class RecordHttpFixtures {
    @Test
    fun `record the responses of the fixture tracks`() {
        val record = sourcesToRecord()
        if (record.isEmpty()) return
        runBlocking {
            NetClient(NetConfig(appVersion = APP_VERSION)).use { live ->
                val net = Routing(RecordingNetClient(live), record)
                val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)
                val resolutions =
                    RecordedTracks.positions.associateWith {
                        resolver.resolve(RecordedTracks.track(it), RecordedTracks.duration(it))
                    }
                assertEquals(emptyList(), resolutions.filterValues { it.outcome == Outcome.Unreachable }.keys.toList())
                if ("wikidata" in record) {
                    val check = IdentityCheck(WikidataClient(net), RecordedTracks.names)
                    for (case in IdentityCases.songs) {
                        val verdict =
                            check.song(
                                case.item,
                                RecordedTracks.track(case.track),
                                resolutions.getValue(case.track),
                                case.route,
                            )
                        assertEquals(false, verdict is IdentityCheck.Verdict.Unreachable, "track ${case.track}")
                    }
                    check.artist("Q4091249", ResolvedArtist("Reem", null))
                    val lightfoot = resolutions.getValue(1).artists.single()
                    lightfoot.mbid?.let { check.artistItem(it) }
                    check.artist("Q359552", lightfoot)
                }
                if ("wikipedia" in record) {
                    val check = IdentityCheck(WikidataClient(net), RecordedTracks.names)
                    val adapter = WikipediaSource(WikipediaClient(net), check, RecordedTracks.names)
                    val failed =
                        RecordedTracks.positions.filter { n ->
                            adapter
                                .lookup(
                                    RecordedTracks.track(n),
                                    CompletableDeferred(resolutions.getValue(n)),
                                ).outcome ==
                                Outcome.Unreachable
                        }
                    assertEquals(emptyList(), failed, "tracks Wikipedia or Wikidata did not answer for")
                }
            }
        }
    }

    private fun sourcesToRecord(): Set<String> =
        System
            .getenv(SWITCH)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    // Live through the recorder for the sources named, replayed for the others.
    private class Routing(
        private val recorder: NetClient,
        private val record: Set<String>,
    ) : NetClient {
        private val replay = ReplayNetClient(*SOURCES.values.filter { it !in record }.toTypedArray())

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            val source = SOURCES.entries.first { request.url.startsWith(it.key) }.value
            return if (source in record) recorder.send(request) else replay.send(request)
        }

        override fun close() = Unit
    }

    private companion object {
        const val SWITCH = "SLEEVESIGHT_RECORD_HTTP"
        const val APP_VERSION = "0.0"
        val SOURCES =
            mapOf(
                "https://musicbrainz.org/" to "musicbrainz",
                "https://www.wikidata.org/" to "wikidata",
                "https://en.wikipedia.org/" to "wikipedia",
            )
    }
}
