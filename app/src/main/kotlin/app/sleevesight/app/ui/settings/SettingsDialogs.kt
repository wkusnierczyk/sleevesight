package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.sleevesight.app.R
import app.sleevesight.app.keyboard.KeyboardSession
import app.sleevesight.app.keyboard.SessionLedger
import app.sleevesight.app.secrets.SaveResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.WebLink
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.keys.KeyShape
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.launch

private val DIALOG_LIST_HEIGHT = 360.dp

/** Picks one of a setting's values; choosing one saves it and closes the dialog. */
@Composable
internal fun <T> ChoiceDialog(
    choice: SettingsDialog.Choice<T>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(choice.titleArg?.let { stringResource(choice.title, it) } ?: stringResource(choice.title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = DIALOG_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
                for ((value, label) in choice.options) {
                    RadioRow(label = label, selected = value == choice.selected) {
                        choice.onSelect(value)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/**
 * Enters, replaces or removes the key [id]. The saved key is never shown, and what is typed lives
 * only in this dialog's memory: not in saved instance state, which the system may keep on disk.
 * Spaces around the key, as a paste often brings, are dropped. [onSave] and [onRemove] say whether
 * they succeeded: the dialog closes only then, and otherwise says why and keeps what was typed;
 * only a key the key store could not seal suggests *Reset saved keys*, which cannot cure a file that
 * cannot be written. While one runs, the dialog cannot be cancelled or dismissed: it ends as the
 * result says.
 *
 * *Scan QR code* reads the key from a QR code with [scanner] (#268) and fills the field with it, as
 * if typed, once it has the service's shape ([KeyShape]); otherwise it says why and leaves the
 * field as it was. A setup code, which holds several keys, is pointed to *Scan keys*. The window is
 * secure (`FLAG_SECURE`), so no screenshot or screen recording shows what is typed or scanned.
 *
 * *Type on a computer* (#394) scans the code `sleevesight.app/keyboard.html` shows, then runs that
 * session with [keyboard]: the field follows what is typed on the computer, unchecked, as typing
 * on the phone is. The session ends when the user stops it or the dialog closes.
 *
 * The field is a password to the autofill service (#376), so a password manager offers its saved
 * entries on it. It fills but does not save: closing the dialog cancels the autofill session
 * instead of letting Compose commit it, so no manager offers to keep the key as a new login. A key
 * goes into a password manager only by the user putting it there, through that manager's own
 * service, which is what scanning a key handed over by someone else avoids.
 */
@Suppress("LongParameterList") // The key, its state, the actions on it, the scan and the keyboard.
@Composable
internal fun KeyDialog(
    id: SecretId,
    state: SecretState,
    onSave: suspend (String) -> SaveResult,
    onRemove: suspend () -> Boolean,
    scanner: QrScanner,
    keyboard: ComputerKeyboard,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<Int?>(null) }
    var scanning by remember { mutableStateOf<ScanFor?>(null) }
    var session by remember { mutableStateOf<KeyboardSession?>(null) }
    val ledger = SessionLedger.of(LocalContext.current)
    val scope = rememberCoroutineScope()
    val value = typed.trim()
    val autofill = LocalAutofillManager.current

    // Ends the autofill session unsaved before the field goes, which would otherwise commit it.
    fun close() {
        autofill?.cancel()
        onDismiss()
    }

    // Runs [action], which returns what to say of its failure, or null when it succeeded.
    fun run(action: suspend () -> Int?) {
        // `enabled` takes effect only after a recomposition, so two taps in one frame both reach here.
        if (busy) return
        busy = true
        failed = null
        scope.launch {
            val failure = action()
            busy = false
            if (failure == null) close() else failed = failure
        }
    }

    scanning?.let { purpose ->
        scanner(
            { text ->
                scanning = null
                val result = scanned(purpose, id, text.trim(), ledger)
                failed = result.failed
                result.key?.let { typed = it }
                session = result.session
            },
            { scanning = null },
        )
    }
    val running =
        session?.let { s ->
            @Composable { key(s) { keyboard(s, { typed = it }, { session = null }) } }
        }

    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(keyName(id))) },
        text = {
            KeyEntry(id, typed, { typed = it }, failed, busy, running) {
                failed = null
                scanning = it
            }
        },
        confirmButton = {
            TextButton(
                onClick = { run { saveFailure(onSave(value)) } },
                enabled = value.isNotEmpty() && !busy,
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            Row {
                if (state != SecretState.ABSENT) {
                    TextButton(
                        onClick = { run { R.string.settings_key_remove_failed.takeUnless { onRemove() } } },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.settings_remove)) }
                }
                TextButton(onClick = ::close, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
    )
    // The dialog can also go without closing itself, when the screen does. After the dialog in
    // this composition, so that it is forgotten, and cancels, before the dialog's window goes.
    DisposableEffect(autofill) { onDispose { autofill?.cancel() } }
}

/**
 * The key dialog's body, scrolling where the screen is short: who gets the key [id], an AI
 * service's terms with a link to them (#409), the field showing [typed] masked, *Scan QR code* and
 * *Type on a computer*, or the [keyboard] session running instead, and what [failed], if anything;
 * the field and the scans are off while [busy].
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // What the body shows, and what it hears.
@Composable
private fun KeyEntry(
    id: SecretId,
    typed: String,
    onType: (String) -> Unit,
    @StringRes failed: Int?,
    busy: Boolean,
    keyboard: (@Composable () -> Unit)?,
    onScan: (ScanFor) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.settings_key_hint, stringResource(keyRecipient(id))),
            style = MaterialTheme.typography.bodyMedium,
        )
        serviceTerms(id)?.let { terms ->
            Text(stringResource(terms.note), style = MaterialTheme.typography.bodyMedium)
            WebLink(terms.url)
        }
        OutlinedTextField(
            value = typed,
            onValueChange = onType,
            label = { Text(stringResource(R.string.settings_key_field)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.semantics { contentType = ContentType.Password },
        )
        if (keyboard != null) {
            keyboard()
        } else {
            FlowRow {
                TextButton(onClick = { onScan(ScanFor.KEY) }, enabled = !busy) {
                    Text(stringResource(R.string.settings_key_scan))
                }
                TextButton(onClick = { onScan(ScanFor.KEYBOARD) }, enabled = !busy) {
                    Text(stringResource(R.string.settings_key_type))
                }
            }
        }
        failed?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** What the key dialog says of a save that ended as [result]; null for none. */
@StringRes
private fun saveFailure(result: SaveResult): Int? =
    when (result) {
        SaveResult.SAVED -> null
        SaveResult.NOT_SEALED -> R.string.settings_key_save_failed
        SaveResult.NOT_WRITTEN -> R.string.settings_key_write_failed
    }

/** What a [ConfirmDialog] says: its [title], what the action does, its button, and its failure. */
internal class Confirmation(
    val title: String,
    val explained: String,
    val confirm: String,
    val failed: String,
)

/**
 * Confirms a removal: a saved key, or every saved key with the phone's key for them (*Reset saved
 * keys*, *Remove all API keys*). [onConfirm] returns whether it succeeded: the dialog closes only
 * then, and otherwise says so. While it runs, the dialog cannot be cancelled or dismissed, and a
 * second tap does not run it again.
 */
@Composable
internal fun ConfirmDialog(
    confirmation: Confirmation,
    onConfirm: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(confirmation.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(confirmation.explained, style = MaterialTheme.typography.bodyMedium)
                if (failed) {
                    Text(
                        confirmation.failed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // As in [KeyDialog]: a second tap in the same frame finds the button still enabled.
                    if (busy) return@TextButton
                    busy = true
                    failed = false
                    scope.launch {
                        val done = onConfirm()
                        busy = false
                        if (done) onDismiss() else failed = true
                    }
                },
                enabled = !busy,
            ) { Text(confirmation.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * The model for [provider]: its default, stored as no choice so that it follows the default if a
 * later version revises it, or one of the models its service lists for the saved key, fetched by
 * [load] when the dialog opens. A model chosen earlier stays offered when the list cannot be had.
 */
@Composable
internal fun ModelDialog(
    provider: ProviderId,
    current: String?,
    load: suspend () -> ModelLoad,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<ModelLoad>(ModelLoad.Loading, provider) { value = load() }
    val default = Settings.DEFAULT_MODELS[provider]
    val listed = (loaded as? ModelLoad.Listed)?.models.orEmpty()
    val earlier = current?.takeIf { c -> c != default && listed.none { it.id == c } }
    val others =
        listed.filter { it.id != default }.map { it.id to optionLabel(it) } +
            listOfNotNull(earlier?.let { it to it })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ai_model_of, stringResource(providerName(provider)))) },
        text = {
            Column(modifier = Modifier.heightIn(max = DIALOG_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
                modelStatus(loaded)?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                default?.let { d ->
                    val label = stringResource(R.string.settings_ai_model_default, d)
                    RadioRow(label, selected = current == null || current == d) {
                        onSelect(null)
                        onDismiss()
                    }
                }
                for ((id, label) in others) {
                    RadioRow(label, selected = current == id) {
                        onSelect(id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** A listed model as the dialog offers it: its name and id, or its id alone where the service names it by that. */
internal fun optionLabel(model: ModelInfo): String =
    if (model.displayName == model.id) model.id else "${model.displayName} (${model.id})"

private fun modelStatus(load: ModelLoad): Int? =
    when (load) {
        ModelLoad.Loading -> {
            R.string.settings_model_loading
        }

        ModelLoad.Unavailable -> {
            R.string.settings_model_unavailable
        }

        ModelLoad.NoKey -> {
            R.string.settings_model_no_key
        }

        is ModelLoad.Listed -> {
            null
        }

        is ModelLoad.Failed -> {
            when (load.kind) {
                AiErrorKind.UNAUTHORIZED -> R.string.settings_model_failed_unauthorized
                AiErrorKind.OFFLINE -> R.string.settings_model_failed_offline
                AiErrorKind.UNAVAILABLE_HERE -> R.string.settings_model_failed_unavailable_here
                AiErrorKind.QUOTA_EXHAUSTED -> R.string.settings_model_failed_quota_exhausted
                else -> R.string.settings_model_failed_other
            }
        }
    }
