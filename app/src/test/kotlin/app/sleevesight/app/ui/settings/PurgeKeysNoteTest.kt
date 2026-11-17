package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Purge keys* after a failure (#1365, #847): why to use it, "A saved key cannot be read, …", is a
 * note shown in full under the row's description, not a description folded to one line; the
 * description stays what *Purge keys* does. Without a failure there is no note. Native graphics, for
 * the text's measure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurgeKeysNoteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun show(
        keys: KeysShown,
        test: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SecretsSection(Settings(), keys, {}, {})
                        }
                    }
                }
            }
            compose.waitForIdle()
            test()
        }
    }

    @Test
    fun afterAFailureWhyIsANoteShownInFull() =
        show(KeysShown(mapOf(SecretId.EXA to SecretState.UNREADABLE), offerReset = true)) {
            val note = compose.onNodeWithTag(ROW_NOTE_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val text = note.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            assertEquals(app.getString(R.string.settings_reset_keys_value), text)
            val layouts = mutableListOf<TextLayoutResult>()
            note.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertTrue("not cut short", !layouts.single().hasVisualOverflow)
            assertTrue("on more than one line", layouts.single().lineCount > 1)
            compose.onNodeWithText(app.getString(R.string.settings_remove_all_keys_value)).assertExists()
        }

    @Test
    fun withoutAFailureThereIsNoNote() =
        show(KeysShown(mapOf(SecretId.EXA to SecretState.SAVED), offerReset = false)) {
            compose.onNodeWithText(app.getString(R.string.settings_remove_all_keys_value)).assertExists()
            assertTrue(compose.onAllNodesWithTag(ROW_NOTE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }
}
