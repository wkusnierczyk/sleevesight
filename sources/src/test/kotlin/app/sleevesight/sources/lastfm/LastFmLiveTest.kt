package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.sources.Resolution
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetConfig
import app.sleevesight.sources.musicbrainz.MusicBrainzClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A smoke test against the live API, skipped unless `SLEEVESIGHT_LASTFM_API_KEY` is set; never in
 * CI. It checks first that the hand-written stubs' shape (`fixtures/http/lastfm/README.md`) is
 * what Last.fm sends.
 */
class LastFmLiveTest {
    @Test
    fun `the live API answers in the stubs' shape, and track 61 is still rejected`() {
        val key = System.getenv(KEY_VARIABLE)
        assumeTrue(!key.isNullOrBlank(), "$KEY_VARIABLE is not set")
        runBlocking {
            NetClient(NetConfig(appVersion = "0.0-live-test")).use { net ->
                val adapter =
                    LastFmAdapter(
                        net,
                        MusicBrainzClient(net),
                        Secret(key),
                        GenreVocabulary.load(JvmResourceReader),
                        JvmAccentFolder,
                    )
                val normalizer = Normalizer(JvmAccentFolder)
                val none = CompletableDeferred(Resolution.NONE)

                val rich = adapter.lookupArtists(normalizer.normalize("Sundown", "Gordon Lightfoot"), none)
                val artist = checkNotNull(rich.artists.single().artist) { "${rich.artists.single().match}" }
                assertTrue(artist.mbid != null && (artist.listeners ?: 0) > 0 && artist.tags.isNotEmpty())
                assertTrue(
                    rich.artists
                        .single()
                        .genres
                        .isNotEmpty(),
                )
                assertEquals(Outcome.ArtistArticle, rich.result.outcome)

                val willow = adapter.lookup(normalizer.normalize("willow", "Jessiah"), none)
                assertIs<Outcome.Rejected>(willow.outcome)
            }
        }
    }

    private companion object {
        const val KEY_VARIABLE = "SLEEVESIGHT_LASTFM_API_KEY"
    }
}
