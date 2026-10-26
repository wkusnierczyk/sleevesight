package app.sleevesight.app.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The key dialog's terms and buttons (#543): an AI service's agreement in bold; *Cancel* at the
 * start, and *Remove*, while a key is saved, and *Save*, filled, at the end, in every key's dialog;
 * *Remove* in the error colour, named for the key it removes, and asking first, in a confirmation
 * laid out the same. Drawn with native graphics on a phone's width and density, to see the colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyDialogButtonsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val cancel get() = hasText(text(R.string.settings_cancel)) and hasClickAction()
    private val remove get() = hasText(text(R.string.settings_remove)) and hasClickAction()
    private val save get() = hasText(text(R.string.settings_save)) and hasClickAction()

    /** What the dialog heard, and the theme's colours it was drawn in. */
    private class Shown {
        var error = Color.Unspecified
        var primary = Color.Unspecified
        var removed = 0
        var dismissed by mutableIntStateOf(0)
    }

    /**
     * The dialog of the key [id], saved or not as [state] says, [dark] or not; [removes] is what
     * `onRemove` returns, whether a removal succeeds.
     */
    private fun show(
        id: SecretId,
        state: SecretState = SecretState.SAVED,
        dark: Boolean = false,
        removes: Boolean = true,
        block: (Shown) -> Unit = {},
    ) {
        val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned("") } }
        val keyboard: ComputerKeyboard = { _, _, _, _ -> }
        val shown = Shown()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        shown.error = MaterialTheme.colorScheme.error
                        shown.primary = MaterialTheme.colorScheme.primary
                        if (shown.dismissed == 0) {
                            KeyDialog(
                                id = id,
                                state = state,
                                onSave = { SaveResult.SAVED },
                                onRemove = {
                                    shown.removed++
                                    removes
                                },
                                scanner = scanner,
                                keyboard = keyboard,
                                showPhoneName = false,
                                onDismiss = { shown.dismissed++ },
                            )
                        }
                    }
                }
            }
            waitFor(hasText(text(R.string.settings_key_field)))
            block(shown)
        }
    }

    /** The confirmation's title, for the key [id]. */
    private fun confirmTitle(id: SecretId) = app.getString(R.string.settings_key_remove_title, text(keyName(id)))

    /** A node of the confirmation of the key [id]'s removal. */
    private fun inConfirmation(id: SecretId) = hasAnyAncestor(hasAnyDescendant(hasText(confirmTitle(id))))

    /** A node of the key dialog, not of the confirmation over it. */
    private val inKeyDialog get() = hasAnyAncestor(hasAnyDescendant(hasSetTextAction()))

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun count(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).getBoundsInRoot().let(::px)

    private fun px(bounds: DpRect) = Rect(bounds.left.value, bounds.top.value, bounds.right.value, bounds.bottom.value)

    /** The text a node with [plain] for its text shows, with its styles. */
    private fun styled(plain: String): AnnotatedString =
        compose
            .onNode(hasText(plain))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .single()

    @Test
    fun eachAiServicesAgreementIsInBoldAndTheRestOfItsTermsPlain() {
        val services =
            mapOf(
                SecretId.ANTHROPIC to R.string.settings_terms_anthropic,
                SecretId.OPENAI to R.string.settings_terms_openai,
                SecretId.GOOGLE to R.string.settings_terms_google,
            )
        for ((id, note) in services) {
            show(id) {
                val plain = text(note)
                val agreement = plain.substring(0, plain.indexOf(". ") + 1)
                assertTrue(agreement, agreement.startsWith("Using this key means agreeing to "))
                val bold = styled(plain).spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
                val spans = bold.map { it.start to it.end }
                assertEquals("$id: the agreement alone in bold", listOf(0 to agreement.length), spans)
            }
        }
    }

    @Test
    fun everyKeysDialogHasCancelAtTheStartThenRemoveAndSaveAtTheEnd() {
        for (id in SHOWN_KEYS) {
            show(id) {
                val field = bounds(hasSetTextAction())
                val (c, r, s) = listOf(cancel, remove, save).map(::bounds)
                assertTrue("$id: Cancel before Remove", c.right <= r.left)
                assertTrue("$id: Remove before Save", r.right <= s.left)
                assertEquals("$id: Cancel at the start", field.left, c.left, 1f)
                assertEquals("$id: Save at the end", field.right, s.right, 1f)
                assertEquals("$id: one line", c.center.y, s.center.y, 1f)
            }
        }
    }

    @Test
    fun withNoKeySavedThereIsNoRemoveAndCancelStaysAtTheStart() =
        show(SecretId.LASTFM, SecretState.ABSENT) {
            assertEquals(0, count(remove))
            val field = bounds(hasSetTextAction())
            val (c, s) = listOf(cancel, save).map(::bounds)
            assertEquals("Cancel at the start", field.left, c.left, 1f)
            assertEquals("Save at the end", field.right, s.right, 1f)
        }

    @Test
    fun aScreenReaderNamesTheKeyRemoveRemoves() {
        for (id in SHOWN_KEYS) {
            show(id) {
                val named = app.getString(R.string.settings_key_remove, text(keyName(id)))
                compose.onNode(remove and hasContentDescription(named)).assertIsDisplayed()
            }
        }
    }

    @Test
    fun removeIsDrawnInTheErrorColourAndSaveIsNot() {
        for (dark in listOf(false, true)) {
            show(SecretId.EXA, dark = dark) { shown ->
                compose.onNode(hasSetTextAction()).performTextInput("0c6d1e2f-3a4b-5c6d")
                compose.waitForIdle()
                val removing = compose.onNode(remove).captureToImage().asAndroidBitmap()
                val saving = compose.onNode(save).captureToImage().asAndroidBitmap()
                assertTrue("dark=$dark: Remove in ${shown.error}", share(removing, shown.error) > MARKED)
                assertEquals("dark=$dark: Save not", 0f, share(saving, shown.error), 0f)
                // *Save*, the main action, is filled with the primary colour.
                assertTrue("dark=$dark: Save filled in ${shown.primary}", share(saving, shown.primary) > FILLED)
            }
        }
    }

    @Test
    fun atTwiceTheFontOnANarrowPhoneTheButtonsWrapInOrderInsideTheDialog() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp")
        RuntimeEnvironment.setFontScale(2f)
        show(SecretId.ANTHROPIC) {
            // The dialog's content, from its start, where the title starts, to its end, as far in
            // from the dialog's edge (the field, scrolled out of the short window, has no bounds).
            val dialog = bounds(isDialog())
            val title = bounds(hasText(text(keyName(SecretId.ANTHROPIC))))
            val field = Rect(title.left, title.top, dialog.right - (title.left - dialog.left), title.bottom)
            val (c, r, s) = listOf(cancel, remove, save).map(::bounds)
            assertTrue("the line breaks: Save below Cancel", s.top >= c.bottom)
            assertEquals("Cancel at the start", field.left, c.left, 1f)
            assertEquals("Save at the end of its line", field.right, s.right, 1f)
            if (r.top < s.top) {
                // Remove, on a line above Save's, ends it too, unless it shares Cancel's.
                assertTrue("Remove after Cancel", r.top >= c.bottom || r.left >= c.right)
                if (r.top >= c.bottom) assertEquals("Remove at the end of its line", field.right, r.right, 1f)
            } else {
                assertEquals("Remove beside Save", s.left - 8f, r.right, 1f)
            }
            val inside = listOf(c, r, s).all { it.left >= field.left - 1f && it.right <= field.right + 1f }
            assertTrue("each inside", inside)
            // Remove's text fits on one line: it is no taller than Save.
            assertEquals(s.height, r.height, 1f)
        }
    }

    @Test
    fun removeAsksFirstAndTheConfirmationRemovesTheKeyAndClosesBoth() =
        show(SecretId.LASTFM) { shown ->
            compose.onNode(remove and inKeyDialog).performClick()
            waitFor(hasText(confirmTitle(SecretId.LASTFM)))
            assertEquals("nothing removed before it is confirmed", 0, shown.removed)
            compose.onNode(remove and inConfirmation(SecretId.LASTFM)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { shown.dismissed == 1 }
            assertEquals(1, shown.removed)
            compose.waitForIdle()
            assertEquals("the confirmation gone", 0, count(hasText(confirmTitle(SecretId.LASTFM))))
            assertEquals("the key dialog gone", 0, count(hasSetTextAction()))
        }

    @Test
    fun cancellingTheConfirmationKeepsTheKeyAndTheKeyDialog() =
        show(SecretId.ANTHROPIC) { shown ->
            compose.onNode(remove and inKeyDialog).performClick()
            waitFor(hasText(confirmTitle(SecretId.ANTHROPIC)))
            compose.onNode(cancel and inConfirmation(SecretId.ANTHROPIC)).performClick()
            compose.waitForIdle()
            assertEquals(0, count(hasText(confirmTitle(SecretId.ANTHROPIC))))
            assertEquals("the key kept", 0, shown.removed)
            assertEquals("the key dialog still open", 0, shown.dismissed)
            compose.onNode(remove and inKeyDialog).assertIsDisplayed()
        }

    @Test
    fun theConfirmationsRemoveIsRedNamedAndAfterCancel() {
        for (dark in listOf(false, true)) {
            show(SecretId.EXA, dark = dark) { shown ->
                compose.onNode(remove and inKeyDialog).performClick()
                waitFor(hasText(confirmTitle(SecretId.EXA)))
                val confirming = remove and inConfirmation(SecretId.EXA)
                val named = app.getString(R.string.settings_key_remove, text(keyName(SecretId.EXA)))
                compose.onNode(confirming and hasContentDescription(named)).assertIsDisplayed()
                val picture = compose.onNode(confirming).captureToImage().asAndroidBitmap()
                assertTrue("dark=$dark: in ${shown.error}", share(picture, shown.error) > MARKED)
                // The content runs from where the title starts to as far in from the dialog's other edge.
                val dialog = bounds(isDialog() and hasAnyDescendant(hasText(confirmTitle(SecretId.EXA))))
                val title = bounds(hasText(confirmTitle(SecretId.EXA)))
                val c = bounds(cancel and inConfirmation(SecretId.EXA))
                val r = bounds(confirming)
                assertEquals("Cancel at the start", title.left, c.left, 1f)
                assertTrue("Remove after Cancel", r.left >= c.right)
                assertEquals("Remove at the end", dialog.right - (title.left - dialog.left), r.right, 1f)
            }
        }
    }

    @Test
    fun aRemovalThatFailsSaysSoInTheConfirmationAndCancelGoesBackToTheKeyDialog() =
        show(SecretId.LASTFM, removes = false) { shown ->
            compose.onNode(remove and inKeyDialog).performClick()
            waitFor(hasText(confirmTitle(SecretId.LASTFM)))
            compose.onNode(remove and inConfirmation(SecretId.LASTFM)).performClick()
            val failed = text(R.string.settings_key_remove_failed)
            waitFor(hasText(failed) and inConfirmation(SecretId.LASTFM))
            assertEquals(1, shown.removed)
            assertEquals("the key dialog stays", 0, shown.dismissed)
            compose.onNode(cancel and inConfirmation(SecretId.LASTFM)).performClick()
            compose.waitForIdle()
            assertEquals("the confirmation gone", 0, count(hasText(confirmTitle(SecretId.LASTFM))))
            assertEquals(0, shown.dismissed)
            compose.onNode(remove and inKeyDialog).assertIsDisplayed()
        }

    /** The share of [picture]'s pixels near [colour]. */
    private fun share(
        picture: Bitmap,
        colour: Color,
    ): Float {
        val target = colour.toArgb()
        var near = 0
        for (x in 0 until picture.width) {
            for (y in 0 until picture.height) {
                val p = picture.getPixel(x, y)
                val d =
                    listOf(16, 8, 0).sumOf { shift -> abs((p shr shift and 0xFF) - (target shr shift and 0xFF)) }
                if (d < NEAR) near++
            }
        }
        return near.toFloat() / (picture.width * picture.height)
    }

    private companion object {
        /** How far, summed over red, green and blue, a pixel may be from a colour and still be it. */
        const val NEAR = 40

        /** The share of a button's pixels in the error colour that marks it: its outline alone is more. */
        const val MARKED = 0.02f

        /** The share of a filled button's pixels in its fill, less its text and rounded corners. */
        const val FILLED = 0.5f
    }
}
