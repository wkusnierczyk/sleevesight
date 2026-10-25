package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeySetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

// *Secrets* (#234, #444): *Load keys* (#268), *Purge keys*, and each key's row, with its button
// that removes it; and their dialogs.

/** The keys' rows under *Secrets*, in the order shown: the AI services', then the sources'. */
private val SHOWN_KEYS =
    listOf(SecretId.ANTHROPIC, SecretId.OPENAI, SecretId.GOOGLE, SecretId.LASTFM, SecretId.EXA)

/**
 * The key [id]'s row: its state, which opens the key's dialog, and, while a key is stored, readable
 * or not, a button that removes it once confirmed.
 */
@Composable
internal fun KeyRow(
    title: String,
    id: SecretId,
    secrets: Map<SecretId, SecretState>,
    indent: Boolean,
    open: (SettingsDialog) -> Unit,
) {
    val state = secrets[id] ?: SecretState.ABSENT
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            ValueRow(title, keyState(state), indent = indent, onClick = { open(SettingsDialog.Key(id)) })
        }
        if (state != SecretState.ABSENT) {
            IconButton(onClick = { open(SettingsDialog.RemoveKey(id)) }) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.settings_key_remove, stringResource(keyName(id))),
                )
            }
        }
    }
}

/**
 * *Secrets*: *Load keys*, which saves several keys from one QR code, a setup code (#268); *Purge
 * keys*, while any key is stored, readable or not, or after a failure ([KeysShown.offerReset]),
 * when it says why (what was *Reset saved keys*, which did the same); and each key's row, the AI
 * services' and the sources' alike.
 */
@Composable
internal fun SecretsSection(
    keys: KeysShown,
    open: (SettingsDialog) -> Unit,
) {
    ValueRow(
        title = stringResource(R.string.settings_scan_keys),
        value = stringResource(R.string.settings_scan_keys_value),
        onClick = { open(SettingsDialog.ScanKeys) },
    )
    if (keys.anyStored || keys.offerReset) {
        val why = if (keys.offerReset) R.string.settings_reset_keys_value else R.string.settings_remove_all_keys_value
        ValueRow(
            title = stringResource(R.string.settings_remove_all_keys),
            value = stringResource(why),
            onClick = { open(SettingsDialog.PurgeKeys) },
        )
    }
    for (id in SHOWN_KEYS) KeyRow(stringResource(keyName(id)), id, keys.states, indent = false, open)
}

/**
 * The saved keys' dialog [shown], on [store]. Each save and removal runs in [saves], which outlives
 * the dialog and the screen, so closing either does not cut it short; the dialog waits for the
 * result. [onSealFailed] hears, from within that work, so even if the dialog has gone, whether the
 * key store could not seal a key saved, and that it has not once every key is removed. [scanner]
 * reads QR codes, and [keyboard] runs *Type on a computer* (#394); *Scan keys* shows what a
 * setup code holds by [open]ing the next dialog, and saves nothing until the user confirms it there.
 */
@Suppress("LongParameterList") // The dialog, the keys, where they are saved, and what the screen hears.
@Composable
internal fun KeyDialogs(
    shown: SettingsDialog.Keys,
    secrets: Map<SecretId, SecretState>,
    store: SecretStore,
    saves: CoroutineScope,
    scanner: QrScanner,
    keyboard: ComputerKeyboard,
    onSealFailed: (Boolean) -> Unit,
    open: (SettingsDialog) -> Unit,
    onDismiss: () -> Unit,
) {
    when (shown) {
        is SettingsDialog.Key -> {
            KeyDialog(
                id = shown.id,
                state = secrets[shown.id] ?: SecretState.ABSENT,
                onSave = { value ->
                    saves
                        .async {
                            store.set(shown.id, Secret(value)).also { onSealFailed(it == SaveResult.NOT_SEALED) }
                        }.await()
                },
                onRemove = { saves.async { store.clear(shown.id) }.await() },
                scanner = scanner,
                keyboard = keyboard,
                onDismiss = onDismiss,
            )
        }

        SettingsDialog.ScanKeys -> {
            scanner({ text -> open(SettingsDialog.SetupFound(KeySetup.read(text))) }, onDismiss)
        }

        is SettingsDialog.SetupFound -> {
            SetupDialog(
                read = shown.read,
                secrets = secrets,
                onSave = { keys -> saves.async { saveAll(store, keys, onSealFailed) }.await() },
                onScanAgain = { open(SettingsDialog.ScanKeys) },
                onDismiss = onDismiss,
            )
        }

        is SettingsDialog.RemoveKey -> {
            ConfirmDialog(
                removeKeyConfirmation(shown.id),
                onConfirm = { saves.async { store.clear(shown.id) }.await() },
                onDismiss,
            )
        }

        SettingsDialog.PurgeKeys -> {
            ConfirmDialog(
                purgeConfirmation(),
                onConfirm = { saves.async { store.reset().also { if (it) onSealFailed(false) } }.await() },
                onDismiss,
            )
        }
    }
}

/**
 * Saves [keys] to [store], one after another, and returns the ones that could not be saved;
 * [onSealFailed] hears whether the key store could not seal one, as for a key saved alone.
 */
private suspend fun saveAll(
    store: SecretStore,
    keys: Map<SecretId, Secret>,
    onSealFailed: (Boolean) -> Unit,
): List<SecretId> {
    val results = keys.map { (id, key) -> id to store.set(id, key) }
    onSealFailed(results.any { it.second == SaveResult.NOT_SEALED })
    return results.filter { it.second != SaveResult.SAVED }.map { it.first }
}

/** What removing the key [id] says. */
@Composable
private fun removeKeyConfirmation(id: SecretId): Confirmation =
    Confirmation(
        title = stringResource(R.string.settings_key_remove_title, stringResource(keyName(id))),
        explained = stringResource(R.string.settings_key_remove_explained, stringResource(keyRecipient(id))),
        confirm = stringResource(R.string.settings_remove),
        failed = stringResource(R.string.settings_key_remove_failed),
    )

/** What *Purge keys*, removing every key with the phone's key for them, says. */
@Composable
private fun purgeConfirmation(): Confirmation =
    Confirmation(
        title = stringResource(R.string.settings_remove_all_keys_title),
        explained = stringResource(R.string.settings_reset_keys_explained),
        confirm = stringResource(R.string.settings_remove_all),
        failed = stringResource(R.string.settings_remove_all_keys_failed),
    )
