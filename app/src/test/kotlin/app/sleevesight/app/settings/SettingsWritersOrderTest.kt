package app.sleevesight.app.settings

import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.secrets.servicesOff
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.applyChoices
import app.sleevesight.core.Secret
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings' other writers in line with the screens' saves (#1146): while a slow save holds the
 * line and a change on the settings screen waits behind it, a later write from Home, the key
 * dialogs or the app's list of players waits its turn too, so the waiting change cannot overwrite
 * it. The first save's write is held ([held]); everything runs on the test's thread.
 */
class SettingsWritersOrderTest {
    private val data = FlakyDataStore(MemoryStore())
    private val store = memorySettings(data)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val held = CompletableDeferred<Unit>()

    // The keys, in memory, each first save and each purge writing services off, as the app's do.
    private val secretsData = FlakyDataStore(MemoryStore())
    private val secrets =
        SecretStore(
            secretsData,
            testCipher(),
            legacyKeystore = false,
            dispatcher = Dispatchers.Unconfined,
            servicesOff = store::servicesOff,
        )

    @After
    fun close() {
        // Also when a test fails, so no write stays held.
        held.complete(Unit)
        saves.cancel()
    }

    private fun now(): Settings = runBlocking { store.settings.first() }

    /** Starts a slow save, its write held until [held] completes, then [then] while it waits. */
    private fun whileASaveIsHeld(then: () -> Unit) {
        val before = data.writes.get()
        data.writeGate = held
        store.saving(saves) { store.update { it.copy(musicBrainzWait = Settings().musicBrainzWait * 2) } }
        assertEquals("the slow save's write started", before + 1, data.writes.get())
        data.writeGate = null
        then()
        held.complete(Unit)
    }

    @Test
    fun homesHideComesAfterAReportButtonChangeWaitingBeforeIt() {
        whileASaveIsHeld {
            store.saving(saves) { store.update { it.copy(reportButton = ReportButton.SHOW) } }
            store.reportButtonHider(saves)()
        }
        assertEquals(ReportButton.HIDE, now().reportButton)
    }

    @Test
    fun aRemovedPlayerStaysForgottenThoughASwitchForItWaitedBeforeIt() {
        runBlocking { store.playerSeen(PLAYER) }
        whileASaveIsHeld {
            store.saving(saves) { store.update { it.copy(followedPlayers = it.followedPlayers + (PLAYER to true)) } }
            saves.launch { store.forgetPlayer(PLAYER) }
        }
        assertFalse("the player listed again", PLAYER in now().followedPlayers)
    }

    @Test
    fun aKeyDialogsChoiceComesAfterASwitchWaitingBeforeIt() {
        whileASaveIsHeld {
            store.saving(saves) { store.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.LASTFM) } }
            saves.launch { store.applyChoices(mapOf(SecretId.LASTFM to true)) }
        }
        assertTrue("Last.fm switched off", SourceSwitch.LASTFM in now().enabledSources)
    }

    // Codex's sequence on #1311: a choice to switch Last.fm on waits its turn; *Purge keys* deletes
    // the Keystore key but cannot remove the entries, which then count as stored for the keys' gate.
    // Its services written off must come after the waiting choice, or Last.fm stays on, and a key
    // saved over the entry left would find it on.
    @Test
    fun aPurgeThatLeavesTheEntriesStillWritesOffAChoiceWaitingBeforeIt() {
        runBlocking { secrets.set(SecretId.LASTFM, Secret("lastfm-key")) }
        whileASaveIsHeld {
            saves.launch { store.applyChoices(mapOf(SecretId.LASTFM to true)) }
            secretsData.writeFailures.set(1)
            saves.launch { secrets.reset() }
        }
        assertEquals("the entries could not be removed", 0, secretsData.writeFailures.get())
        assertTrue("the Last.fm entry left", runBlocking { secrets.isStored(SecretId.LASTFM) })
        assertFalse("Last.fm on after the purge", SourceSwitch.LASTFM in now().enabledSources)
    }

    // One lock order, the key store's lock and then a turn (`SaveOrder`): with every writer of the
    // settings waiting its turn while a key's first save holds the key store's lock and waits for a
    // turn too, and a purge waiting for that lock, everything ends. A writer that took the key
    // store's lock in its turn would wait for the key save, which waits for that turn: this would
    // hang, and the timeout fail it.
    @Test(timeout = DEADLOCK_TIMEOUT_MS)
    fun keySavesAndPurgesWaitingOnTheSettingsLineEndOnceItMoves() {
        val done = mutableListOf<Deferred<Boolean>>()
        whileASaveIsHeld {
            // Every writer of the settings, waiting its turn behind the save held.
            done += store.saving(saves) { store.update { it.copy(reportButton = ReportButton.SHOW) } }
            done += store.writingModeChooser(saves)(WriteAutomatically.NEVER)
            done += store.reportButtonHider(saves)()
            store.themeChooser(saves)(ThemeChoice.DARK)
            done += saves.async { store.applyChoices(mapOf(SecretId.EXA to false)) != null }
            done += saves.async { store.forgetPlayer(PLAYER).let { true } }
            // The key dialog's save: the key, its services off first (holding the key store's lock
            // while it waits for a turn), then the choice made with it.
            done +=
                saves.async {
                    secrets.set(SecretId.LASTFM, Secret("lastfm-key"))
                    store.applyChoices(mapOf(SecretId.LASTFM to true)) != null
                }
            // *Purge keys*, waiting for the key store's lock.
            done += saves.async { secrets.reset().sealDeleted }
        }
        assertEquals(listOf(true, true, true, true, true, true, true), runBlocking { done.awaitAll() })
        assertEquals(ThemeChoice.DARK, now().theme)
    }

    private companion object {
        const val DEADLOCK_TIMEOUT_MS = 60_000L

        const val PLAYER = "com.example.player"
    }
}
