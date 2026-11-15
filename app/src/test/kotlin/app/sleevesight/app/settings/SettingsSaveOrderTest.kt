package app.sleevesight.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.memorySettings
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The screens' saves in the order asked for (#1146): two quick changes to a setting, such as a fast
 * drag on a slider or two quick taps on a button group, end at the second, however the threads
 * they hop to run them; each keeps its own result. The race is made certain by a dispatcher that
 * runs the work given it newest first ([NewestFirst]), as a pool of threads may.
 */
class SettingsSaveOrderTest {
    private val newestFirst = NewestFirst()

    // Saves on the caller's thread, as the app's are (on the main thread, `Dispatchers.Main.immediate`).
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun close() {
        saves.cancel()
    }

    // Read from the DataStore itself: the store's own reads would wait on [newestFirst].
    private fun DataStore<Preferences>.now(): Settings = SettingsCodec.read(runBlocking { data.first() }, Settings())

    @Test
    fun twoQuickChangesEndAtTheSecondThoughTheirWritesStartNewestFirst() {
        // The app's race: each save hops to the store's dispatcher (IO) for its write.
        val data = MemoryStore()
        val store = SettingsStore(data, dispatcher = newestFirst)
        store.saving(saves) { store.update { it.copy(musicBrainzWait = 30.seconds) } }
        store.saving(saves) { store.update { it.copy(musicBrainzWait = 1.minutes) } }
        newestFirst.runAll()
        assertEquals(1.minutes, data.now().musicBrainzWait)
    }

    @Test
    fun twoQuickChangesEndAtTheSecondThoughTheirSavesStartNewestFirst() {
        // Saves in a scope on a pool of threads, such as `Dispatchers.Default`.
        val data = MemoryStore()
        val store = memorySettings(data)
        val pooled = CoroutineScope(SupervisorJob() + newestFirst)
        store.saving(pooled) { store.update { it.copy(musicBrainzWait = 30.seconds) } }
        store.saving(pooled) { store.update { it.copy(musicBrainzWait = 1.minutes) } }
        newestFirst.runAll()
        pooled.cancel()
        assertEquals(1.minutes, data.now().musicBrainzWait)
    }

    @Test
    fun eachSaveKeepsItsResultAndOneThatFailsDoesNotHoldUpTheNext() {
        val data = FlakyDataStore(MemoryStore()).apply { writeFailures.set(1) }
        val store = SettingsStore(data, dispatcher = newestFirst)
        val first = store.saving(saves) { store.update { it.copy(musicBrainzWait = 30.seconds) } }
        val second = store.saving(saves) { store.update { it.copy(musicBrainzWait = 1.minutes) } }
        newestFirst.runAll()
        assertEquals("the first write failed", false, runBlocking { first.await() })
        assertEquals("the second was saved", true, runBlocking { second.await() })
        assertEquals(1.minutes, data.now().musicBrainzWait)
    }

    @Test
    fun twoQuickModesChosenEndAtTheSecond() {
        val data = MemoryStore()
        val store = SettingsStore(data, dispatcher = newestFirst)
        val choose = store.writingModeChooser(saves)
        val first = choose(WriteAutomatically.WHEN_I_SKIP)
        val second = choose(WriteAutomatically.NEVER)
        newestFirst.runAll()
        assertEquals(listOf(true, true), runBlocking { listOf(first.await(), second.await()) })
        assertEquals(WriteAutomatically.NEVER, data.now().writeAutomatically)
    }

    @Test
    fun twoQuickThemesChosenEndAtTheSecond() {
        val data = MemoryStore()
        val store = SettingsStore(data, dispatcher = newestFirst)
        val choose = store.themeChooser(saves)
        choose(ThemeChoice.DARK)
        choose(ThemeChoice.LIGHT)
        newestFirst.runAll()
        assertEquals(ThemeChoice.LIGHT, data.now().theme)
    }

    /** Runs the work dispatched to it when told ([runAll]), the newest first, on the test's thread. */
    private class NewestFirst : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queued.addLast(block)
        }

        /** Runs what is queued, newest first, and what that queues, until nothing is left. */
        fun runAll() {
            while (queued.isNotEmpty()) queued.removeLast().run()
        }
    }
}
