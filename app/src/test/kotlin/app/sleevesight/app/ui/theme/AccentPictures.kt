package app.sleevesight.app.ui.theme

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.core.settings.LinkStyle
import kotlin.math.abs

/**
 * Links, headings and switches as drawn (#442), for `LinkLookTest` and `PlainAccentsTest`: shown
 * on a surface of the app's theme, and read back as pixels and as the text's layout.
 */
internal class AccentPictures(
    private val compose: ComposeTestRule,
) {
    /**
     * [content] on a surface of the app's theme, light or [dark], its links marked as [linkStyle]
     * says (#519), and the scheme it was drawn in.
     */
    fun show(
        dark: Boolean,
        content: @Composable () -> Unit,
        linkStyle: LinkStyle = LinkStyle.UNDERLINE,
        check: (ColorScheme) -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        var scheme: ColorScheme? = null
        scenario.onActivity {
            it.setContent {
                SleevesightTheme(darkTheme = dark, linkStyle = linkStyle) {
                    scheme = MaterialTheme.colorScheme
                    Surface { content() }
                }
            }
        }
        compose.waitForIdle()
        check(checkNotNull(scheme))
    }

    /** The node tagged [tag], as drawn. */
    fun picture(tag: String): Bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    /** The style the text [text] is laid out in. */
    fun styleOf(text: String): TextStyle {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single().layoutInput.style
    }

    /** The layout of the text tagged [tag]. */
    fun layout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }
}

/** Whether [this] pixel is within [tolerance] of [color] in each channel. */
internal fun Int.near(
    color: Color,
    tolerance: Int = 8,
): Boolean {
    val c = color.toArgb()
    return listOf(16, 8, 0).all { shift -> abs((this shr shift and 0xFF) - (c shr shift and 0xFF)) <= tolerance }
}

/** Whether [this] pixel is nearer [ink] than [paper]: part of a glyph or a line, not the background. */
internal fun Int.inked(
    ink: Color,
    paper: Color,
): Boolean {
    fun distance(c: Color) =
        listOf(16, 8, 0).sumOf { shift -> abs((this shr shift and 0xFF) - (c.toArgb() shr shift and 0xFF)) }
    return distance(ink) < distance(paper)
}

internal fun Bitmap.row(y: Int): IntArray = IntArray(width).also { getPixels(it, 0, width, 0, y, width, 1) }

internal fun Bitmap.all(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
