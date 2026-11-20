package app.sleevesight.app.pipeline

import app.sleevesight.app.MemoryStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.settings.resetter
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Reset to the defaults* (#960) and *Reactive*'s mode: once a mode has been chosen in the process,
 * the pipeline no longer follows the store's mode, so a reset that changes it tells the pipeline as
 * a choice does. On demand chosen, then a reset: the next track is written automatically again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineResetModeTest : PipelineHostFixture() {
    // The settings in a store, mirrored to the host's settings as the app's flow is.
    private fun TestScope.store(): SettingsStore {
        val store =
            SettingsStore(MemoryStore(), Settings(), StandardTestDispatcher(testScheduler))
        backgroundScope.launch { store.settings.onEach { settings.value = it }.collect() }
        backgroundScope.launch { store.update { it.copy(aiProvider = ProviderId.ANTHROPIC) } }
        runCurrent()
        return store
    }

    @Test
    fun `On demand chosen, then a reset, the next track is written automatically`() =
        runTest {
            val store = store()
            started()
            play("One")
            settle()
            tap()
            settle()
            assertEquals(1, provider.requestsFor("One"))
            store.writingModeChooser(backgroundScope, host::modeChosen)(WriteAutomatically.NEVER)
            settle()
            play("Two")
            settle()
            assertEquals("On demand writes nothing unasked", 0, provider.requestsFor("Two"))
            store.resetter(backgroundScope, host::modeChosen)()
            settle()
            assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, settings.value.writeAutomatically)
            play("Three")
            settle()
            assertEquals("the reset's mode writes again", 1, provider.requestsFor("Three"))
        }

    @Test
    fun `a reset that leaves the mode as it is tells the pipeline nothing`() =
        runTest {
            val store = store()
            val told = mutableListOf<WriteAutomatically>()
            store.resetter(backgroundScope) { mode ->
                told += mode
                {}
            }()
            runCurrent()
            assertEquals(emptyList<WriteAutomatically>(), told)
        }
}
