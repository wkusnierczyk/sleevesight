package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.ai.AiErrorKind
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
        title = { Text(stringResource(choice.title)) },
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
 * Spaces around the key, as a paste often brings, are dropped. [onSave] and [onRemove] return
 * whether they succeeded: the dialog closes only then, and otherwise says so and keeps what was typed.
 */
@Composable
internal fun KeyDialog(
    id: SecretId,
    state: SecretState,
    onSave: suspend (String) -> Boolean,
    onRemove: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val value = typed.trim()

    fun run(
        @StringRes failure: Int,
        action: suspend () -> Boolean,
    ) {
        busy = true
        failed = null
        scope.launch {
            val done = action()
            busy = false
            if (done) onDismiss() else failed = failure
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(keyName(id))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_key_hint, stringResource(keyRecipient(id))),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.settings_key_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                failed?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { run(R.string.settings_key_save_failed) { onSave(value) } },
                enabled = value.isNotEmpty() && !busy,
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            Row {
                if (state != SecretState.ABSENT) {
                    TextButton(
                        onClick = { run(R.string.settings_key_remove_failed, onRemove) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.settings_remove)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
    )
}

/**
 * Confirms *Reset saved keys*, which removes every saved key and the phone's key for them. [onReset]
 * returns whether it succeeded: the dialog closes only then, and otherwise says so.
 */
@Composable
internal fun ResetKeysDialog(
    onReset: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_keys_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_reset_keys_explained),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (failed) {
                    Text(
                        stringResource(R.string.settings_reset_keys_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    failed = false
                    scope.launch {
                        val done = onReset()
                        busy = false
                        if (done) onDismiss() else failed = true
                    }
                },
                enabled = !busy,
            ) { Text(stringResource(R.string.settings_reset)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
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
        listed.filter { it.id != default }.map { it.id to "${it.displayName} (${it.id})" } +
            listOfNotNull(earlier?.let { it to it })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_model_title)) },
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
                else -> R.string.settings_model_failed_other
            }
        }
    }
