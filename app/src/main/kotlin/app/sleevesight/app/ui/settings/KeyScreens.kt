package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.ui.theme.appendCode
import app.sleevesight.app.ui.theme.codeStyle

// The key settings' own screens (#446): *Load keys* ([LoadKeysScreen]) and *Purge keys*
// ([PurgeKeysScreen]), in place of their dialogs.

/** How many of a key's last characters the key screens show. */
internal const val SHOWN_END = 4

/** A saved or read key as the key screens show it: its name, and its last characters, if it can be read. */
internal data class ShownKey(
    val id: SecretId,
    val ending: String?,
)

/**
 * A key in a [KeyTable]: [key], and, on *Load keys*' list of the keys read, whether it [replaces] a
 * saved key (true) or is new (false); null elsewhere.
 */
internal data class KeyRow(
    val key: ShownKey,
    val replaces: Boolean? = null,
)

/** [key]'s last characters, as the key screens show them: "a1B2". */
internal fun endingOf(key: String): String = key.takeLast(SHOWN_END)

/**
 * The text [line] of a key, by its [name] (`%1$s`) and its [ending] (`%2$s`, from [endingOf]), the
 * ending marked as code ([codeStyle]) and read by a screen reader character by character (#542):
 * "Anthropic, ending `a1B2`". A [line] without the ending is that text alone.
 */
@Composable
internal fun keyLine(
    @StringRes line: Int,
    name: String,
    ending: String,
): AnnotatedString {
    val text = stringResource(line, name, ENDING_MARK)
    val code = codeStyle()
    return buildAnnotatedString {
        val at = text.indexOf(ENDING_MARK)
        if (at < 0) {
            append(text)
            return@buildAnnotatedString
        }
        append(text, 0, at)
        appendCode(ending, code)
        append(text, at + ENDING_MARK.length, text.length)
    }
}

/** Where [keyLine] puts the ending: a character no key's name has. */
private const val ENDING_MARK = "\uFFFC"

/**
 * A key screen: [title] at the top, then [content], filling the screen. It is a window of its own,
 * secure (`FLAG_SECURE`), so no screenshot or screen recording shows it, as the key dialogs are.
 * Back calls [onDismiss], except while [busy]: a save or a deletion then ends as its result says.
 * With no [title], [content] shows it itself ([KeyScreenTitle]), as a guided scan does inside what
 * scrolls, so that a short window leaves the camera's view as much room as it can (#541).
 */
@Composable
internal fun KeyScreen(
    @StringRes title: Int?,
    busy: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                title?.let { KeyScreenTitle(it) }
                content()
            }
        }
    }
}

/** A key screen's [title], which a screen reader reads as a heading. */
@Composable
internal fun KeyScreenTitle(
    @StringRes title: Int,
) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * A step's heading on a key screen: a screen reader reads it as a heading, and, when [announced],
 * says it as soon as it shows, as a result that replaces what was there.
 */
@Composable
internal fun StepHeading(
    text: String,
    announced: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
                if (announced) liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * A key screen's buttons: [cancel], if any, at the start of the line, as in the key dialog (#541,
 * #543), and the others ([content]) at its end, wrapping where the screen is narrow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyScreenActions(
    cancel: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        cancel?.invoke()
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) { content() }
    }
}
