package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.scan.QrScanner
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
 * hides it; the field is masked again when it empties or the dialog closes. The dialog takes the
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

    /** Whether the dialog is open. */
    private class Shown {
        var open by mutableStateOf(true)
    }

    private fun show(block: (Shown) -> Unit) {
        val shown = Shown()
        // A scanner that reads, at once, a code that is neither a key nor a keyboard code.
        val scanner: QrScanner = { onScanned, _ -> LaunchedEffect(Unit) { onScanned(NEITHER) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (shown.open) {
                        KeyDialog(
                            id = SecretId.ANTHROPIC,
                            state = SecretState.ABSENT,
                            onSave = { SaveResult.SAVED },
                            onRemove = { true },
                            scanner = scanner,
                            keyboard = { _, _, _ -> },
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

    // The field spans the dialog, whose text has Material's 24 dp padding at each side.

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun onAPhoneTheDialogTakesTheScreensWidthLessAMargin() =
        show { field().assertWidthIsEqualTo((411 - 2 * 16 - 2 * 24).dp) }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun onAWiderScreenTheDialogKeepsMaterialsWidestDialogWidth() =
        show { field().assertWidthIsEqualTo((560 - 2 * 24).dp) }

    private companion object {
        /** A QR code's text that is neither a key (it holds a space) nor a keyboard session's code. */
        const val NEITHER = "not a key"
    }
}
