package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The height of the block of the track's key data on Home (#1407).

/**
 * The least height of [KeyData]'s block (#1407): the title, the artists, the album and the release
 * line on one line each, in their styles at the font size shown, and the gaps between them.
 */
@Composable
internal fun keyDataMinimum(): Dp {
    val measurer = rememberTextMeasurer()
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    val styles =
        listOf(
            type.headlineMedium.copy(fontWeight = FontWeight.Bold),
            type.bodyLarge,
            type.bodyLarge,
            type.bodyMedium,
        )
    return remember(measurer, styles, density) {
        val lines = styles.sumOf { measurer.measure(ONE_LINE, it, maxLines = 1).size.height }
        with(density) { lines.toDp() } + KEY_LINE_GAP * (styles.size - 1)
    }
}

/** The space between [KeyData]'s lines, and between the album and the release line under it. */
internal val KEY_LINE_GAP = 2.dp

/** What a line's height is measured on: a capital and a descender, as tall as any line of text. */
private const val ONE_LINE = "Ag"
