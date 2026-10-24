package app.sleevesight.app.ui.settings

import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromAutofillValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAutofillManager

/**
 * A password manager fills each key field, and is never asked to save the key (#376): the dialog
 * cancels the autofill session before anything can commit it. The platform's autofill manager is
 * shadowed to record what it is asked, in order; a commit after the cancel finds no session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [KeyAutofillTest.RecordingAutofillManager::class])
class KeyAutofillTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    /** The platform's autofill manager, recording each commit and cancel it is asked for, in order. */
    @Implements(AutofillManager::class)
    class RecordingAutofillManager : ShadowAutofillManager() {
        @Implementation
        fun commit() {
            calls += COMMIT
        }

        @Implementation
        fun cancel() {
            calls += CANCEL
        }

        companion object {
            const val COMMIT = "commit"
            const val CANCEL = "cancel"
            val calls = mutableListOf<String>()
        }
    }

    private val calls get() = RecordingAutofillManager.calls

    @Before
    fun forget() = calls.clear()

    /** The session ended unsaved: cancelled, and not committed before that. */
    private fun assertEndedUnsaved() =
        assertEquals("cancelled before any commit", RecordingAutofillManager.CANCEL, calls.firstOrNull())

    /** What the dialog was asked to do, and the switch that takes it away without closing it. */
    private class Shown {
        var open by mutableStateOf(true)
        val saved = mutableListOf<String>()
        var removed = 0
        var dismissed = 0
    }

    private fun show(
        id: SecretId = SecretId.ANTHROPIC,
        state: SecretState = SecretState.ABSENT,
        saving: SaveResult = SaveResult.SAVED,
        block: (Shown) -> Unit,
    ) {
        val shown = Shown()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (shown.open) {
                        KeyDialog(
                            id = id,
                            state = state,
                            onSave = {
                                shown.saved += it
                                saving
                            },
                            onRemove = {
                                shown.removed++
                                true
                            },
                            scanner = { _, _ -> },
                            keyboard = { _, _, _ -> },
                            onDismiss = {
                                shown.dismissed++
                                shown.open = false
                            },
                        )
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_key_field))).fetchSemanticsNodes().isNotEmpty()
            }
            block(shown)
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    /** Fills the field with [key], as a password manager's chosen entry does. */
    private fun fill(key: String) =
        field().performSemanticsAction(SemanticsActions.OnFillData) {
            it(checkNotNull(FillableData.createFromAutofillValue(AutofillValue.forText(key))))
        }

    // Compose also infers the content type from `KeyboardType.Password`; the field marks it itself,
    // so this holds whatever the keyboard type becomes.
    @Test
    fun everyKeyFieldIsAMaskedPasswordThatAPasswordManagerFills() {
        for (id in SecretId.entries) {
            show(id) { shown ->
                val config = field().fetchSemanticsNode().config
                assertEquals(
                    "$id is a password to the autofill service",
                    ContentType.Password,
                    config.getOrNull(SemanticsProperties.ContentType),
                )
                assertTrue("$id stays masked", SemanticsProperties.Password in config)

                fill(" filled-key ")
                compose.onNodeWithText(text(R.string.settings_save)).assertIsEnabled().performClick()
                compose.waitUntil(timeoutMillis = 5_000) { shown.saved.isNotEmpty() }
                assertEquals("$id saves what was filled", listOf("filled-key"), shown.saved)
            }
        }
    }

    @Test
    fun cancellingEndsTheAutofillSessionUnsaved() =
        show { shown ->
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals(1, shown.dismissed)
            assertEndedUnsaved()
        }

    @Test
    fun aSavedKeyEndsTheAutofillSessionUnsaved() =
        show { shown ->
            fill("filled-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { shown.dismissed == 1 }
            compose.waitForIdle()
            assertEndedUnsaved()
        }

    @Test
    fun aRemovedKeyEndsTheAutofillSessionUnsaved() =
        show(state = SecretState.SAVED) { shown ->
            compose.onNodeWithText(text(R.string.settings_remove)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { shown.dismissed == 1 }
            compose.waitForIdle()
            assertEquals(1, shown.removed)
            assertEndedUnsaved()
        }

    @Test
    fun aKeyThatCannotBeSavedKeepsTheDialogAndItsAutofillSession() =
        show(saving = SaveResult.NOT_WRITTEN) { shown ->
            fill("filled-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_key_write_failed))).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, shown.dismissed)
            assertEquals("the session goes on while the dialog stays", emptyList<String>(), calls)
        }

    @Test
    fun aDialogThatGoesWithoutClosingItselfAlsoEndsTheAutofillSessionUnsaved() =
        show { shown ->
            compose.runOnIdle { shown.open = false }
            compose.waitForIdle()
            assertEquals("not closed by the dialog", 0, shown.dismissed)
            assertEndedUnsaved()
        }
}
