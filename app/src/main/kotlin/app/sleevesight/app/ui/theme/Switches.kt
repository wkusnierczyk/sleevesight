package app.sleevesight.app.ui.theme

import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

/** A checkbox's colours, as a switch's: ticked, the box in the text colour and the tick in the surface's. */
@Composable
internal fun plainCheckboxColors(): CheckboxColors {
    val colors = MaterialTheme.colorScheme
    return CheckboxDefaults.colors(checkedColor = colors.onSurface, checkmarkColor = colors.surface)
}

/**
 * A switch's colours (#442): on, the track in the text colour and the thumb in the surface's (black
 * and white, white and black in dark mode); off, and disabled, Material's own greys.
 */
@Composable
internal fun plainSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = colors.surface,
        checkedTrackColor = colors.onSurface,
        checkedBorderColor = colors.onSurface,
        checkedIconColor = colors.onSurface,
    )
}
