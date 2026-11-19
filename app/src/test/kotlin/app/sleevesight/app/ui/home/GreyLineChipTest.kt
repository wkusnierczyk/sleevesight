package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.SleevesightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chip of a line that does not back its sentence (#1458), light and dark: a line's chip (its
 * size and weight), filled with the `?` chip's grey and its text dark grey ([CiteStyles.unsupported]
 * as [CiteStyles.unbacked]), and the same in the note under the text, which still opens the line.
 */
@RunWith(RobolectricTestRunner::class)
class GreyLineChipTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        dark: Boolean,
        opened: MutableList<String> = mutableListOf(),
        block: (CiteStyles, ColorScheme) -> Unit,
    ) {
        var styles: CiteStyles? = null
        var colors: ColorScheme? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        styles = citeStyles()
                        colors = MaterialTheme.colorScheme
                        // Within a reply's note focus, as under an insight, where the note has its tag.
                        WithUnbackedNote {
                            UnbackedNote(Unbacked(listOf("W1"), uncited = false), onCite = { id -> opened += id })
                        }
                    }
                }
            }
            compose.waitForIdle()
            block(checkNotNull(styles), checkNotNull(colors))
        }
    }

    private fun theChipIsGrey(dark: Boolean) =
        show(dark) { styles, colors ->
            val chip = styles.unsupported
            assertEquals(styles.unbacked, chip)
            assertEquals(colors.onSurface.copy(alpha = UNBACKED_GREY), chip.background)
            assertEquals(colors.onSurfaceVariant, chip.color)
            assertEquals(styles.line.fontWeight to styles.line.fontSize, chip.fontWeight to chip.fontSize)
        }

    @Test
    fun theChipIsGreyInTheLightTheme() = theChipIsGrey(dark = false)

    @Test
    fun theChipIsGreyInTheDarkTheme() = theChipIsGrey(dark = true)

    private fun theNotesChipIsGreyToo(dark: Boolean) {
        val opened = mutableListOf<String>()
        show(dark, opened) { styles, _ ->
            val text =
                compose
                    .onNodeWithTag(UNBACKED_NOTE_TAG)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            val link =
                text
                    .getLinkAnnotations(0, text.length)
                    .map { it.item }
                    .filterIsInstance<LinkAnnotation.Clickable>()
                    .single { it.tag == CITE_PREFIX + "W1" }
            assertEquals(styles.unsupported, link.styles?.style)
            link.linkInteractionListener?.onClick(link)
            assertEquals(listOf("W1"), opened)
        }
    }

    @Test
    fun theNotesChipIsGreyTooInTheLightTheme() = theNotesChipIsGreyToo(dark = false)

    @Test
    fun theNotesChipIsGreyTooInTheDarkTheme() = theNotesChipIsGreyToo(dark = true)
}
