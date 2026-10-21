package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.SetupRead
import kotlinx.coroutines.launch

// *Scan keys* (#268): what a scanned setup code holds, confirmed before anything is saved.

private val SETUP_LIST_HEIGHT = 360.dp

/** How many of a key's last characters the confirmation shows. */
private const val SHOWN_END = 4

/**
 * What the setup code [read] holds, before anything is saved: each key the code has in its
 * service's shape, by its last four characters, and whether it replaces a key stored ([secrets]);
 * each key refused and why; the names this version does not know; the lines that could not be read.
 * *Save* saves the keys with [onSave], which returns the ones that could not be saved: the dialog
 * closes once none is left, and otherwise names them and keeps them to try again. Not a setup code,
 * or one of a later version, says so and offers [onScanAgain]. The window is secure (`FLAG_SECURE`).
 */
@Composable
internal fun SetupDialog(
    read: SetupRead,
    secrets: Map<SecretId, SecretState>,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onScanAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (read) {
        SetupRead.NotASetup -> {
            NotSavedDialog(
                title = stringResource(R.string.setup_not_a_setup_title),
                explained = stringResource(R.string.setup_not_a_setup),
                onScanAgain = onScanAgain,
                onDismiss = onDismiss,
            )
        }

        is SetupRead.Unsupported -> {
            NotSavedDialog(
                title = stringResource(R.string.setup_unsupported_title),
                explained = stringResource(R.string.setup_unsupported, read.version),
                onScanAgain = onScanAgain,
                onDismiss = onDismiss,
            )
        }

        is SetupRead.Setup -> {
            ConfirmSetupDialog(read, secrets, onSave, onDismiss)
        }
    }
}

@Composable
private fun ConfirmSetupDialog(
    setup: SetupRead.Setup,
    secrets: Map<SecretId, SecretState>,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onDismiss: () -> Unit,
) {
    val keys = remember(setup) { setup.keys.mapKeys { SecretId.of(it.key) } }
    var busy by remember { mutableStateOf(false) }
    var unsaved by remember { mutableStateOf<List<SecretId>>(emptyList()) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.setup_title)) },
        text = { SetupList(setup, keys, secrets, unsaved) },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    scope.launch {
                        // After a failure, only the keys not yet saved are saved again.
                        val left = if (unsaved.isEmpty()) keys else keys.filterKeys { it in unsaved }
                        unsaved = onSave(left)
                        busy = false
                        if (unsaved.isEmpty()) onDismiss()
                    }
                },
                enabled = keys.isNotEmpty() && !busy,
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * What the setup code [setup] holds: its [keys] in their services' shape, each by its last four
 * characters and whether it replaces a key stored ([secrets]); the keys refused; the names unknown;
 * the lines unreadable; and the keys that could not be saved ([unsaved]).
 */
@Composable
private fun SetupList(
    setup: SetupRead.Setup,
    keys: Map<SecretId, Secret>,
    secrets: Map<SecretId, SecretState>,
    unsaved: List<SecretId>,
) {
    Column(
        modifier = Modifier.heightIn(max = SETUP_LIST_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        Text(stringResource(R.string.setup_explained), style = style)
        for ((id, key) in keys) {
            val stored = (secrets[id] ?: SecretState.ABSENT) != SecretState.ABSENT
            val line = if (stored) R.string.setup_replaces else R.string.setup_new
            Text(
                stringResource(line, stringResource(keyName(id)), "…" + key.reveal().takeLast(SHOWN_END)),
                style = style,
            )
        }
        for ((name, problem) in setup.refused) {
            val id = SecretId.of(name)
            Text(
                stringResource(
                    R.string.setup_refused,
                    stringResource(keyName(id)),
                    stringResource(refusal(problem)),
                ),
                style = style,
            )
        }
        if (setup.unknown.isNotEmpty()) {
            Text(stringResource(R.string.setup_unknown, setup.unknown.joinToString()), style = style)
        }
        if (setup.unreadable > 0) {
            Text(
                pluralStringResource(R.plurals.setup_unreadable, setup.unreadable, setup.unreadable),
                style = style,
            )
        }
        if (keys.isEmpty()) Text(stringResource(R.string.setup_nothing), style = style)
        if (unsaved.isNotEmpty()) {
            val names = unsaved.map { stringResource(keyName(it)) }.joinToString()
            Text(
                stringResource(R.string.setup_failed, names),
                style = style,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A scan that holds nothing to save: [explained] says why. */
@Composable
private fun NotSavedDialog(
    title: String,
    explained: String,
    onScanAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(explained, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onScanAgain) { Text(stringResource(R.string.setup_scan_again)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** Why a key in a setup code is not saved. */
@StringRes
private fun refusal(problem: KeyProblem): Int =
    when (problem) {
        KeyProblem.EMPTY -> R.string.setup_refused_empty
        KeyProblem.BAD_CHARACTERS -> R.string.setup_refused_characters
        KeyProblem.WRONG_SHAPE -> R.string.setup_refused_shape
        KeyProblem.DUPLICATE -> R.string.setup_refused_duplicate
    }
