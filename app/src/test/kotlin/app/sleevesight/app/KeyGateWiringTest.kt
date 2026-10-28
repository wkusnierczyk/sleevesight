package app.sleevesight.app

import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.core.Secret
import app.sleevesight.core.settings.WebEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The keys' guard on a save as `AppGraph` wires it (#667): its key store saves the services that
 * need a key off before that key is saved where none is stored. Before the first run is
 * acknowledged the keys' gate does not run, so Exa, on by default with no key, stays stored on until
 * a key saved for it writes it off: the graph's own key store does, or nothing does.
 */
@RunWith(RobolectricTestRunner::class)
class KeyGateWiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }

    @Before
    fun standIns() {
        app.makeGraph = { AppGraph(it, settingsData = stores.open("settings"), secretCipher = testCipher()) }
    }

    @After
    fun close() = stores.close()

    @Test
    fun aKeySavedThroughTheGraphWritesItsServiceOffFirst() {
        val graph = app.graph
        assertTrue("Exa on by default", WebEngine.EXA in graph.settings.now().webEngines)
        assertEquals(SaveResult.SAVED, runBlocking { graph.secrets.set(SecretId.EXA, Secret("exa-key")) })
        assertFalse(WebEngine.EXA in graph.settings.now().webEngines)
    }
}
