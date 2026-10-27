package app.sleevesight.app.secrets

import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.softwareKey
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys' gate (#667): a service that needs a key is off, in the stored settings, while its key is
 * not saved. Settings left on with no key by an earlier version come up off, removing a key or
 * purging them all switches their services off, and saving a key switches nothing on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyGateTest {
    private val key = softwareKey()
    private val settings = SettingsStore(MemoryStore())
    private val secretsData = MemoryStore()
    private val secrets = secretStore()

    private fun secretStore(cipher: SecretCipher = testCipher(key)) =
        SecretStore(secretsData, cipher, legacyKeystore = false, dispatcher = Dispatchers.Unconfined)

    /** Every service that needs a key switched on, as an earlier version could store them. */
    private val allOn: (Settings) -> Settings = {
        it.copy(
            aiProvider = ProviderId.ANTHROPIC,
            enabledSources = it.enabledSources + SourceSwitch.LASTFM + SourceSwitch.WEB,
            webEngines = setOf(WebEngine.EXA),
        )
    }

    private suspend fun stored(): Settings = settings.settings.first()

    private suspend fun saveAll() {
        for (id in listOf(SecretId.ANTHROPIC, SecretId.LASTFM, SecretId.EXA)) secrets.set(id, Secret("key-$id"))
    }

    /** Starts the gate over [store] for the rest of the test, and lets it act. */
    private fun TestScope.gate(store: SecretStore = secrets) {
        backgroundScope.launch { KeyGate(settings, store).run() }
        runCurrent()
    }

    private fun assertAllOn(settings: Settings) {
        assertEquals(ProviderId.ANTHROPIC, settings.aiProvider)
        assertTrue(SourceSwitch.LASTFM in settings.enabledSources)
        assertTrue(WebEngine.EXA in settings.webEngines)
    }

    @Test
    fun onlyTheServicesWhoseKeyIsMissingAreSwitchedOff() {
        val on = allOn(Settings())
        val off = on.keylessOff(setOf(SecretId.ANTHROPIC, SecretId.LASTFM, SecretId.EXA))
        assertNull(off.aiProvider)
        assertFalse(SourceSwitch.LASTFM in off.enabledSources)
        assertEquals(emptySet<WebEngine>(), off.webEngines)
        // Web search's own switch, and the sources that need no key, stay as they were.
        assertEquals(on.enabledSources - SourceSwitch.LASTFM, off.enabledSources)
        assertEquals(on.copy(aiProvider = null), on.keylessOff(setOf(SecretId.ANTHROPIC)))
        // Another service's key missing changes nothing; a provider with no key (Ollama) needs none.
        assertSame(on, on.keylessOff(setOf(SecretId.OPENAI, SecretId.GOOGLE)))
        val ollama = on.copy(aiProvider = ProviderId.OLLAMA)
        assertEquals(ProviderId.OLLAMA, ollama.keylessOff(SecretId.entries.toSet()).aiProvider)
    }

    @Test
    fun settingsFromAnEarlierVersionWithServicesOnAndNoKeyComeUpOff() =
        runTest(UnconfinedTestDispatcher()) {
            settings.update(allOn)
            gate()
            val now = stored()
            assertNull(now.aiProvider)
            assertFalse(now.isEnabled(SourceId.LASTFM))
            assertFalse(now.isEnabled(SourceId.WEB))
            // The services that need no key are left alone.
            assertTrue(SourceSwitch.MUSICBRAINZ in now.enabledSources)
        }

    @Test
    fun theDefaultsComeUpWithTheKeyedSourcesOffOnAFreshInstall() =
        runTest(UnconfinedTestDispatcher()) {
            // Last.fm and Exa are on by default in `:core`, for the command line, which has its keys.
            assertTrue(SourceSwitch.LASTFM in Settings().enabledSources)
            gate()
            assertFalse(SourceSwitch.LASTFM in stored().enabledSources)
            assertEquals(emptySet<WebEngine>(), stored().webEngines)
        }

    @Test
    fun servicesWithTheirKeysKeepTheirSwitches() =
        runTest(UnconfinedTestDispatcher()) {
            saveAll()
            settings.update(allOn)
            gate()
            assertAllOn(stored())
        }

    @Test
    fun removingAKeySwitchesItsServiceOffAndNoOther() =
        runTest(UnconfinedTestDispatcher()) {
            saveAll()
            settings.update(allOn)
            gate()
            assertTrue(secrets.clear(SecretId.LASTFM))
            runCurrent()
            val now = stored()
            assertFalse(SourceSwitch.LASTFM in now.enabledSources)
            assertEquals(ProviderId.ANTHROPIC, now.aiProvider)
            assertTrue(WebEngine.EXA in now.webEngines)
        }

    @Test
    fun purgingTheKeysSwitchesEveryServiceThatNeedsOneOff() =
        runTest(UnconfinedTestDispatcher()) {
            saveAll()
            settings.update(allOn)
            gate()
            assertTrue(secrets.reset().done)
            runCurrent()
            val now = stored()
            assertNull(now.aiProvider)
            assertFalse(SourceSwitch.LASTFM in now.enabledSources)
            assertEquals(emptySet<WebEngine>(), now.webEngines)
        }

    @Test
    fun savingAKeySwitchesNothingOn() =
        runTest(UnconfinedTestDispatcher()) {
            settings.update(allOn)
            gate()
            val off = stored()
            saveAll()
            runCurrent()
            assertEquals(off, stored())
            // Switched on once its key is saved, the service stays on.
            settings.update(allOn)
            runCurrent()
            assertAllOn(stored())
        }

    @Test
    fun aKeyStoredButNoLongerReadableKeepsItsServicesSwitch() =
        runTest(UnconfinedTestDispatcher()) {
            saveAll()
            settings.update(allOn)
            // Another Keystore key than the one that sealed them, as after a restore onto another phone.
            val other = secretStore(testCipher())
            assertEquals(SecretState.UNREADABLE, other.states.first()[SecretId.ANTHROPIC])
            gate(other)
            assertAllOn(stored())
        }

    @Test
    fun aKeysFileThatCannotBeReadSaysNoKeyIsMissing() =
        runTest(UnconfinedTestDispatcher()) {
            val flaky = FlakyDataStore(MemoryStore())
            val store = SecretStore(flaky, testCipher(key), legacyKeystore = false, dispatcher = Dispatchers.Unconfined)
            assertEquals(SecretId.entries.toSet(), store.missing())
            // Every read fails: nothing says a key is gone, so none is switched off for it.
            flaky.failures.set(SecretId.entries.size)
            assertEquals(emptySet<SecretId>(), store.missing())
        }
}
