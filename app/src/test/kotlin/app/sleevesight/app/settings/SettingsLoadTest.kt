package app.sleevesight.app.settings

import app.sleevesight.app.memorySettings
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

/**
 * *Load from a file* (#815) through the store: what a file holds is loaded into the stored settings,
 * a setting it does not have keeps its value, a mode it changes is told as chosen at the tap, and its
 * message is kept with the store until a screen says it.
 */
class SettingsLoadTest {
    private fun contents(settings: Settings) = SettingsFile.read(SettingsFile.write(settings, "0.10.0"))!!

    @Test
    fun aFileSavedFromOneStoreLoadsIntoAnotherAsItWas() =
        runBlocking {
            val from = memorySettings()
            from.update {
                it.copy(
                    theme = ThemeChoice.DARK,
                    logoSpeed = LogoSpeed.RPM_45,
                    aiProvider = ProviderId.ANTHROPIC,
                    followedPlayers = mapOf("org.example.tunes" to true),
                    historyLifetime = null,
                )
            }
            val saved = from.settings.first()
            val into = memorySettings()
            into.loadFile(contents(saved))
            val loaded = into.settings.first()
            // Each setting the file holds as saved; the others as a new store has them.
            assertEquals(saved, loaded)
        }

    @Test
    fun aSettingTheFileDoesNotHaveKeepsTheValueStoredWhenTheLoadIsSaved() =
        runBlocking {
            val store = memorySettings()
            store.update { it.copy(spinLogo = false) }
            val file =
                SettingsFile.read(
                    """{"file": "Sleevesight settings", "format": 1, "settings": {"theme": "LIGHT"}}""",
                )!!
            store.loadFile(file)
            val now = store.settings.first()
            assertEquals(ThemeChoice.LIGHT, now.theme)
            assertEquals(false, now.spinLogo)
        }

    @Test
    fun aModeTheLoadChangesIsToldAtTheTapAndItsMessageKept() =
        runBlocking {
            val store = memorySettings()
            val saves = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                val told = mutableListOf<WriteAutomatically>()
                val settled = mutableListOf<WriteAutomatically?>()
                val load =
                    store.loader(saves) { mode ->
                        told += mode
                        { stored -> settled += stored }
                    }
                val shown = store.settings.first()
                val saved =
                    load(shown, contents(Settings(writeAutomatically = WriteAutomatically.NEVER)), "1 setting loaded.")
                assertEquals(listOf(WriteAutomatically.NEVER), told)
                assertTrue(saved.await())
                assertEquals(listOf<WriteAutomatically?>(WriteAutomatically.NEVER), settled)
                assertEquals(WriteAutomatically.NEVER, store.settings.first().writeAutomatically)
                assertEquals(
                    "1 setting loaded.",
                    store.resets.pending.value
                        ?.message,
                )
            } finally {
                saves.cancel()
            }
        }

    @Test
    fun aLoadThatKeepsTheModeTellsNothing() =
        runBlocking {
            val store = memorySettings()
            val saves = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                val told = mutableListOf<WriteAutomatically>()
                val load =
                    store.loader(saves) { mode ->
                        told += mode;
                        {}
                    }
                assertTrue(load(store.settings.first(), contents(Settings(theme = ThemeChoice.DARK)), "").await())
                assertEquals(emptyList<WriteAutomatically>(), told)
            } finally {
                saves.cancel()
            }
        }

    @Test
    fun aLoadNeverShortensKeepHistoryForInTheStore() =
        runBlocking {
            val store = memorySettings()
            store.loadFile(contents(Settings(historyLifetime = 7.days)))
            assertEquals(Settings.DEFAULT_HISTORY_LIFETIME, store.settings.first().historyLifetime)
        }
}
