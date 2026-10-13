package io.github.wkusnierczyk.sleevesight.sources

import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.resolution.RecordingResolver
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetConfig
import io.github.wkusnierczyk.sleevesight.sources.musicbrainz.MusicBrainzClient
import io.github.wkusnierczyk.sleevesight.sources.testing.RecordingNetClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Records the responses the adapter tests replay (`fixtures/http/README.md`). Runs only when
 * `SLEEVESIGHT_RECORD_HTTP=1`, so neither CI nor an ordinary build calls a live service; it makes
 * the same requests as the tests, against the real services, through the one real [NetClient] and
 * its rate limits.
 */
class RecordHttpFixtures {
    @Test
    fun `record the responses of the fixture tracks`() {
        if (System.getenv(SWITCH) != "1") return
        runBlocking {
            RecordingNetClient(NetClient(NetConfig(appVersion = APP_VERSION))).use { net ->
                val resolver = RecordingResolver(MusicBrainzClient(net), RecordedTracks.names)
                val unreachable =
                    RecordedTracks.positions.filter { n ->
                        resolver.resolve(RecordedTracks.track(n), RecordedTracks.duration(n)).outcome ==
                            Outcome.Unreachable
                    }
                assertEquals(emptyList(), unreachable, "tracks MusicBrainz did not answer for")
            }
        }
    }

    private companion object {
        const val SWITCH = "SLEEVESIGHT_RECORD_HTTP"
        const val APP_VERSION = "0.0"
    }
}
