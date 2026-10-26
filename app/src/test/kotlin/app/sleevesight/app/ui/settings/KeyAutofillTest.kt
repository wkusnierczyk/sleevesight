package app.sleevesight.app.ui.settings

import android.graphics.Rect
import android.view.View
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
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
 * shadowed to record what it is asked, in order, with the dialog's own closing in the same record;
 * a commit after the cancel finds no session. The shadow keeps no session of its own: that Compose
 * tracks the field at all shows in the commit it sends when the field goes (which the dialog's
 * code before #376 let through first) and in the entry it reports when the field is focused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [KeyAutofillTest.RecordingAutofillManager::class])
class KeyAutofillTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    /** The platform's autofill manager, recording each entry, commit and cancel it is told of, in order. */
    @Implements(AutofillManager::class)
    class RecordingAutofillManager : ShadowAutofillManager() {
        @Implementation
        fun notifyViewEntered(
            @Suppress("UNUSED_PARAMETER") view: View,
            @Suppress("UNUSED_PARAMETER") virtualId: Int,
            @Suppress("UNUSED_PARAMETER") absBounds: Rect,
        ) {
            calls += ENTERED
        }

        @Implementation
        fun commit() {
            calls += COMMIT
        }

        @Implementation
        fun cancel() {
            calls += CANCEL
        }

        companion object {
            const val ENTERED = "entered"
            const val COMMIT = "commit"
            const val CANCEL = "cancel"

            /** Not the autofill manager's: the dialog's own [onDismiss][KeyDialog], recorded by the test. */
            const val DISMISSED = "dismissed"
            val calls = mutableListOf<String>()
        }
    }

    private val calls get() = RecordingAutofillManager.calls

    @Before
    fun forget() = calls.clear()

    /** What the autofill manager was told, without the field's entries, which come as focus moves. */
    private fun told() = calls.filter { it != RecordingAutofillManager.ENTERED }

    /** The session ended unsaved: cancelled, and not committed before that. */
    private fun assertEndedUnsaved() =
        assertEquals("cancelled before any commit", RecordingAutofillManager.CANCEL, told().firstOrNull())

    /** The dialog closed itself, and cancelled the session first, before it said it was done. */
    private fun assertCancelledThenClosed() =
        assertEquals(
            "cancelled, then dismissed",
            listOf(RecordingAutofillManager.CANCEL, RecordingAutofillManager.DISMISSED),
            told().take(2),
        )

    /** What the dialog was asked to do, and the switch that takes it away without closing it. */
    private class Shown {
        var open by mutableStateOf(true)
        val saved = mutableListOf<String>()
        var removed = 0
        val dismissed get() = RecordingAutofillManager.calls.count { it == RecordingAutofillManager.DISMISSED }
    }

    private fun show(
        id: SecretId = SecretId.ANTHROPIC,
        state: SecretState = SecretState.ABSENT,
        saving: SaveResult = SaveResult.SAVED,
        block: ActivityScenario<MainActivity>.(Shown) -> Unit,
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
                            scanner = { },
                            keyboard = { _, _, _, _ -> },
                            showPhoneName = false,
                            onDismiss = {
                                calls += RecordingAutofillManager.DISMISSED
                                shown.open = false
                            },
                        )
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_key_field))).fetchSemanticsNodes().isNotEmpty()
            }
            scenario.block(shown)
        }
    }

    private fun field() = compose.onNode(hasSetTextAction())

    /** Fills the field with [key], as a password manager's chosen entry does. */
    private fun fill(key: String) =
        field().performSemanticsAction(SemanticsActions.OnFillData) {
            it(checkNotNull(FillableData.createFromAutofillValue(AutofillValue.forText(key))))
        }

    // Compose also infers the content type from `KeyboardType.Password`, and the field marks it
    // itself, so this holds as long as either the mark or the keyboard type stays.
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
            assertCancelledThenClosed()
        }

    @Test
    fun aSavedKeyEndsTheAutofillSessionUnsaved() =
        show { shown ->
            fill("filled-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { shown.dismissed == 1 }
            compose.waitForIdle()
            assertCancelledThenClosed()
        }

    @Test
    fun aRemovedKeyEndsTheAutofillSessionUnsaved() =
        show(state = SecretState.SAVED) { shown ->
            compose.onNodeWithText(text(R.string.settings_remove)).performClick()
            // *Remove* asks first (#543): the confirmation's own *Remove* removes the key.
            val title = app.getString(R.string.settings_key_remove_title, text(R.string.settings_key_name_anthropic))
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNode(hasText(text(R.string.settings_remove)) and hasAnyAncestor(hasAnyDescendant(hasText(title))))
                .performClick()
            compose.waitUntil(timeoutMillis = 5_000) { shown.dismissed == 1 }
            compose.waitForIdle()
            assertEquals(1, shown.removed)
            assertCancelledThenClosed()
        }

    @Test
    fun aKeyThatCannotBeSavedKeepsTheDialogAndItsAutofillSession() =
        show(saving = SaveResult.NOT_WRITTEN) { shown ->
            field().performClick()
            compose.waitForIdle()
            assertTrue("Compose tracks the field for autofill", RecordingAutofillManager.ENTERED in calls)
            fill("filled-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodes(hasText(text(R.string.settings_key_write_failed))).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, shown.dismissed)
            assertEquals("the session goes on while the dialog stays", emptyList<String>(), told())
        }

    @Test
    fun aDialogThatGoesWithoutClosingItselfAlsoEndsTheAutofillSessionUnsaved() =
        show { shown ->
            compose.runOnIdle { shown.open = false }
            compose.waitForIdle()
            assertEquals("not closed by the dialog", 0, shown.dismissed)
            assertEndedUnsaved()
        }

    @Test
    fun aDialogThatGoesWithTheActivityAlsoEndsTheAutofillSessionUnsaved() =
        show {
            fill("filled-key")
            compose.waitForIdle()
            // As a rotation does: the dialog's window composition goes first, then the activity's.
            recreate()
            compose.waitForIdle()
            assertEndedUnsaved()
        }
}
