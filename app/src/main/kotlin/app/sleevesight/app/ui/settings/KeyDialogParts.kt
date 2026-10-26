package app.sleevesight.app.ui.settings

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

// The key dialog's terms text and buttons (#543).

/** Material's alpha for a disabled button's outline, on the surface's own colour. */
private const val DISABLED_OUTLINE_ALPHA = 0.12f

/**
 * The string [id] with its bold, as the resource marks it with `<b>`: an AI service's terms, whose
 * agreement is bold (#543). `stringResource` drops the markup; `getText` keeps it as spans.
 */
@Composable
internal fun boldStringResource(
    @StringRes id: Int,
): AnnotatedString {
    val text = LocalResources.current.getText(id)
    return buildAnnotatedString {
        append(text.toString())
        if (text is Spanned) {
            for (span in text.getSpans(0, text.length, StyleSpan::class.java)) {
                if (span.style == Typeface.BOLD || span.style == Typeface.BOLD_ITALIC) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), text.getSpanStart(span), text.getSpanEnd(span))
                }
            }
        }
    }
}

/**
 * The key dialog's buttons, the same for every key (#543): *Cancel* at the start; at the end,
 * *Remove*, only while a key is [stored], a danger action, and *Save*, which needs something
 * [savable]. Where the line is too short, as at a large font scale, the buttons that do not fit
 * wrap, in their order, to a line of their own, at its end. Nothing can be tapped while [busy].
 */
@Suppress("LongParameterList") // The key, its state, and the three actions.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyDialogButtons(
    name: String,
    stored: Boolean,
    savable: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onSave: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
        // Fills what the line leaves, so that *Cancel* keeps to the start.
        Spacer(Modifier.weight(1f))
        if (stored) RemoveKeyButton(name, enabled = !busy, onClick = onRemove)
        TextButton(onClick = onSave, enabled = savable && !busy) { Text(stringResource(R.string.settings_save)) }
    }
}

/**
 * *Remove*, which deletes the key [name] at once: in the theme's error colour, outlined, with the
 * trash can, so that it cannot be taken for *Save*. A screen reader names the key it removes.
 */
@Composable
private fun RemoveKeyButton(
    name: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val outline = if (enabled) error else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA)
    val label = stringResource(R.string.settings_key_remove, name)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
        border = BorderStroke(1.dp, outline),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(
            painterResource(R.drawable.ic_delete),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.settings_remove))
    }
}
