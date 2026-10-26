package app.sleevesight.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

// Code in a text (#542): characters to read as they are, such as a key's last four, marked as code.

/**
 * How code is marked in a text: monospace, in blue on a faint fill of the same blue, a darker blue
 * on a light theme and a lighter one on a dark theme ([isAppInDarkTheme]). The blue is the app's
 * own, not the theme's accent, which follows the wallpaper and need not be blue.
 */
@Composable
internal fun codeStyle(): SpanStyle {
    val ink = Color(if (isAppInDarkTheme()) CODE_DARK else CODE_LIGHT)
    return SpanStyle(fontFamily = FontFamily.Monospace, color = ink, background = ink.copy(alpha = CODE_FILL))
}

/**
 * Appends [code] marked as code ([style], from [codeStyle]), for a screen reader to read character
 * by character ([VerbatimTtsAnnotation]): "a1B2" as "a, 1, B, 2", not as a word.
 */
internal fun AnnotatedString.Builder.appendCode(
    code: String,
    style: SpanStyle,
) {
    pushTtsAnnotation(VerbatimTtsAnnotation(code))
    withStyle(style) { append(code) }
    pop()
}

/** Code's blue on a light theme: 6.4:1 on white, and 4.9:1 on its fill over Material's light surface. */
private const val CODE_LIGHT = 0xFF0B57D0

/** Code's blue on a dark theme: about 10:1 on Material's dark surface, and 7.3:1 on its fill over it. */
private const val CODE_DARK = 0xFFA8C7FA

/** How strong code's fill is: a faint tint of its blue. */
private const val CODE_FILL = 0.14f
