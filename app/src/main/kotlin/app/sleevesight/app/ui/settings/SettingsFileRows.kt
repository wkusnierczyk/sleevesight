package app.sleevesight.app.ui.settings

import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.settings.LoadMessage
import app.sleevesight.app.settings.loadPlan
import app.sleevesight.app.ui.theme.DialogPills
import app.sleevesight.app.ui.theme.LocalMessages
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

// *Save to a file* and *Load from a file* (#815), under *Save and reset*: the settings as a file the
// user chooses through Android's file picker, and its preview before a load.

/** The name a saved file is offered under, [date] in ISO form: `sleevesight-settings-2026-11-20.json`. */
internal fun settingsFileName(date: LocalDate): String = "sleevesight-settings-$date.json"

/** The kinds of file *Load from a file* offers: JSON, and what a phone may call a JSON file it does not know. */
private val LOADABLE = arrayOf("application/json", "text/plain", "application/octet-stream")

/**
 * *Save to a file* and *Load from a file*: each button opens Android's file picker. A file saved holds
 * [settings] as they are then ([SettingsFile.write]), and *Settings saved to …* follows. A file
 * picked to load opens *Load settings?* ([LoadDialog]) with what it would change, by each setting's
 * title, a model named by [modelName] and a player by [playerLabel]; *Load* loads it.
 */
@Composable
internal fun SettingsFileRows(
    settings: Settings,
    access: SettingsFileAccess,
    modelName: (String) -> String,
    playerLabel: (String) -> String,
) {
    // The file picked to load, by name and text, kept over a rotation: parsed again as shown.
    // A text too large for the saved state is not kept: a rotation then closes the preview.
    var picked by rememberSaveable(stateSaver = PICKED_SAVER) { mutableStateOf<Pair<String, String>?>(null) }
    val saver = rememberSaver(settings, access)
    val loader = rememberLoader(access) { picked = it }
    ButtonRow(
        title = stringResource(R.string.settings_save_file),
        summary = stringResource(R.string.settings_save_file_summary),
        label = stringResource(R.string.settings_save_file_button),
        tag = SAVE_FILE_TAG,
        onClick = { saver.launch(settingsFileName(access.today())) },
    )
    ButtonRow(
        title = stringResource(R.string.settings_load_file),
        summary = stringResource(R.string.settings_load_file_summary),
        label = stringResource(R.string.settings_load_file_button),
        tag = LOAD_FILE_TAG,
        onClick = { loader.launch(LOADABLE) },
    )
    picked?.let { (name, text) ->
        LoadPreview(name, text, settings, access, modelName, playerLabel) { picked = null }
    }
}

/**
 * *Save to a file*'s picker: the file picked gets [settings] as they are then, and *Settings saved
 * to …* follows, or that it could not be saved.
 */
@Composable
private fun rememberSaver(
    settings: Settings,
    access: SettingsFileAccess,
): ManagedActivityResultLauncher<String, Uri?> {
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    val resources = LocalResources.current
    val current by rememberUpdatedState(settings)
    return rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val shown = current
        scope.launch {
            val said =
                try {
                    // Fails rather than write a file too large to load ([SettingsFile.MAX_BYTES]).
                    access.files.write(uri, SettingsFile.write(shown, access.appVersion, APP_OFFERED))
                    resources.getString(R.string.settings_save_file_done, nameOf(access.files, uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An I/O error, or a file the picker no longer lets the app write.
                    resources.getString(R.string.settings_save_file_failed)
                }
            messages?.show(said)
        }
    }
}

/** [uri]'s name as the picker shows it, else the last part of its path: a name not found fails nothing. */
private suspend fun nameOf(
    files: SettingsFiles,
    uri: Uri,
): String =
    try {
        files.name(uri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment.orEmpty()

/**
 * *Load from a file*'s picker: the file picked, read, is told to [onPicked] by its name and text if it
 * is a settings file; else a message says it holds none, or could not be read.
 */
@Composable
private fun rememberLoader(
    access: SettingsFileAccess,
    onPicked: (Pair<String, String>) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    val resources = LocalResources.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val failure =
                try {
                    val text = access.files.read(uri)
                    if (SettingsFile.read(text, APP_OFFERED) == null) {
                        R.string.settings_load_file_not_settings
                    } else {
                        onPicked(nameOf(access.files, uri) to text)
                        null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    R.string.settings_load_file_unreadable
                }
            failure?.let { messages?.show(resources.getString(it)) }
        }
    }
}

/**
 * *Load settings?* for the file [name] holding [text], against [settings]: *Load* loads it through
 * [access], with its message; either button, or a tap outside, calls [onClose].
 */
@Composable
@Suppress("LongParameterList") // The file, the settings, how to load and to name, and the close.
private fun LoadPreview(
    name: String,
    text: String,
    settings: Settings,
    access: SettingsFileAccess,
    modelName: (String) -> String,
    playerLabel: (String) -> String,
    onClose: () -> Unit,
) {
    val contents = SettingsFile.read(text, APP_OFFERED) ?: return
    // The load's own plan, the keys' gate included, so the preview promises only what it does.
    val plan = loadPlan(contents, settings, access.missing)
    val changes = loadChanges(settings, plan.settings, modelName, playerLabel)
    val skipped = plan.skipped.map { skippedLine(it) } + plan.keyless.map { keylessLine(it) }
    val resources = LocalResources.current
    // As the preview has it, unless what is saved differs: a save ahead of it changed the settings.
    // The message counts settings, as the load saved them; the dialog lists their lines.
    val expected = SettingsFile.changes(settings, plan.settings)
    val message =
        LoadMessage(
            loadedMessage(resources, expected.size, skipped.size),
            expected,
            skipped.size,
        ) { loaded, notLoaded ->
            loadedMessage(resources, loaded, notLoaded)
        }
    LoadDialog(
        from =
            contents.appVersion?.let { stringResource(R.string.settings_load_file_from, name, it) }
                ?: stringResource(R.string.settings_load_file_from_unknown, name),
        changes = changes,
        skipped = skipped,
        onLoad = {
            onClose()
            // Its message comes from [ResetNotice], which outlives a recreation.
            access.load(settings, contents, message)
        },
        onDismiss = onClose,
    )
}

/** *{n} settings loaded.*, or with [skipped] items not loaded, *…, {k} skipped.* */
private fun loadedMessage(
    resources: Resources,
    loaded: Int,
    skipped: Int,
): String =
    if (skipped == 0) {
        resources.getQuantityString(R.plurals.settings_load_file_done, loaded, loaded)
    } else {
        resources.getQuantityString(R.plurals.settings_load_file_done_skipped, loaded, loaded, skipped)
    }

/**
 * *Load settings?* (#815): [from], the file and the version that saved it; [changes], each setting
 * the load changes as *Title: value now → value loaded*, or, with none, that the file matches the
 * settings; [skipped], what of the file is not loaded and why, under *Not loaded:*; and that keys and
 * history are never in such a file. *Load* calls [onLoad], disabled while nothing would change;
 * *Cancel*, or a tap outside, [onDismiss].
 */
@Composable
internal fun LoadDialog(
    from: String,
    changes: List<String>,
    skipped: List<String>,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(LOAD_DIALOG_TAG),
        title = { Text(stringResource(R.string.settings_load_file_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(from, style = MaterialTheme.typography.bodyMedium)
                val body = MaterialTheme.typography.bodyMedium
                if (changes.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_load_file_same),
                        style = body,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        pluralStringResource(R.plurals.settings_load_file_changes, changes.size, changes.size),
                        style = body,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Bullets(changes)
                }
                if (skipped.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_load_file_skipped),
                        style = body,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Bullets(skipped)
                }
                Text(
                    stringResource(R.string.settings_load_file_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            DialogPills(
                dismiss = stringResource(R.string.settings_cancel),
                onDismiss = onDismiss,
                confirm = stringResource(R.string.settings_load_file_button),
                onConfirm = onLoad.takeIf { changes.isNotEmpty() },
                confirmModifier = Modifier.testTag(LOAD_CONFIRM_TAG),
            )
        },
    )
}

/** [lines] as bullets, the first [SHOWN_LINES], then *and {n} more* for the rest. */
@Composable
private fun Bullets(lines: List<String>) {
    lines.take(SHOWN_LINES).forEach { LoadBullet(it) }
    val more = lines.size - SHOWN_LINES
    if (more > 0) LoadBullet(pluralStringResource(R.plurals.settings_load_file_more, more, more))
}

/** The most lines of each list *Load settings?* shows. */
internal const val SHOWN_LINES = 20

/** The most characters of a file picked kept over a rotation (#815): more is read again by no one. */
private const val SAVED_TEXT = 16 * 1024

/** The file picked, by name and text, saved only while its text is small. */
private val PICKED_SAVER =
    Saver<Pair<String, String>?, ArrayList<String>>(
        save = { picked ->
            picked?.takeIf { it.second.length <= SAVED_TEXT }?.let { arrayListOf(it.first, it.second) }
        },
        restore = { saved -> saved[0] to saved[1] },
    )

/** One line of a list, after a bullet a screen reader does not read. */
@Composable
private fun LoadBullet(text: String) {
    Row(Modifier.padding(start = 4.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics {})
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The test tag of *Save to a file*'s button. */
internal const val SAVE_FILE_TAG = "settings:saveFile"

/** The test tag of *Load from a file*'s button. */
internal const val LOAD_FILE_TAG = "settings:loadFile"

/** The test tag of *Load settings?*. */
internal const val LOAD_DIALOG_TAG = "settings:loadDialog"

/** The test tag of *Load settings?*'s *Load*. */
internal const val LOAD_CONFIRM_TAG = "settings:loadConfirm"
