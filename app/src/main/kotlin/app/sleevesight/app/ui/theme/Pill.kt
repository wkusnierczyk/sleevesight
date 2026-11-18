package app.sleevesight.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The pill (#1387): every button in Settings and the prompt lab, a panel's *Close*, and Home's
// text actions (#1404).

/**
 * The app's small outlined button, a pill (#1126, #1365, #1387, #1404), for every button in Settings
 * and the prompt lab, a panel's *Close* ([Panel]), and Home's actions (*Retry*, *Stop*, *Change*):
 * [label] on it, read by it, or by [named] where the label alone would not say what it does out of
 * context.
 */
@Composable
internal fun PillButton(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    named: String? = null,
    destructive: Boolean = false,
) = PillButton(onClick, modifier, named, destructive) { Text(label) }

/**
 * A pill with [content] on it, read by [named], or, when null, by what [content] says (an
 * [app.sleevesight.app.ui.home.AiLabel]'s "…, uses AI"); [onClick] runs on a tap, and a null
 * [onClick] disables it. [action], when given, names the tap for a screen reader ("Double-tap to
 * ask MusicBrainz again"), where the label alone (*Retry*) does not say what it does. Its text in
 * the controls' colour, black in light mode and white in dark, as the switches'
 * ([plainSwitchColors]), with Material's outline; a [destructive] one, which deletes, in the theme's
 * error colour, its outline too. Disabled, its text and outline are Material's disabled greys, a
 * [destructive] one's too.
 */
@Suppress("LongParameterList") // The tap, how it reads, how it looks, and what it shows.
@Composable
internal fun PillButton(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    named: String? = null,
    destructive: Boolean = false,
    action: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val described = named?.let { modifier.semantics { contentDescription = it } } ?: modifier
    OutlinedButton(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier =
            if (action != null && onClick != null) {
                described.semantics {
                    onClick(label = action) {
                        onClick()
                        true
                    }
                }
            } else {
                described
            },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border =
            if (destructive && onClick != null) {
                BorderStroke(1.dp, color)
            } else {
                ButtonDefaults.outlinedButtonBorder(onClick != null)
            },
        content = content,
    )
}
