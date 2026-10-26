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
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

// *Secrets* (#234, #444): *Load keys* (#268), *Purge keys*, and each key's row, with its button
// that removes it; and their dialogs and screens (#446).

/** The keys' rows under *Secrets*, in the order shown: the AI services', then the sources'. */
internal val SHOWN_KEYS =
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
 * *Secrets*: *Load keys*, which saves several keys from one QR code, a setup code (#268), on a
 * screen of its own that, in developer mode ([Settings.developerMode]), names the CLI's `keys-qr`
 * too ([LoadKeysScreen]); *Purge keys*, while any key is stored, readable or not, or after a failure
 * ([KeysShown.offerReset]), when it says why (what was *Reset saved keys*, which did the same), on a
 * screen of its own ([PurgeKeysScreen]); each key's row, the AI services' and the sources' alike;
 * and *Show this phone's name to the computer* (#433), for the key dialogs' *Type on a computer*.
 */
@Composable
internal fun SecretsSection(
    settings: Settings,
    keys: KeysShown,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    ValueRow(
        title = stringResource(R.string.settings_scan_keys),
        value = stringResource(R.string.settings_scan_keys_value),
        onClick = { open(SettingsDialog.LoadKeys(settings.developerMode)) },
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
    // A privacy choice, so its title is in the theme's error colour, as *Keep what sources said
    // about each artist* is: on, anyone nearby can see the phone's name during a session.
    SwitchRow(
        title = stringResource(R.string.settings_show_phone_name),
        summary = stringResource(R.string.settings_show_phone_name_summary),
        checked = settings.showPhoneName,
        highlight = true,
    ) { on -> update { it.copy(showPhoneName = on) } }
}

/**
 * The saved keys' dialog [shown], on [store]. Each save and removal runs in [saves], which outlives
 * the dialog and the screen, so closing either does not cut it short; the dialog waits for the
 * result. [onSealFailed] hears, from within that work, so even if the dialog has gone, whether the
 * key store could not seal a key saved, and that it has not once every key is removed. [scanner]
 * reads QR codes, and [keyboard] runs *Type on a computer* (#394), advertising the phone's name
 * with [showPhoneName] (#433). *Load keys* shows what a setup code holds, and saves nothing until
 * the user confirms it there; *Purge keys* lists the keys it deletes, reading each one's last
 * characters from [store].
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
    showPhoneName: Boolean,
    onSealFailed: (Boolean) -> Unit,
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
                showPhoneName = showPhoneName,
                onDismiss = onDismiss,
            )
        }

        is SettingsDialog.LoadKeys -> {
            LoadKeysScreen(
                secrets = secrets,
                scanner = scanner,
                developerMode = shown.developerMode,
                onSave = { keys -> saves.async { saveAll(store, keys, onSealFailed) }.await() },
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
            PurgeKeysScreen(
                secrets = secrets,
                ending = { id -> store.get(id)?.reveal()?.let(::endingOf) },
                onPurge = { saves.async { store.reset().also { if (it.done) onSealFailed(false) } }.await() },
                onDismiss = onDismiss,
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

/** What removing the key [id] says, from its row or its dialog. */
@Composable
internal fun removeKeyConfirmation(id: SecretId): Confirmation =
    Confirmation(
        title = stringResource(R.string.settings_key_remove_title, stringResource(keyName(id))),
        explained = stringResource(R.string.settings_key_remove_explained, stringResource(keyRecipient(id))),
        confirm = stringResource(R.string.settings_remove),
        named = stringResource(R.string.settings_key_remove, stringResource(keyName(id))),
        failed = stringResource(R.string.settings_key_remove_failed),
    )
