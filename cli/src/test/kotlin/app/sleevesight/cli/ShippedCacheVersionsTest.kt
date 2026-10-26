package app.sleevesight.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WITHOUT_GENRES_VERSION
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.store.Caches
import app.sleevesight.store.db.CacheDatabase
import app.sleevesight.wiring.SourceKeys
import app.sleevesight.wiring.pipelineWiringOn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Results 0.1.4 kept in the retrieval cache with genre information in their lines are not served
 * with *Show genres* off (#545): Last.fm's, kept without genres under 2 + [WITHOUT_GENRES_VERSION]
 * but still with a line of the artist's tags, and Wikipedia's, kept under 1 either way, with
 * Wikidata's genre lines. Through the real cache and the adapters the app builds.
 */
class ShippedCacheVersionsTest {
    private object Offline : NetClient {
        override suspend fun send(request: NetRequest): NetResult<NetResponse> = NetResult.Failure(NetError.Server(503))

        override fun close() = Unit
    }

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CacheDatabase.Schema.create(it) }
    private val normalizer = Normalizer(JvmAccentFolder)
    private val off = Settings(showGenres = false)
    private val caches = Caches(driver, { off }, normalizer)
    private val track = normalizer.normalize("Sundown", "Gordon Lightfoot")
    private val adapters =
        pipelineWiringOn(Offline, JvmAccentFolder, JvmResourceReader).adapters(SourceKeys(lastFm = Secret("key")), off)

    @AfterTest
    fun close() = driver.close()

    private fun adapter(source: SourceId): SourceAdapter = adapters.single { it.id == source }

    /** Keeps, as 0.1.4 did for [adapter]'s source at [shipped], a result whose one line is [line]. */
    private fun keepAsShipped(
        adapter: SourceAdapter,
        shipped: Int,
        source: SourceId,
        line: String,
    ) {
        val draft = EvidenceDraft(source, Scope.ARTIST, line, "https://example.org/", Clock.System.now())
        val result = SourceResult(Outcome.ArtistArticle, listOf(draft), Duration.ZERO)
        val key = caches.retrieval.keyOf(track, null)
        caches.retrieval.put(adapter.id, key, shipped, result, adapter.consults)
        assertNotNull(caches.retrieval.get(adapter.id, key, shipped), "kept as shipped")
    }

    @Test
    fun `Last fm's result kept without genres by 0_1_4, with its tags line, is not served`() {
        val lastFm = adapter(SourceId.LASTFM)
        val tags = "Last.fm listeners tag Gordon Lightfoot as: folk."
        keepAsShipped(lastFm, 2 + WITHOUT_GENRES_VERSION, SourceId.LASTFM, tags)
        assertNull(caches.retrieval.get(lastFm, track, null))
    }

    @Test
    fun `Wikipedia's result kept by 0_1_4, with Wikidata's genre line, is not served without genres`() {
        val wikipedia = adapter(SourceId.WIKIPEDIA)
        keepAsShipped(wikipedia, 1, SourceId.WIKIDATA, "The genre of “Gordon Lightfoot” is folk.")
        assertNull(caches.retrieval.get(wikipedia, track, null))
    }
}
