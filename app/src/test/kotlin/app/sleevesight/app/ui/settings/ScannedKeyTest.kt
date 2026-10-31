package app.sleevesight.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.keyboard.KeyboardVectors
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.scan.QrScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A key read from a QR code is never shown (#702), since keys are lent as a setup code: in a key's
 * dialog, a key scanned, a plain key's or a setup code's, has no eye, and nothing that reads the
 * field gets it: not its undo history, a copy, an autofill request or an accessibility service.
 * Any edit replaces it whole, and what is typed then can be shown, as can text typed on a computer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ScannedKeyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val show get() = text(R.string.settings_key_show)
    private val hide get() = text(R.string.settings_key_hide)
    private val scan get() = text(R.string.settings_key_scan)
    private val type get() = text(R.string.settings_key_type)

    /**
     * Shows Anthropic's key dialog, whose scanner reads each of [codes] in turn and then [code]
     * again, whose saves succeed, and whose computer types [typed] as soon as a session starts.
     */
    private fun show(
        codes: List<String> = listOf(KEY),
        code: String = NEITHER,
        typed: String = "",
        block: () -> Unit,
    ) {
        val next = ArrayDeque(codes)
        val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned(next.removeFirstOrNull() ?: code) } }
        val keyboard: ComputerKeyboard = { session, _, onText, _ -> LaunchedEffect(session) { onText(typed) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KeyDialog(
                        id = SecretId.ANTHROPIC,
                        state = SecretState.ABSENT,
                        onSave = { SaveResult.SAVED },
                        onRemove = { true },
                        scanner = scanner,
                        keyboard = keyboard,
                        showPhoneName = false,
                        onDismiss = {},
                    )
                }
            }
            waitFor(hasText(text(R.string.settings_key_field)))
            block()
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun field() = compose.onNode(hasSetTextAction())

    private fun config() = field().fetchSemanticsNode().config

    /** What the field shows: its text, or the mask's dots. */
    private fun showing() = config().getOrNull(SemanticsProperties.EditableText)?.text

    /** Whether the field masks what it holds: shows it as dots, nothing else. */
    private fun masked() = showing().let { !it.isNullOrEmpty() && it.all { c -> c == '•' } }

    /** Whether the field has no eye: neither *Show key* nor *Hide key*. */
    private fun noEye() = count(hasContentDescription(show)) + count(hasContentDescription(hide)) == 0

    /** Scans with the QR icon, and waits until the field holds as many characters as [KEY]. */
    private fun scanKey() {
        compose.onNodeWithContentDescription(scan).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { showing()?.length == KEY.length }
    }

    /** Keys pressed on a hardware keyboard, or sent by the keyboard app, into the field. */
    private fun keys(block: KeyInjectionScope.() -> Unit) {
        field().requestFocus()
        field().performKeyInput(block)
        compose.waitForIdle()
    }

    /**
     * Asserts that nothing reading the field gets [KEY]: not what it holds, which the keyboard app,
     * the undo history and autofill read, nor what accessibility services read, nor the eye.
     */
    private fun keyNowhere() {
        val config = config()
        val held = config.getOrNull(SemanticsProperties.InputText)?.text.orEmpty()
        val fillable = config.getOrNull(SemanticsProperties.FillableData)?.textValue
        val filled = fillable?.toString().orEmpty()
        assertFalse("not in the field's text: $held", KEY in held)
        assertFalse("not in what autofill gets: $filled", KEY in filled)
        assertFalse("not read out", KEY in showing().orEmpty())
        if (count(hasContentDescription(show)) > 0) {
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertFalse("not shown", KEY in showing().orEmpty())
        }
    }

    @Test
    fun aPlainKeyScannedIntoTheFieldStaysMaskedWithNoEye() =
        show {
            scanKey()
            assertTrue("masked", masked())
            assertTrue("no eye", noEye())
            keyNowhere()
        }

    @Test
    fun aSetupCodesKeyScannedIntoTheFieldStaysMaskedWithNoEye() =
        show(codes = listOf("sleevesight-keys:v1\nanthropic=$KEY")) {
            scanKey()
            assertTrue("masked", masked())
            assertTrue("no eye", noEye())
            keyNowhere()
        }

    @Test
    fun aKeyScannedWhileATypedOneIsShownIsMaskedWithNoEye() =
        show {
            field().performTextInput("sk-ant-typed")
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertFalse(masked())

            scanKey()
            assertTrue("masked", masked())
            assertTrue("no eye", noEye())
        }

    @Test
    fun aFailedScanAfterAScannedKeyLeavesItMaskedWithNoEye() =
        show {
            scanKey()
            compose.onNodeWithContentDescription(scan).performClick()
            waitFor(hasText(text(R.string.settings_key_scan_characters)))
            assertTrue("masked", masked())
            assertTrue("no eye", noEye())
            keyNowhere()
        }

    @Test
    fun anEditReplacesAScannedKeyWholeAndWhatIsTypedCanBeShown() =
        show {
            scanKey()
            field().performTextInput("x")
            compose.waitForIdle()
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertEquals("x", showing())
        }

    @Test
    fun aKeyTypedOnceTheScannedOneIsClearedHasTheEyeAgain() =
        show {
            scanKey()
            field().performTextClearance()
            field().performTextInput("sk-ant-typed")
            compose.waitForIdle()
            assertTrue(masked())
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertEquals("sk-ant-typed", showing())
        }

    // An undo or a redo brings back only what the field held, which is never the key (`KeyFieldTest`
    // checks what they bring back). Robolectric cannot press Ctrl+Z here: it types a "z".
    @Test
    fun aScannedKeyClearedOrReplacedByTextTypedOnAComputerWasNeverInTheField() =
        show(
            codes = listOf(KEY, KEY),
            code = KeyboardVectors.freshCode(),
            typed = "sk-ant-from-the-computer",
        ) {
            scanKey()
            keyNowhere()
            field().performTextClearance()
            compose.waitForIdle()
            keyNowhere()

            scanKey()
            compose.onNodeWithContentDescription(type).performClick()
            waitFor(hasContentDescription(show))
            keyNowhere()
        }

    @Test
    fun aKeyTypedOnAComputerAfterAScannedOneCanBeShown() =
        show(code = KeyboardVectors.freshCode(), typed = "sk-ant-from-the-computer") {
            scanKey()
            assertTrue(noEye())

            compose.onNodeWithContentDescription(type).performClick()
            waitFor(hasContentDescription(show))
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertEquals("sk-ant-from-the-computer", showing())
        }

    // The keyboard app's copy and cut come to the field as the Copy and Cut keys, as do a hardware
    // keyboard's Ctrl+C and Ctrl+X, which Robolectric cannot press (it types the letter). This guards
    // the password field's own block on copy and cut, not #702's fix, which keeps the key out of the
    // field: the `keyNowhere` checks of `InputText` and `FillableData`, and `KeyFieldTest`, guard that.
    @Test
    fun aScannedKeyCannotBeCopiedOrCut() =
        show {
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("before", "before"))
            scanKey()
            field().performTextInputSelection(TextRange(0, KEY.length))
            keys { pressKey(Key.Copy) }
            keys { pressKey(Key.Cut) }
            val clip = clipboard.primaryClip?.getItemAt(0)
            assertEquals("before", clip?.text?.toString())
            assertTrue("not cut", masked())
        }

    @Test
    fun savingAScannedKeySavesTheKeyItself() {
        var saved: String? = null
        val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned(KEY) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    KeyDialog(
                        id = SecretId.ANTHROPIC,
                        state = SecretState.ABSENT,
                        onSave = { key -> SaveResult.SAVED.also { saved = key } },
                        onRemove = { true },
                        scanner = scanner,
                        keyboard = { _, _, _, _ -> },
                        showPhoneName = false,
                        onDismiss = {},
                    )
                }
            }
            waitFor(hasText(text(R.string.settings_key_field)))
            scanKey()
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { saved != null }
            assertEquals(KEY, saved)
        }
    }

    private companion object {
        /** An Anthropic key's form, for the dialog's key, Anthropic's. */
        val KEY = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)

        /** A QR code's text that is neither a key (it holds a space) nor a keyboard session's code. */
        const val NEITHER = "not a key"
    }
}
