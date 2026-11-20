package app.sleevesight.app.settings

import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.time.Duration.Companion.minutes

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
                    load(
                        shown,
                        contents(Settings(writeAutomatically = WriteAutomatically.NEVER)),
                        LoadMessage("1 setting loaded."),
                    )
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
    fun aFileWithoutAModeTellsNothing() =
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
                val noMode = """{"file": "Sleevesight settings", "format": 1, "settings": {"theme": "DARK"}}"""
                assertTrue(load(store.settings.first(), SettingsFile.read(noMode)!!, LoadMessage("")).await())
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

    @Test
    fun aModeInTheFileIsToldAtTheTapEvenWhenItIsTheOneShownWhileAnotherChoiceWaits() =
        runBlocking {
            val store = memorySettings()
            store.update { it.copy(writeAutomatically = WriteAutomatically.NEVER) }
            val saves = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                val told = mutableListOf<WriteAutomatically>()
                val chosen = { mode: WriteAutomatically ->
                    told += mode
                    { _: WriteAutomatically? -> }
                }
                // A save held, then an automatic mode chosen behind it: the screen still shows On demand.
                val gate = CompletableDeferred<Unit>()
                store.saveOrder.launch(saves) { gate.await() }
                store.writingModeChooser(saves, chosen)(WriteAutomatically.ON_EVERY_TRACK_CHANGE)
                val shown = store.settings.first()
                assertEquals(WriteAutomatically.NEVER, shown.writeAutomatically)
                val file = Settings(writeAutomatically = WriteAutomatically.NEVER, theme = ThemeChoice.DARK)
                val saved = store.loader(saves, chosen = chosen)(shown, contents(file), LoadMessage(""))
                // The file's mode is the last choice, as its save comes last.
                assertEquals(listOf(WriteAutomatically.ON_EVERY_TRACK_CHANGE, WriteAutomatically.NEVER), told)
                gate.complete(Unit)
                assertTrue(saved.await())
                assertEquals(WriteAutomatically.NEVER, store.settings.first().writeAutomatically)
            } finally {
                saves.cancel()
            }
        }

    @Test
    fun aServiceTheFileSwitchesOnStaysOffWithoutItsKeyAndIsNamed() =
        runBlocking {
            val store = memorySettings()
            val file = Settings(aiProvider = ProviderId.ANTHROPIC, theme = ThemeChoice.DARK)
            val plan = loadPlan(contents(file), store.settings.first(), setOf(SecretId.ANTHROPIC))
            assertEquals(null, plan.settings.aiProvider)
            assertEquals(listOf<Keyless>(Keyless.Provider(ProviderId.ANTHROPIC)), plan.keyless)
            store.loadFile(contents(file), setOf(SecretId.ANTHROPIC))
            assertEquals(null, store.settings.first().aiProvider)
            assertEquals(ThemeChoice.DARK, store.settings.first().theme)
            // With the key saved, it is switched on.
            assertEquals(ProviderId.ANTHROPIC, loadPlan(contents(file), Settings(), emptySet()).settings.aiProvider)
        }

    @Test
    fun aTimeLoadedReadsBackFromTheStoreAsLoadedAndOneThatWouldNotIsSkipped() =
        runBlocking {
            val store = memorySettings()
            val file = { gap: String, web: String ->
                SettingsFile.read(
                    """{"file": "Sleevesight settings", "format": 1, "settings": """ +
                        """{"sessionGap": "$gap", "cacheLifetimes": {"WEB": "$web"}, "crossfade": "$gap"}}""",
                )!!
            }
            // Finer than the store keeps: each skipped, and the store keeps its own.
            val fine = file("1ns", "1ms")
            assertEquals(3, fine.skipped.size)
            val before = store.settings.first()
            store.loadFile(fine)
            assertEquals(before, store.settings.first())
            // Whole minutes and days: as loaded, through the store's milliseconds.
            store.loadFile(file("45m", "2d"))
            val now = store.settings.first()
            assertEquals(45.minutes, now.sessionGap)
            assertEquals(2.days, now.cacheLifetimes[SourceId.WEB])
        }

    @Test
    fun aLoadThatSavesOtherThanThePreviewSaysWhatItSaved() =
        runBlocking {
            val store = memorySettings()
            store.update { it.copy(historyLifetime = 90.days) }
            val saves = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                val shown = store.settings.first()
                val file = contents(Settings(historyLifetime = 180.days))
                val previewed = loadPlan(file, shown, emptySet())
                assertEquals(180.days, previewed.settings.historyLifetime)
                // *Forever* chosen just before, its save held: the preview still shows 90 days.
                val gate = CompletableDeferred<Unit>()
                store.saving(saves) {
                    gate.await()
                    store.update { it.copy(historyLifetime = null) }
                }
                val message =
                    LoadMessage("1 setting loaded.", SettingsFile.changes(shown, previewed.settings), 0) { n, k ->
                        "$n loaded, $k skipped"
                    }
                val saved = store.loader(saves)(shown, file, message)
                gate.complete(Unit)
                assertTrue(saved.await())
                // 180 days would shorten *Forever*: not loaded, and the message says so.
                assertEquals(null, store.settings.first().historyLifetime)
                // The preview's other changes, if any, all saved; *Keep history for* not.
                assertEquals(
                    "${message.expected!!.size - 1} loaded, 1 skipped",
                    store.resets.pending.value
                        ?.message,
                )
            } finally {
                saves.cancel()
            }
        }

    @Test
    fun webSearchAndItsEnginesLoadOnlyAsTheirRowsShowThem() {
        val web = SourceSwitch.WEB
        val exa = WebEngine.EXA

        fun at(
            webOn: Boolean,
            exaOn: Boolean,
        ) = Settings(
            enabledSources = Settings.DEFAULT_SOURCES.let { if (webOn) it + web else it - web },
            webEngines = if (exaOn) setOf(exa) else emptySet(),
        )
        val all = listOf(false to false, false to true, true to false, true to true)
        for ((nowWeb, nowExa) in all) {
            for ((fileWeb, fileExa) in all) {
                val now = at(nowWeb, nowExa)
                val loaded = loadPlan(contents(at(fileWeb, fileExa)), now, emptySet()).settings
                val rowNow = nowWeb && nowExa
                val rowFile = fileWeb && fileExa
                val case = "now $nowWeb/$nowExa, file $fileWeb/$fileExa"
                // The row as the file has it; nothing else of web search changed unless the row did.
                assertEquals(case, rowFile, web in loaded.enabledSources && exa in loaded.webEngines)
                if (rowNow == rowFile) assertEquals(case, now, loaded)
            }
        }
    }
}
