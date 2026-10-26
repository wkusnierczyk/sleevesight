package app.sleevesight.app.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
 * start, and *Remove*, while a key is saved, and *Save* at the end, in every key's dialog; *Remove*
 * in the error colour, named for the key it removes. Drawn with native graphics on a phone's width
 * and density, to see the colour.
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

    /** The dialog of the key [id], saved or not as [state] says, [dark] or not; [block] hears the error colour. */
    private fun show(
        id: SecretId,
        state: SecretState = SecretState.SAVED,
        dark: Boolean = false,
        block: (Color) -> Unit = {},
    ) {
        val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned("") } }
        val keyboard: ComputerKeyboard = { _, _, _, _ -> }
        var error = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        error = MaterialTheme.colorScheme.error
                        KeyDialog(
                            id = id,
                            state = state,
                            onSave = { SaveResult.SAVED },
                            onRemove = { true },
                            scanner = scanner,
                            keyboard = keyboard,
                            showPhoneName = false,
                            onDismiss = {},
                        )
                    }
                }
            }
            waitFor(hasText(text(R.string.settings_key_field)))
            block(error)
        }
    }

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
                val dialog = bounds(isDialog())
                val (c, r, s) = listOf(cancel, remove, save).map(::bounds)
                assertTrue("$id: Cancel before Remove", c.right <= r.left)
                assertTrue("$id: Remove before Save", r.right <= s.left)
                assertTrue("$id: Cancel at the start", c.left - dialog.left < r.left - c.right)
                assertTrue("$id: Save at the end", dialog.right - s.right < r.left - c.right)
                assertEquals("$id: one line", c.center.y, s.center.y, 1f)
            }
        }
    }

    @Test
    fun withNoKeySavedThereIsNoRemoveAndCancelStaysAtTheStart() =
        show(SecretId.LASTFM, SecretState.ABSENT) {
            assertEquals(0, count(remove))
            val (c, s) = listOf(cancel, save).map(::bounds)
            val dialog = bounds(isDialog())
            assertTrue("Cancel at the start", c.left - dialog.left < s.left - c.right)
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
            show(SecretId.EXA, dark = dark) { error ->
                val removing = compose.onNode(remove).captureToImage().asAndroidBitmap()
                val saving = compose.onNode(save).captureToImage().asAndroidBitmap()
                assertTrue("dark=$dark: Remove in $error", share(removing, error) > MARKED)
                assertEquals("dark=$dark: Save not", 0f, share(saving, error), 0f)
            }
        }
    }

    @Test
    fun atTwiceTheFontOnANarrowPhoneTheButtonsWrapInOrderInsideTheDialog() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp")
        RuntimeEnvironment.setFontScale(2f)
        show(SecretId.ANTHROPIC) {
            val dialog = bounds(isDialog())
            val (c, r, s) = listOf(cancel, remove, save).map(::bounds)
            for (b in listOf(c, r, s)) assertTrue("$b inside $dialog", b.left >= dialog.left && b.right <= dialog.right)
            assertTrue("Cancel first", c.right <= r.left || c.bottom <= r.top)
            assertTrue("Remove before Save", r.right <= s.left || r.bottom <= s.top)
            assertTrue("each button whole", listOf(c, r, s).all { it.width > 0f && it.height > 0f })
            // Remove's text fits on one line: it is no taller than Save.
            assertEquals(s.height, r.height, 1f)
        }
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
    }
}
