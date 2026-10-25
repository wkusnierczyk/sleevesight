package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The key field's icons (#432): *Scan QR code* and *Type on a computer* in the field, in place of
 * the links under it, and the eye, there only while the field holds something, which shows and
 * hides it; the field is masked again when it empties, the dialog closes or the app is left. The dialog takes the
 * screen's width, less a margin, up to Material's widest dialog.
 */
@RunWith(RobolectricTestRunner::class)
class KeyFieldIconsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val show get() = text(R.string.settings_key_show)
    private val hide get() = text(R.string.settings_key_hide)
    private val scan get() = text(R.string.settings_key_scan)
    private val type get() = text(R.string.settings_key_type)

    /** Whether the dialog is open, and the activity it is in. */
    private class Shown(
        val scenario: ActivityScenario<MainActivity>,
    ) {
        var open by mutableStateOf(true)
    }

    /**
     * Shows the dialog, whose scanner reads [code] at once, whose saves end as [saving] says, and
     * whose computer types [typed] as soon as a keyboard session starts.
     */
    private fun show(
        code: String = NEITHER,
        saving: CompletableDeferred<SaveResult> = CompletableDeferred(SaveResult.SAVED),
        typed: String = "",
        block: (Shown) -> Unit,
    ) {
        val scanner: QrScanner = { onScanned, _ -> LaunchedEffect(Unit) { onScanned(code) } }
        val keyboard: ComputerKeyboard = { session, onText, _ -> LaunchedEffect(session) { onText(typed) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val shown = Shown(scenario)
            scenario.onActivity { activity ->
                activity.setContent {
                    if (shown.open) {
                        KeyDialog(
                            id = SecretId.ANTHROPIC,
                            state = SecretState.ABSENT,
                            onSave = { saving.await() },
                            onRemove = { true },
                            scanner = scanner,
                            keyboard = keyboard,
                            onDismiss = { shown.open = false },
                        )
                    }
                }
            }
            waitFor(hasText(text(R.string.settings_key_field)))
            block(shown)
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun field() = compose.onNode(hasSetTextAction())

    /** Whether the field masks what it holds: shows it as dots, nothing else. */
    private fun masked() = showing().let { !it.isNullOrEmpty() && it.all { c -> c == '\u2022' } }

    /** What the field shows: its text, or the mask's dots. */
    private fun showing() =
        field()
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text

    @Test
    fun theTwoLinksAreIconsInTheFieldNowAndNoLongerText() =
        show {
            assertEquals("no Scan QR code link", 0, count(hasText(scan)))
            assertEquals("no Type on a computer link", 0, count(hasText(type)))
            for (name in listOf(scan, type)) {
                compose
                    .onNodeWithContentDescription(name)
                    .assert(hasClickAction())
                    .assertIsEnabled()
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
            }
        }

    @Test
    fun theQrIconScansForTheKeyAndTheKeyboardIconForAKeyboardCode() =
        show {
            compose.onNodeWithContentDescription(scan).performClick()
            waitFor(hasText(text(R.string.settings_key_scan_characters)))
            compose.onNodeWithContentDescription(type).performClick()
            waitFor(hasText(text(R.string.keyboard_not_code)))
        }

    @Test
    fun eachIconShowsItsNameAsATooltipOnALongPress() =
        show {
            compose.onNodeWithContentDescription(scan).performTouchInput { longClick() }
            waitFor(hasText(scan))
        }

    @Test
    fun theEyeIsAbsentWhileTheFieldIsEmptyAndThereOnceSomethingIsEntered() =
        show {
            assertEquals(0, count(hasContentDescription(show)))
            field().performTextInput("sk-ant-typed")
            compose
                .onNodeWithContentDescription(show)
                .assert(hasClickAction())
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            field().performTextClearance()
            compose.waitForIdle()
            assertEquals(0, count(hasContentDescription(show)))
        }

    @Test
    fun theEyeShowsWhatTheFieldHoldsAndHidesItAgain() =
        show {
            field().performTextInput("sk-ant-typed")
            assertTrue("masked at first", masked())
            assertFalse(showing() == "sk-ant-typed")

            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertFalse("shown", masked())
            assertEquals("sk-ant-typed", showing())
            // The icon now names what a tap does next.
            assertEquals(0, count(hasContentDescription(show)))

            compose.onNodeWithContentDescription(hide).performClick()
            compose.waitForIdle()
            assertTrue("masked again", masked())
            compose.onNodeWithContentDescription(show)
        }

    @Test
    fun theFieldIsMaskedAgainWhenTheDialogClosesAndOpensAgain() =
        show { shown ->
            field().performTextInput("sk-ant-typed")
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertFalse(masked())

            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { !shown.open }
            compose.runOnIdle { shown.open = true }
            waitFor(hasText(text(R.string.settings_key_field)))
            // Opened again: empty, with no eye, and masked once something is entered.
            assertEquals(0, count(hasContentDescription(show)) + count(hasContentDescription(hide)))
            field().performTextInput("sk-ant-again")
            compose.waitForIdle()
            assertTrue(masked())
            compose.onNodeWithContentDescription(show)
        }

    @Test
    fun aFieldEmptiedWhileShownIsMaskedWhenSomethingIsEnteredAgain() =
        show {
            field().performTextInput("sk-ant-typed")
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertFalse(masked())
            field().performTextClearance()
            field().performTextInput("sk-ant-other")
            compose.waitForIdle()
            assertTrue(masked())
        }

    @Test
    fun theFieldIsMaskedAgainWhenTheAppIsLeftAndComeBackTo() =
        show { shown ->
            field().performTextInput("sk-ant-typed")
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            assertFalse(masked())

            // Another app in front, or the screen locked, then back.
            shown.scenario.moveToState(Lifecycle.State.CREATED)
            shown.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertTrue("masked on return", masked())
            compose.onNodeWithContentDescription(show).assertIsEnabled()
            assertEquals("what was typed is kept", 1, count(hasContentDescription(show)))
        }

    @Test
    fun whileASaveRunsNoIconIsOnAndAllAreOnAgainWhenItFails() {
        val saving = CompletableDeferred<SaveResult>()
        show(saving = saving) {
            field().performTextInput("sk-ant-typed")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitForIdle()
            for (name in listOf(show, scan, type)) compose.onNodeWithContentDescription(name).assertIsNotEnabled()

            saving.complete(SaveResult.NOT_WRITTEN)
            waitFor(hasText(text(R.string.settings_key_write_failed)))
            for (name in listOf(show, scan, type)) compose.onNodeWithContentDescription(name).assertIsEnabled()
        }
    }

    @Test
    fun duringAKeyboardSessionTheEyeStaysOnToCheckWhatArrivesAndTheScansAreOff() =
        show(code = KeyboardVectors.freshCode(), typed = "sk-ant-from-the-computer") {
            compose.onNodeWithContentDescription(type).performClick()
            waitFor(hasContentDescription(show))
            compose.onNodeWithContentDescription(scan).assertIsNotEnabled()
            compose.onNodeWithContentDescription(type).assertIsNotEnabled()
            compose.onNodeWithContentDescription(show).assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals("sk-ant-from-the-computer", showing())
        }

    // Compose marks the field a password from its keyboard type, which stays `Password` while the
    // key is shown so that the keyboard neither learns nor suggests it.
    @Test
    fun shownTheKeyStaysAPasswordToAutofillAndToAccessibilityServices() =
        show {
            field().performTextInput("sk-ant-typed")
            compose.onNodeWithContentDescription(show).performClick()
            compose.waitForIdle()
            val config = field().fetchSemanticsNode().config
            assertEquals("sk-ant-typed", showing())
            assertTrue("a password to accessibility services", SemanticsProperties.Password in config)
            val type = config.getOrNull(SemanticsProperties.ContentType)
            assertEquals("a password to autofill", ContentType.Password, type)
        }

    // The field spans the dialog, whose text has Material's 24 dp padding at each side.

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneTheDialogTakesTheScreensWidthLessAMargin() =
        show { field().assertWidthIsEqualTo((411 - 2 * 16 - 2 * 24).dp) }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun onASmallPhoneTooTheDialogTakesTheScreensWidthLessAMargin() =
        show { field().assertWidthIsEqualTo((360 - 2 * 16 - 2 * 24).dp) }

    @Test
    @Config(qualifiers = "w592dp-h900dp")
    fun at592DpTheTwoRulesMeet() = show { field().assertWidthIsEqualTo((560 - 2 * 24).dp) }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun onAWiderScreenTheDialogKeepsMaterialsWidestDialogWidth() =
        show { field().assertWidthIsEqualTo((560 - 2 * 24).dp) }

    private companion object {
        /** A QR code's text that is neither a key (it holds a space) nor a keyboard session's code. */
        const val NEITHER = "not a key"
    }
}
