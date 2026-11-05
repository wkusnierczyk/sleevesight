package app.sleevesight.wiring

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.KeptPart
import app.sleevesight.core.sources.LanguageSubject
import app.sleevesight.core.sources.WITHOUT_GENRES_VERSION
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.language.LanguageLinesSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class PipelineWiringTest {
    /** Answers every request with [response], and keeps the URLs asked and the times it was closed. */
    private class Answering(
        private val response: NetResponse,
    ) : NetClient {
        val asked = mutableListOf<String>()
        var closed = 0

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            asked += request.url
            return NetResult.Success(response)
        }

        override fun close() {
            closed++
        }
    }

    private val image = NetResponse(200, mapOf("Content-Type" to listOf("image/jpeg")), byteArrayOf(1, 2, 3))
    private val net = Answering(image)
    private val wiring = pipelineWiringOn(net, JvmAccentFolder, JvmResourceReader)
    private val lastFm = Secret("lastfm")
    private val exa = Secret("exa")

    private fun ids(
        keys: SourceKeys,
        settings: Settings = Settings(),
    ) = wiring.adapters(keys, settings).map { it.id }

    @Test
    fun `without keys only the keyless sources get adapters`() {
        assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), ids(SourceKeys()))
    }

    @Test
    fun `LASTFM gets its adapter only with its key`() {
        assertEquals(
            listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM),
            ids(SourceKeys(lastFm = lastFm)),
        )
    }

    @Test
    fun `web search is built from the engines switched on that have a key`() {
        val keyed = SourceKeys(lastFm = lastFm, web = mapOf(WebEngine.EXA to exa))
        assertEquals(
            listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.WEB),
            ids(keyed),
        )
        assertEquals(
            listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM),
            ids(keyed, Settings(webEngines = emptySet())),
        )
    }

    @Test
    fun `the artist's web search is built only with Exa switched on and its key, its lines spared by the clear`() {
        val keyed = SourceKeys(web = mapOf(WebEngine.EXA to exa))
        assertNotNull(wiring.artistWeb(keyed, Settings()))
        assertNull(wiring.artistWeb(keyed, Settings(webEngines = emptySet())))
        assertNull(wiring.artistWeb(SourceKeys(), Settings()))
        assertEquals(setOf("album.web", "artist.web"), PipelineWiring.TRACK_ONLY_PARTS)
    }

    // The artist cache in a map, by part name and key, kept for ever.
    private class Table : ArtistResults {
        val entries = mutableMapOf<Pair<String, String>, String>()

        override suspend fun get(
            part: ArtistPart,
            key: String,
        ): KeptPart? = entries[part.name to key]?.let { KeptPart(it, Instant.DISTANT_FUTURE) }

        override suspend fun keep(
            part: ArtistPart,
            key: String,
            value: String,
        ) {
            entries[part.name to key] = value
        }
    }

    @Test
    fun `the lines in the track's language are one for the wiring, in the artist cache while it is kept`() =
        runTest {
            val table = Table()
            var keeps = false
            val wiring =
                PipelineWiring(net, JvmAccentFolder, JvmResourceReader, artists = table, keepsArtists = { keeps })
            assertSame(wiring.languageLines, wiring.languageLines)
            val bts = LanguageSubject.Artist("BTS", "mb-bts")
            val line =
                EvidenceDraft(
                    SourceId.WIKIPEDIA,
                    Scope.ARTIST,
                    "방탄소년단은 그룹이다.",
                    null,
                    Instant.DISTANT_PAST,
                    language = "ko",
                )

            // Off: in memory, which Clear history forgets.
            wiring.languageLines.keep(bts, "ko", listOf(line))
            assertEquals(listOf(line), wiring.languageLines.kept(bts))
            assertTrue(table.entries.isEmpty())
            wiring.forgetRemembered()
            assertTrue(wiring.languageLines.kept(bts).isEmpty())

            // On: in the artist cache, which the switch's clear does not spare.
            keeps = true
            wiring.languageLines.keep(bts, "ko", listOf(line))
            assertEquals(setOf("artist.language" to "mb:mb-bts"), table.entries.keys)
            assertFalse(LanguageLinesSource.ARTIST_PART.name in PipelineWiring.TRACK_ONLY_PARTS)
            assertFalse(LanguageLinesSource.ALBUM_PART.name in PipelineWiring.TRACK_ONLY_PARTS)
        }

    @Test
    fun `with Show genres off the MusicBrainz, Wikipedia and Last fm adapters are built without genres`() {
        val keys = SourceKeys(lastFm = lastFm, web = mapOf(WebEngine.EXA to exa))
        val on = wiring.adapters(keys, Settings()).associate { it.id to it.extractionVersion }
        val off = wiring.adapters(keys, Settings(showGenres = false)).associate { it.id to it.extractionVersion }
        // Wikipedia's adapter writes Wikidata's lines, genres among them (#545).
        for (source in listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM)) {
            assertEquals(on.getValue(source) + WITHOUT_GENRES_VERSION, off.getValue(source), "$source")
        }
        // A source with no genres is built the same either way.
        assertEquals(on.getValue(SourceId.WEB), off.getValue(SourceId.WEB))
    }

    @Test
    fun `a source switched off still gets its adapter, since the pipeline reads the switch`() {
        // Every source has its key, so each one switched off would otherwise get an adapter.
        val keyed = SourceKeys(lastFm = lastFm, web = mapOf(WebEngine.EXA to exa))
        val off = Settings(enabledSources = emptySet())
        assertEquals(
            listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.WEB),
            ids(keyed, off),
        )
    }

    @Test
    fun `closing the wiring closes the client only when the wiring made it`() {
        val borrowed = Answering(image)
        pipelineWiringOn(borrowed, JvmAccentFolder, JvmResourceReader).close()
        assertEquals(0, borrowed.closed)
        val owned = Answering(image)
        PipelineWiring(owned, JvmAccentFolder, JvmResourceReader, ownsNet = true).close()
        assertEquals(1, owned.closed)
    }

    @Test
    fun `the AI providers are Anthropic, OpenAI and Google, each under its own id`() {
        assertEquals(setOf(ProviderId.ANTHROPIC, ProviderId.OPENAI, ProviderId.GOOGLE), wiring.aiProviders.keys)
        for ((id, provider) in wiring.aiProviders) assertEquals(id, provider.id)
    }

    @Test
    fun `the art fetch hands out an image's bytes, and nothing for an address it does not fetch`() =
        runTest {
            assertContentEquals(byteArrayOf(1, 2, 3), wiring.art("https://example.org/cover.jpg"))
            assertNull(wiring.art("content://media/cover"))
            assertEquals(listOf("https://example.org/cover.jpg"), net.asked)
        }

    @Test
    fun `the art fetch hands out nothing for an answer that is not an image`() =
        runTest {
            val page = NetResponse(200, mapOf("Content-Type" to listOf("text/html")), byteArrayOf(1))
            val wiring = pipelineWiringOn(Answering(page), JvmAccentFolder, JvmResourceReader)
            assertNull(wiring.art("https://example.org/cover.jpg"))
        }
}
