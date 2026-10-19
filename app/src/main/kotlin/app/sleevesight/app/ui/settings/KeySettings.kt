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
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.core.Secret

// The API keys (#234): each key's row, with its button that removes it, *Remove all API keys*,
// and their dialogs.

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
 * *Remove all API keys*, while any key is stored, readable or not: the way to remove every key
 * at any time, where *Reset saved keys* above the list shows only after a failure.
 */
@Composable
internal fun SavedKeysSection(
    keys: KeysShown,
    open: (SettingsDialog) -> Unit,
) {
    if (!keys.anyStored) return
    SectionHeading(R.string.settings_section_keys)
    Hint(R.string.settings_keys_hint)
    ValueRow(
        title = stringResource(R.string.settings_remove_all_keys),
        value = stringResource(R.string.settings_remove_all_keys_value),
        onClick = { open(SettingsDialog.RemoveAllKeys) },
    )
}

/**
 * The saved keys' dialog [shown], on [store]. [onSaveFailed] hears whether a key's save failed, and
 * that none has once every key is removed.
 */
@Composable
internal fun KeyDialogs(
    shown: SettingsDialog.Keys,
    secrets: Map<SecretId, SecretState>,
    store: SecretStore,
    onSaveFailed: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (shown) {
        is SettingsDialog.Key -> {
            KeyDialog(
                id = shown.id,
                state = secrets[shown.id] ?: SecretState.ABSENT,
                onSave = { value -> store.set(shown.id, Secret(value)).also { onSaveFailed(!it) } },
                onRemove = { store.clear(shown.id) },
                onDismiss = onDismiss,
            )
        }

        is SettingsDialog.RemoveKey -> {
            ConfirmDialog(removeKeyConfirmation(shown.id), onConfirm = { store.clear(shown.id) }, onDismiss)
        }

        SettingsDialog.ResetKeys, SettingsDialog.RemoveAllKeys -> {
            ConfirmDialog(
                removeAllConfirmation(reset = shown == SettingsDialog.ResetKeys),
                onConfirm = { store.reset().also { if (it) onSaveFailed(false) } },
                onDismiss,
            )
        }
    }
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

/**
 * What removing every key says: as *Reset saved keys* after a failure ([reset]), or as *Remove all
 * API keys*. The removal is the same, and so is what it explains.
 */
@Composable
private fun removeAllConfirmation(reset: Boolean): Confirmation =
    if (reset) {
        Confirmation(
            title = stringResource(R.string.settings_reset_keys_title),
            explained = stringResource(R.string.settings_reset_keys_explained),
            confirm = stringResource(R.string.settings_reset),
            failed = stringResource(R.string.settings_reset_keys_failed),
        )
    } else {
        Confirmation(
            title = stringResource(R.string.settings_remove_all_keys_title),
            explained = stringResource(R.string.settings_reset_keys_explained),
            confirm = stringResource(R.string.settings_remove_all),
            failed = stringResource(R.string.settings_remove_all_keys_failed),
        )
    }
