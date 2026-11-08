package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.KEYBOARD_PAGE_URL
import app.sleevesight.app.ui.LOAD_KEYS_DOCS_URL
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.shownAddress
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
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
 * or not, a button that removes it once confirmed. A service's link to its key lands on it
 * ([SettingsTarget.key], [KeyLink]).
 */
@Composable
internal fun KeyRow(
    title: String,
    id: SecretId,
    secrets: Map<SecretId, SecretState>,
    indent: Boolean,
    open: (SettingsDialog) -> Unit,
) {
    if (!shownInSearch(title)) return
    val state = secrets[id] ?: SecretState.ABSENT
    LandingSpot(SettingsTarget.key(id)) {
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
}

/**
 * *Secrets*: *Load keys*, which saves several keys from one QR code, a setup code (#268), on a
 * screen of its own ([LoadKeysScreen]), with, under it, where the code is made and the guide to it
 * (#541); *Purge keys*, while any key is stored, readable or not, or after a failure
 * ([KeysShown.offerReset]), when it says why (what was *Reset saved keys*, which did the same), on a
 * screen of its own ([PurgeKeysScreen]); each key's row, the AI services' and the sources' alike;
 * and *Share phone's name with Type on a computer* (#433, #544), for the key dialogs' *Type on a
 * computer*, which its summary explains, with a link to the page the computer opens.
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
        onClick = { open(SettingsDialog.LoadKeys) },
    )
    LoadKeysMore()
    if (keys.anyStored || keys.offerReset) {
        val why = if (keys.offerReset) R.string.settings_reset_keys_value else R.string.settings_remove_all_keys_value
        ValueRow(
            title = stringResource(R.string.settings_remove_all_keys),
            value = stringResource(why),
            onClick = { open(SettingsDialog.PurgeKeys) },
        )
    }
    for (id in SHOWN_KEYS) KeyRow(stringResource(keyName(id)), id, keys.states, indent = false, open)
    // Its trade-off, that anyone nearby can see the phone's name during a session, is said in the
    // summary in the normal colour (#544): the error colour made it look alarming for what it is.
    val keyboardPage = shownAddress(KEYBOARD_PAGE_URL)
    SwitchRow(
        title = stringResource(R.string.settings_show_phone_name),
        summary = stringResource(R.string.settings_show_phone_name_summary),
        checked = settings.showPhoneName,
        below = {
            ExternalLink(
                text = keyboardPage,
                description = stringResource(R.string.settings_open_page, keyboardPage),
                onClick = rememberOpenUrl(KEYBOARD_PAGE_URL),
            )
        },
    ) { on -> update { it.copy(showPhoneName = on) } }
}

/**
 * Under *Load keys* (#541): where its QR code is made, [API_KEYS_URL], in full as plain text that
 * can be selected (#765: a link would open it on the phone, not the computer), and the
 * documentation's guide to loading keys, [LOAD_KEYS_DOCS_URL], a link. Apart from the row, which
 * opens the screen, so that the link is a target of its own.
 */
@Composable
private fun LoadKeysMore() {
    // With *Load keys*, whose row it explains (#649).
    if (!shownInSearch(stringResource(R.string.settings_scan_keys))) return
    // The address as plain text (#765), a part of its own rather than formatted in (#1091).
    val parts =
        linkedParts(
            stringResource(R.string.settings_scan_keys_more),
            TextPart.Words(API_KEYS_URL),
            TextPart.Link(stringResource(R.string.settings_scan_keys_docs), LOAD_KEYS_DOCS_URL),
        )
    SelectionContainer {
        LinkedText(
            parts,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
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
 *
 * A key saved switches its service on only as the user chooses after the save (#763), in the key
 * dialog or on *Load keys*, from the [settings] as they are: [applyChoices] writes the choices to
 * the stored settings, after the key dialog's key in the same work in [saves], and the summary
 * says what was saved and switched on or off as written, or that the choices could not be, with
 * *Try again*. A link on *Load
 * keys* to a section of the settings calls [onOpen]. The key dialog's *Open Load keys* (#765), after
 * a scan of a code it cannot take, closes it unsaved and calls [onOpenLoadKeys].
 */
@Suppress("LongParameterList", "LongMethod") // The dialog, the keys, where they are saved, and what the screen hears.
@Composable
internal fun KeyDialogs(
    shown: SettingsDialog.Keys,
    secrets: Map<SecretId, SecretState>,
    settings: Settings,
    applyChoices: suspend (Map<SecretId, Boolean>) -> Applied?,
    store: SecretStore,
    saves: CoroutineScope,
    scanner: QrScanner,
    keyboard: ComputerKeyboard,
    showPhoneName: Boolean,
    onSealFailed: (Boolean) -> Unit,
    onOpen: (SettingsTarget) -> Unit,
    onOpenLoadKeys: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (shown) {
        is SettingsDialog.Key -> {
            val id = shown.id
            val state = secrets[id] ?: SecretState.ABSENT
            // What the save wrote, once it has: the choices and the settings as written.
            var written by remember(shown) { mutableStateOf<KeyWrite?>(null) }
            var summary by remember(shown) { mutableStateOf<SavedKey?>(null) }
            var busy by remember(shown) { mutableStateOf(false) }
            val done = summary
            val write = written
            if (done != null && write != null) {
                KeyScreen(keyName(id), busy = busy, onDismiss = onDismiss) {
                    SummaryStep(
                        keys = listOf(done),
                        secrets = write.secrets,
                        first = write.applied,
                        retry = { saves.async { applyChoices(write.choices) }.await() },
                        onBusy = { busy = it },
                        onDone = onDismiss,
                    )
                }
                return
            }
            val offer = enableOffer(id, settings, secrets)
            KeyDialog(
                id = id,
                state = state,
                offer = offer,
                onSave = { value, choices ->
                    val before = secrets
                    val snapshot = settings
                    // The key and then its choices, in one piece of work that outlives the dialog,
                    // so a rotation during the save still switches the service on.
                    saves
                        .async {
                            val result = store.set(id, Secret(value)).also { onSealFailed(it == SaveResult.NOT_SEALED) }
                            if (result == SaveResult.SAVED) {
                                val applied =
                                    if (choices.isEmpty()) {
                                        Applied(
                                            snapshot,
                                            snapshot,
                                        )
                                    } else {
                                        applyChoices(choices)
                                    }
                                written = KeyWrite(choices, before, applied)
                            }
                            result
                        }.await()
                },
                onSaved = { ending -> summary = SavedKey(id, ending, replaced = state != SecretState.ABSENT) },
                onRemove = { saves.async { store.clear(id) }.await() },
                scanner = scanner,
                keyboard = keyboard,
                showPhoneName = showPhoneName,
                onOpenLoadKeys = onOpenLoadKeys,
                onDismiss = onDismiss,
            )
        }

        SettingsDialog.LoadKeys -> {
            LoadKeysScreen(
                secrets = secrets,
                settings = settings,
                scanner = scanner,
                // The keys and then the choices, in one piece of work that outlives the screen, so
                // a rotation during the save still switches the services on (#765).
                onComplete = { keys, choices ->
                    saves.async { complete(store, keys, onSealFailed) { applyChoices(choices) } }.await()
                },
                onApply = { choices -> saves.async { applyChoices(choices) }.await() },
                onOpen = onOpen,
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

/**
 * *Load keys*' *Complete* (#765): saves [keys] to [store] ([saveAll]), and, once every one is saved,
 * writes the choices with [apply]; what it did, as [Completed] has it.
 */
private suspend fun complete(
    store: SecretStore,
    keys: Map<SecretId, Secret>,
    onSealFailed: (Boolean) -> Unit,
    apply: suspend () -> Applied?,
): Completed {
    val unsaved = if (keys.isEmpty()) emptyList() else saveAll(store, keys, onSealFailed)
    return Completed(unsaved, if (unsaved.isEmpty()) apply() else null)
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

/**
 * What the key dialog's save wrote besides the key: its [choices], with the keys stored before
 * ([secrets]), as written ([applied]), or null when they could not be.
 */
private class KeyWrite(
    val choices: Map<SecretId, Boolean>,
    val secrets: Map<SecretId, SecretState>,
    val applied: Applied?,
)
