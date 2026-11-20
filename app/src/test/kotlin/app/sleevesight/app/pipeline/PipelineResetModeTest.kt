package app.sleevesight.app.pipeline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.settings.resetter
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * *Reset to the defaults* (#960) and *Reactive*'s mode: once a mode has been chosen in the process,
 * the pipeline no longer follows the store's mode, so a reset that changes it tells the pipeline as
 * a choice does. On demand chosen, then a reset: the next track is written automatically again.
 * The reset's place among the choices is taken when it is asked for, so On demand chosen while its
 * save waits stays chosen, its own save saved or not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineResetModeTest : PipelineHostFixture() {
    /** A store whose saves fail with an I/O error while [fail] is set. */
    private class Failing : DataStore<Preferences> {
        private val inner = MemoryStore()
        var fail = false

        override val data: Flow<Preferences> = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            if (fail) throw IOException("not saved") else inner.updateData(transform)
    }

    private val data = Failing()

    // The settings in a store, mirrored to the host's settings as the app's flow is.
    private fun TestScope.store(): SettingsStore {
        val store =
            SettingsStore(data, Settings(), StandardTestDispatcher(testScheduler))
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
            store.resetter(backgroundScope, host::modeChosen)(settings.value)
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
            }(settings.value)
            runCurrent()
            assertEquals(emptyList<WriteAutomatically>(), told)
        }

    // One written on a tap, then *On track skip (auto)* chosen and saved; the store's saves then held
    // behind a gate, and a reset asked for, which puts the mode back to *On track change (auto)*.
    private fun TestScope.resetHeld(store: SettingsStore): CompletableDeferred<Unit> {
        started()
        play("One")
        settle()
        tap()
        settle()
        store.writingModeChooser(backgroundScope, host::modeChosen)(WriteAutomatically.WHEN_I_SKIP)
        settle()
        val gate = CompletableDeferred<Unit>()
        store.saveOrder.launch(backgroundScope) { gate.await() }
        store.resetter(backgroundScope, host::modeChosen)(settings.value)
        return gate
    }

    @Test
    fun `On demand chosen while a reset waits to be saved stays chosen`() =
        runTest {
            val store = store()
            val gate = resetHeld(store)
            // The choice's save held too, behind the reset's.
            val choiceGate = CompletableDeferred<Unit>()
            store.saveOrder.launch(backgroundScope) { choiceGate.await() }
            store.writingModeChooser(backgroundScope, host::modeChosen)(WriteAutomatically.NEVER)
            gate.complete(Unit)
            settle()
            // The reset is saved, the choice not yet: the choice holds all the same.
            assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, settings.value.writeAutomatically)
            play("Two")
            settle()
            assertEquals("the later choice holds while its save waits", 0, provider.requestsFor("Two"))
            choiceGate.complete(Unit)
            settle()
            assertEquals(WriteAutomatically.NEVER, settings.value.writeAutomatically)
            play("Three")
            settle()
            assertEquals("and once it is saved", 0, provider.requestsFor("Three"))
        }

    @Test
    fun `On demand chosen while a reset waits to be saved stays chosen when its own save fails`() =
        runTest {
            val store = store()
            val gate = resetHeld(store)
            // The reset is saved; the choice after it is not.
            store.saveOrder.launch(backgroundScope) { data.fail = true }
            store.writingModeChooser(backgroundScope, host::modeChosen)(WriteAutomatically.NEVER)
            gate.complete(Unit)
            settle()
            assertEquals(WriteAutomatically.ON_EVERY_TRACK_CHANGE, settings.value.writeAutomatically)
            play("Two")
            settle()
            assertEquals("never wider than the later choice", 0, provider.requestsFor("Two"))
        }
}
