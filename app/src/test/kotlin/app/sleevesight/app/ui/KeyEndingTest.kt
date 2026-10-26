package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.idleUntil
import app.sleevesight.app.secrets.ResetResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.settings.PurgeKeysScreen
import app.sleevesight.app.ui.settings.ShownKey
import app.sleevesight.app.ui.settings.endingOf
import app.sleevesight.app.ui.settings.keyLine
import app.sleevesight.app.ui.settings.shownKey
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A key's ending on the key screens (#542): its last four characters, with no ellipsis before
 * them, marked as code (monospace, in a blue of the theme's lightness), and read by a screen reader
 * character by character, on *Purge keys* and *Load keys* alike.
 */
@RunWith(RobolectricTestRunner::class)
class KeyEndingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val exa get() = app.getString(R.string.settings_key_name_exa)

    /** The text showing [text] in [content], with its spans and annotations. */
    private fun shown(
        text: String,
        content: @Composable () -> Unit,
    ): AnnotatedString =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent(content = content) }
            idleUntil("a node: $text") { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
            val node = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().single()
            node.config.getOrNull(SemanticsProperties.Text)!!.single { it.text == text }
        }

    /** The style [line] gives the ending "3e4f", after checking it is read verbatim and only it is styled. */
    private fun endingStyle(line: AnnotatedString): SpanStyle {
        val at = line.text.indexOf(ENDING)
        val ending = at until at + ENDING.length
        val tts = line.getTtsAnnotations(0, line.length).single()
        assertEquals(VerbatimTtsAnnotation(ENDING), tts.item)
        assertEquals(ending, tts.start until tts.end)
        val span = line.spanStyles.single()
        assertEquals(ending, span.start until span.end)
        return span.item
    }

    private fun listed() = app.getString(R.string.key_ending, exa, ENDING)

    @Test
    fun purgeKeysListsTheEndingAsCodeReadCharacterByCharacter() {
        val line =
            shown(listed()) {
                SleevesightTheme(darkTheme = false) {
                    PurgeKeysScreen(
                        secrets = mapOf(SecretId.EXA to SecretState.SAVED),
                        ending = { endingOf(exaKey) },
                        onPurge = { ResetResult(removed = true, sealDeleted = true, left = emptyMap()) },
                        onDismiss = {},
                    )
                }
            }
        // No ellipsis before the ending.
        assertTrue(line.text, line.text.endsWith(", ending $ENDING"))
        assertEquals(FontFamily.Monospace, endingStyle(line).fontFamily)
    }

    @Test
    fun loadKeysShowsTheEndingOfAKeyFoundAsCode() {
        val found = app.getString(R.string.setup_new, exa, ENDING)
        val line =
            shown(found) {
                SleevesightTheme(darkTheme = false) { Text(keyLine(R.string.setup_new, exa, endingOf(exaKey))) }
            }
        assertEquals(FontFamily.Monospace, endingStyle(line).fontFamily)
    }

    @Test
    fun theEndingIsInALighterBlueOnADarkThemeOnAFaintFillOfIt() {
        val styles =
            listOf(false, true).associateWith { dark ->
                val key = ShownKey(SecretId.EXA, endingOf(exaKey))
                endingStyle(shown(listed()) { SleevesightTheme(darkTheme = dark) { Text(shownKey(key)) } })
            }
        val light = styles.getValue(false).color
        val dark = styles.getValue(true).color
        for (blue in listOf(light, dark)) assertTrue("$blue", blue.blue > blue.red && blue.blue > blue.green)
        assertTrue("$light, $dark", luminance(dark) > luminance(light))
        for (style in styles.values) {
            assertEquals(style.color, style.background.copy(alpha = 1f))
            assertTrue("${style.background}", style.background.alpha < FAINT)
        }
    }

    private fun luminance(color: Color) = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    private companion object {
        /** [exaKey]'s last four characters. */
        const val ENDING = "3e4f"

        /** Below this alpha, a fill is faint. */
        const val FAINT = 0.5f
    }
}
