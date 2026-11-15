package app.sleevesight.app.settings

import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.ui.settings.applyChoices
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private companion object {
        const val PLAYER = "com.example.player"
    }
}
