package app.sleevesight.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
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
import kotlin.time.Duration

/**
 * Results 0.6.3 kept for a classical track MusicBrainz missed (#1129), before its search left out
 * the composer's lead and searched by the first performer: MusicBrainz's *nothing found*, kept under
 * extraction version 11, and Wikipedia's and Last.fm's verdicts against that miss, kept under 3 and
 * 4, are not served after the upgrade, so all are asked again. Through the real cache and the
 * adapters the app builds.
 */
class ComposerSearchCacheTest {
    private object Offline : NetClient {
        override suspend fun send(request: NetRequest): NetResult<NetResponse> = NetResult.Failure(NetError.Server(503))

        override fun close() = Unit
    }

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CacheDatabase.Schema.create(it) }
    private val normalizer = Normalizer(JvmAccentFolder)
    private val settings = Settings()
    private val caches = Caches(driver, { settings }, normalizer)
    private val track =
        normalizer.normalize(
            "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            "Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields",
        )
    private val adapters =
        pipelineWiringOn(
            Offline,
            JvmAccentFolder,
            JvmResourceReader,
        ).adapters(SourceKeys(lastFm = Secret("key")), settings)

    @AfterTest
    fun close() = driver.close()

    private fun adapter(source: SourceId): SourceAdapter = adapters.single { it.id == source }

    @Test
    fun `MusicBrainz's, Wikipedia's and Last fm's results kept before the composer search are asked again`() {
        val key = caches.retrieval.keyOf(track, null)
        val shipped = listOf(SourceId.MUSICBRAINZ to 11, SourceId.WIKIPEDIA to 3, SourceId.LASTFM to 4)
        for ((source, version) in shipped) {
            val adapter = adapter(source)
            val result = SourceResult(Outcome.None, emptyList(), Duration.ZERO)
            caches.retrieval.put(adapter.id, key, version, result, adapter.consults)
            assertNotNull(caches.retrieval.get(adapter.id, key, version), "$source kept as shipped")
            assertNull(caches.retrieval.get(adapter, track, null), "$source served after the upgrade")
        }
    }
}
