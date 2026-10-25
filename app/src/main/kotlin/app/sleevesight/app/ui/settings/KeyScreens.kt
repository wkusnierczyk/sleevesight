package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId

// The key settings' own screens (#446): *Load keys* ([LoadKeysScreen]) and *Purge keys*
// ([PurgeKeysScreen]), in place of their dialogs.

/** How many of a key's last characters the key screens show. */
internal const val SHOWN_END = 4

/** A saved or read key as the key screens show it: its name, and its last characters, if it can be read. */
internal class ShownKey(
    val id: SecretId,
    val ending: String?,
)

/** [key]'s last characters, as the key screens show them: "…a1B2". */
internal fun endingOf(key: String): String = "…" + key.takeLast(SHOWN_END)

/** [key] by its name and its last characters, or, when it cannot be read, by its name and that. */
@Composable
internal fun shownKey(key: ShownKey): String {
    val name = stringResource(keyName(key.id))
    return key.ending?.let { stringResource(R.string.key_ending, name, it) }
        ?: stringResource(R.string.key_unreadable, name)
}

/**
 * A key screen: [title] at the top, then [content], filling the screen. It is a window of its own,
 * secure (`FLAG_SECURE`), so no screenshot or screen recording shows it, as the key dialogs are.
 * Back calls [onDismiss], except while [busy]: a save or a deletion then ends as its result says.
 */
@Composable
internal fun KeyScreen(
    @StringRes title: Int,
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
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                content()
            }
        }
    }
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

/** A key screen's buttons, at the end of its line, wrapping where the screen is narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyScreenActions(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) { content() }
}
