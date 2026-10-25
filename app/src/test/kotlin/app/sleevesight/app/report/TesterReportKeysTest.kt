package app.sleevesight.app.report

import app.sleevesight.app.TestStores
import app.sleevesight.app.diagnostics.Diagnostics
import app.sleevesight.app.diagnostics.DiagnosticsLog
import app.sleevesight.app.now
import app.sleevesight.app.pipeline.PipelineKeys
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.store.CacheStats
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A tester's report (#452) never holds a key: with a key saved for every service and every service
 * switched on, no key's value is anywhere in the whole report, the history included; and what the
 * report is made from has no way to the key store.
 */
@RunWith(RobolectricTestRunner::class)
class TesterReportKeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun close() {
        scope.coroutineContext[Job]!!.cancel()
        stores.close()
    }

    private val none = TableStats(0, 0)

    @Test
    fun noSavedKeyIsInTheReport() {
        val secrets = SecretStore(stores.open("secrets"), testCipher())
        val keys = SecretId.entries.associateWith { "key-${it.name}-4f9c2e7a1b" }
        runBlocking { for ((id, value) in keys) assertEquals(SaveResult.SAVED, secrets.set(id, Secret(value))) }
        val settings = SettingsStore(stores.open("settings"))
        runBlocking {
            settings.update {
                it.copy(aiProvider = ProviderId.ANTHROPIC, webEngines = WebEngine.entries.toSet(), developerMode = true)
            }
        }
        val diagnostics =
            Diagnostics(
                log = DiagnosticsLog(),
                readChanges = { _, _ -> TrackChangePage(emptyList(), null) },
                readCacheStats = { CacheStats(none, none, none, none) },
                io = EmptyCoroutineContext,
                scope = scope,
                version = "1.2.3",
            )
        val reports =
            TesterReports(
                build = AppBuild("1.2.3", 7, testBuild = true),
                view = MutableStateFlow(PipelineView(track = track(insight = written("It topped the charts [W1].")))),
                playing = MutableStateFlow(sundown),
                playback = MutableStateFlow(PlaybackInfo.NONE),
                diagnostics = diagnostics,
            )

        val snapshot = reports.snapshot(settings.now())
        val parts =
            TesterPart.entries.map { part ->
                part to (TesterReport.part(part, snapshot) ?: runBlocking { reports.history(snapshot) })
            }
        val whole = TesterReport.mail(snapshot, "", parts).asText()

        assertTrue(whole.contains("aiProvider: Anthropic"))
        for (value in keys.values) {
            assertFalse(value, whole.contains(value))
            // Not even the last four characters, by which the app shows a saved key.
            assertFalse(value, whole.contains(value.takeLast(4)))
        }
    }

    @Test
    fun whatTheReportIsMadeFromHasNoWayToTheKeys() {
        val keyTypes = setOf(Secret::class.java, SecretStore::class.java, PipelineKeys::class.java)
        for (type in listOf(TesterSnapshot::class.java, TesterReports::class.java)) {
            for (field in type.declaredFields) {
                assertFalse("${type.simpleName}.${field.name}", field.type in keyTypes)
            }
        }
    }
}
