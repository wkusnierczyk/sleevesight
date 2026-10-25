package app.sleevesight.app.ui

import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * The settings dialogs that save or remove keys, while their work runs (#242): a second tap in the
 * same frame, before a recomposition can disable the button, starts nothing more, and a removal
 * cannot be cancelled or dismissed. Each holds its write open on a gated [FlakyDataStore].
 */
@RunWith(RobolectricTestRunner::class)
class BusyDialogTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val flaky by lazy { FlakyDataStore(stores.open("gated-secrets")) }
    private val secrets by lazy { SecretStore(flaky, testCipher()) }

    /** Lets the writes through; until then, each waits. */
    private val gate = CompletableDeferred<Unit>()

    /** Where the screen saves, as the process's scope in the app: it outlives the screen. */
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        // Also when a test fails, so no write stays blocked for the rest of the run.
        gate.complete(Unit)
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(
        scanner: QrScanner = { _, _ -> },
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        scanner = scanner,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            // The keys' rows, *Load keys* and *Purge keys* are under *Secrets* (#444).
            compose.openSection(SettingsSection.SECRETS)
            scenario.block()
        }
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = 5_000) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun gone(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    /**
     * Taps the button [label] twice with no recomposition between, as two taps in one frame would:
     * its `enabled` cannot have changed yet.
     */
    private fun tapTwiceInOneFrame(label: String) {
        val button = compose.onNodeWithText(label).fetchSemanticsNode()
        compose.runOnUiThread { repeat(2) { button.config[SemanticsActions.OnClick].action?.invoke() } }
    }

    /**
     * Releases the gate, waits for the dialog to go ([dialog] gone) and for every save started on
     * [saves], and returns the writes asked for since [before]: a second save would be counted by then.
     */
    private fun release(
        dialog: SemanticsMatcher,
        before: Int,
    ): Int {
        gate.complete(Unit)
        waitFor("the dialog gone") { gone(dialog) }
        runBlocking {
            saves.coroutineContext.job.children
                .forEach { it.join() }
        }
        return flaky.writes.get() - before
    }

    @Test
    fun aRemovalRunsOnceAndCannotBeCancelledOrDismissedWhileItRuns() {
        runBlocking { secrets.set(SecretId.EXA, Secret("exa-key")) }
        show {
            val removeExa = app.getString(R.string.settings_key_remove, text(R.string.settings_key_name_exa))
            waitForNode(hasContentDescription(removeExa))
            val before = flaky.writes.get()
            flaky.writeGate = gate
            compose.onNodeWithContentDescription(removeExa).performScrollTo().performClick()

            tapTwiceInOneFrame(text(R.string.settings_remove))
            waitFor("the removal's write started") { flaky.writes.get() == before + 1 }
            val cancel = hasText(text(R.string.settings_cancel))
            waitForNode(cancel and isNotEnabled())
            compose.onNode(cancel).performClick()
            // Back, as the dialog's window hears it.
            onActivity {
                (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            }
            compose.onNodeWithText(text(R.string.settings_remove)).assertIsDisplayed()

            assertEquals(1, release(hasText(text(R.string.settings_remove)), before))
            assertEquals(null, runBlocking { secrets.get(SecretId.EXA) })
        }
    }

    @Test
    fun aKeySavedByTwoTapsInOneFrameIsSavedOnce() =
        show {
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            val before = flaky.writes.get()
            flaky.writeGate = gate

            tapTwiceInOneFrame(text(R.string.settings_save))
            waitFor("the save's write started") { flaky.writes.get() == before + 1 }

            assertEquals(1, release(hasSetTextAction(), before))
            assertEquals("exa-key", runBlocking { secrets.get(SecretId.EXA) }?.reveal())
        }

    @Test
    fun aSetupSavedByTwoTapsInOneFrameIsSavedOnce() {
        val lastFmKey = "0123456789abcdef0123456789abcdef"
        // The scanner reads the setup code at once, as the camera would on a code held up to it.
        val code = "sleevesight-keys:v1\nlastfm=$lastFmKey"
        show(scanner = { onScanned, _ -> LaunchedEffect(Unit) { onScanned(code) } }) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.setup_title))
            val before = flaky.writes.get()
            flaky.writeGate = gate

            tapTwiceInOneFrame(text(R.string.settings_save))
            waitFor("the save's write started") { flaky.writes.get() == before + 1 }

            assertEquals(1, release(hasText(text(R.string.setup_title)), before))
            assertEquals(lastFmKey, runBlocking { secrets.get(SecretId.LASTFM) }?.reveal())
        }
    }
}
