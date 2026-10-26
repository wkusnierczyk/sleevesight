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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.sleevesight.app.secrets.SecretId

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
 * A key dialog's buttons, the same in every one (#543): *Cancel* at the start, and the dialog's
 * [actions] at the end: the key dialog's *Remove* and *Save*, the confirmation's *Remove*. Where
 * the line is too short, as at a large font scale, the buttons that do not fit wrap, in their
 * order, to a line of their own, at its end. *Cancel* cannot be tapped while [busy].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyDialogButtons(
    busy: Boolean,
    onCancel: () -> Unit,
    actions: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
        // Fills what the line leaves, so that *Cancel* keeps to the start.
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/**
 * The key dialog's buttons for the key [id]: *Cancel*, then *Remove*, only while a key is [stored],
 * and *Save*, which needs something [savable]. *Remove* asks first, with the key row's own
 * confirmation ([ConfirmDialog]), which runs [onRemove], says there if it failed, and, once the key
 * is removed, closes and calls [onRemoved]. Nothing can be tapped while [busy].
 */
@Suppress("LongParameterList") // The key, its state, and the actions.
@Composable
internal fun KeyEntryButtons(
    id: SecretId,
    stored: Boolean,
    savable: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onRemove: suspend () -> Boolean,
    onRemoved: () -> Unit,
    onSave: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    KeyDialogButtons(busy = busy, onCancel = onCancel) {
        if (stored) {
            RemoveKeyButton(
                label = stringResource(R.string.settings_remove),
                named = stringResource(R.string.settings_key_remove, stringResource(keyName(id))),
                enabled = !busy,
                onClick = { confirming = true },
            )
        }
        SaveKeyButton(enabled = savable && !busy, onClick = onSave)
    }
    if (confirming) {
        ConfirmDialog(
            removeKeyConfirmation(id),
            onConfirm = onRemove,
            onDismiss = { confirming = false },
            onDone = {
                confirming = false
                onRemoved()
            },
        )
    }
}

/** *Save*, the key dialog's main action: Material's filled button. */
@Composable
internal fun SaveKeyButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.settings_save)) }
}

/**
 * A button that removes a key, [label] (*Remove*): in the theme's error colour, outlined, with the
 * trash can, so that it cannot be taken for *Save*. A screen reader hears it [named] for the key it
 * removes (*Remove the Last.fm API key*).
 */
@Composable
internal fun RemoveKeyButton(
    label: String,
    named: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val outline = if (enabled) error else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
        border = BorderStroke(1.dp, outline),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.semantics { contentDescription = named },
    ) {
        Icon(
            painterResource(R.drawable.ic_delete),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
    }
}
