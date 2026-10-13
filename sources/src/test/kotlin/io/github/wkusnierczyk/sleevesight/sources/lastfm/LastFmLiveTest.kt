package io.github.wkusnierczyk.sleevesight.sources.lastfm

import io.github.wkusnierczyk.sleevesight.core.Secret
import io.github.wkusnierczyk.sleevesight.core.genres.GenreVocabulary
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.platform.JvmResourceReader
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetConfig
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
                    LastFmAdapter(net, Secret(key), GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
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
