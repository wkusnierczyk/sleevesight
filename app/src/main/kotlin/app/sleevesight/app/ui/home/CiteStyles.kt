package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em

/**
 * The citation chips' looks: a line's (a link), the model's knowledge's, a missing line's, a line's
 * that does not back its sentence ([unsupported], #1036, #1458: a line's chip, grey, as the `?`
 * chip's, with no outline), and the `?` chip after a dimmed sentence ([unbacked], #954: on grey, the
 * text colour at a low opacity, in the muted text colour). Each is a chip of the same shape, size,
 * weight and padding, in the theme's colours, light or dark.
 */
internal class CiteStyles(
    val line: SpanStyle,
    val model: SpanStyle,
    val unknown: SpanStyle,
    val unsupported: SpanStyle = line,
    val unbacked: SpanStyle = line,
)

/** The citation chips' looks in the theme's colours, as the insight shows them. */
@Composable
internal fun citeStyles(): CiteStyles {
    val colors = MaterialTheme.colorScheme

    fun chip(
        background: Color,
        foreground: Color,
    ) = SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Medium, fontSize = CITE_SIZE)
    return CiteStyles(
        line = chip(colors.secondaryContainer, colors.onSecondaryContainer),
        model = chip(colors.tertiaryContainer, colors.onTertiaryContainer),
        unknown = chip(colors.errorContainer, colors.onErrorContainer),
        unsupported = chip(colors.onSurface.copy(alpha = UNBACKED_GREY), colors.onSurfaceVariant),
        unbacked = chip(colors.onSurface.copy(alpha = UNBACKED_GREY), colors.onSurfaceVariant),
    )
}

private val CITE_SIZE = 0.8.em
