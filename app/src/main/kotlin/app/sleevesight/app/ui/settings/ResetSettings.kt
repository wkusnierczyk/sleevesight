package app.sleevesight.app.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.settings.Resets
import app.sleevesight.app.settings.SettingsReset
import app.sleevesight.app.ui.theme.DialogPills
import app.sleevesight.app.ui.theme.LocalMessages
import app.sleevesight.app.ui.theme.plainCheckboxColors
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CancellationException

// *Save and reset* (#960): *Reset to the defaults*, and its dialog; then *Save to a file* and *Load
// from a file* (#815, [SettingsFileRows]).

/**
 * *Save and reset*'s rows: *Reset to the defaults*, whose button opens [ResetDialog] for
 * [settings], *Include developer settings* unticked; once the reset is saved, [ResetNotice] says
 * so. Then *Save to a file* and *Load from a file* ([SettingsFileRows], through [files]). [modelName]
 * names a model as its row does, [playerLabel] a player.
 */
@Composable
internal fun SaveAndResetSection(
    settings: Settings,
    resetter: SettingsResetter,
    files: SettingsFileAccess,
    modelName: (String) -> String,
    playerLabel: (String) -> String,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    var includeDeveloper by rememberSaveable { mutableStateOf(false) }
    ButtonRow(
        title = stringResource(R.string.settings_reset),
        summary = stringResource(R.string.settings_reset_summary),
        label = stringResource(R.string.settings_reset_button),
        tag = RESET_TAG,
        onClick = {
            includeDeveloper = false
            asking = true
        },
    )
    if (asking) {
        val full = resetter.preview(settings, true)
        val developer = resetChanges(settings, full, modelName, developer = true)
        val others = resetChanges(settings, full, modelName, developer = false)
        ResetDialog(
            changes = if (includeDeveloper) others + developer else others,
            stays =
                resetStays(settings, resetter.defaults) +
                    listOfNotNull(
                        stringResource(R.string.settings_reset_keeps_developer).takeIf {
                            developer.isNotEmpty() && !includeDeveloper
                        },
                    ),
            // Offered only while a developer setting differs from its default.
            includeDeveloper = includeDeveloper.takeIf { developer.isNotEmpty() },
            onIncludeDeveloper = { includeDeveloper = it },
            onReset = {
                asking = false
                // Its message comes from [ResetNotice], which outlives a recreation.
                resetter.reset(settings, includeDeveloper)
            },
            onDismiss = { asking = false },
        )
    }
    SettingsFileRows(settings, files, modelName, playerLabel)
}

/**
 * Says *Settings reset to the defaults.* ([LocalMessages]) once the last reset asked for ([resets])
 * is saved, or a load's own message (#815), once only: on this screen, or on the one made after a
 * recreation, as when the reset changed the theme and Android restarted the activity. A screen
 * recreated before its message has shown and gone gives it back, so the new screen says it; one left
 * for good does not.
 */
@Composable
internal fun ResetNotice(resets: Resets) {
    val pending by resets.pending.collectAsState()
    val messages = LocalMessages.current
    val activity = LocalActivity.current
    val done = stringResource(R.string.settings_reset_done)
    LaunchedEffect(pending, messages) {
        val reset = pending ?: return@LaunchedEffect
        if (messages == null || !reset.saved.await() || !resets.take(reset)) return@LaunchedEffect
        try {
            messages.showing(reset.message ?: done)
        } catch (gone: CancellationException) {
            // Recreated before the message had shown and gone (a reset of the theme): the new screen
            // says it. Left (Back), the message goes with the screen, never to come up on a later visit.
            if (activity?.isChangingConfigurations == true) resets.giveBack(reset)
            throw gone
        }
    }
}

/** *Save and reset*'s rows as the settings' search finds them. */
@Composable
internal fun saveAndResetRows(): List<IndexedRow> =
    listOf(
        indexRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_summary)),
        indexRow(stringResource(R.string.settings_save_file), stringResource(R.string.settings_save_file_summary)),
        indexRow(stringResource(R.string.settings_load_file), stringResource(R.string.settings_load_file_summary)),
    )

/**
 * *Reset settings?* (#960): [changes], the settings that go back to their defaults, each as its
 * value now and its default, or, with none, that every setting is at its default already; then
 * [stays], what the reset keeps; then, unless [includeDeveloper] is null (no developer setting
 * differs), *Include developer settings*, ticked or not as it says, each tap told to
 * [onIncludeDeveloper]. *Reset* calls [onReset], and is disabled while nothing would change;
 * *Cancel*, or a tap outside, [onDismiss].
 */
@Composable
@Suppress("LongParameterList") // Its lists, its checkbox, and its two buttons.
internal fun ResetDialog(
    changes: List<String>,
    stays: List<String>,
    includeDeveloper: Boolean?,
    onIncludeDeveloper: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RESET_DIALOG_TAG),
        title = { Text(stringResource(R.string.settings_reset_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (changes.isEmpty()) {
                    Text(stringResource(R.string.settings_reset_none), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.settings_reset_changes), style = MaterialTheme.typography.bodyMedium)
                    changes.forEach { Bullet(it) }
                }
                Text(
                    stringResource(R.string.settings_reset_stays),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                stays.forEach { Bullet(it) }
                includeDeveloper?.let { DeveloperCheck(it, onIncludeDeveloper) }
            }
        },
        // *Cancel* and *Reset* as the settings' pills, in one row.
        confirmButton = {
            DialogPills(
                dismiss = stringResource(R.string.settings_cancel),
                onDismiss = onDismiss,
                confirm = stringResource(R.string.settings_reset_button),
                onConfirm = onReset.takeIf { changes.isNotEmpty() },
                confirmModifier = Modifier.testTag(RESET_CONFIRM_TAG),
            )
        },
    )
}

/** *Include developer settings*: a black checkbox and its label, one target, [checked] or not. */
@Composable
private fun DeveloperCheck(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
                .testTag(RESET_DEVELOPER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, colors = plainCheckboxColors())
        Text(
            stringResource(R.string.settings_reset_include_developer),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** One line of a list, after a bullet a screen reader does not read. */
@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(start = 4.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics {})
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * What a reset keeps, as its dialog lists it ([SettingsReset.KEPT]): the API keys, the history, the
 * players followed, which AI service is on, *Developer mode* while it is on, what is dismissed, and
 * *Keep history for* where the reset keeps it off its default in [defaults], the store's.
 */
@Composable
internal fun resetStays(
    current: Settings,
    defaults: Settings,
): List<String> {
    val history = stringResource(R.string.settings_keep_history)
    val historyKept = SettingsReset.shortens(current.historyLifetime, defaults.historyLifetime)
    return listOfNotNull(
        stringResource(R.string.settings_reset_keeps_keys),
        stringResource(R.string.settings_reset_keeps_history),
        // Kept off its default: the default would have deleted history.
        if (historyKept) {
            stringResource(R.string.settings_reset_kept_value, history, lifetimeLabel(current.historyLifetime))
        } else {
            null
        },
        stringResource(R.string.settings_reset_keeps_players),
        stringResource(R.string.settings_reset_keeps_ai),
        if (current.developerMode) stringResource(R.string.settings_developer_mode) else null,
        stringResource(
            R.string.settings_reset_keeps_dismissed,
            stringResource(R.string.settings_section_appearance),
            stringResource(R.string.settings_restore_dismissed),
        ),
    )
}

/**
 * The settings [current] has off what a reset leaves ([reset]), each as *Title: value now →
 * default*, in the order the settings show them ([RESET_LINES]): the developer settings' with
 * [developer], else the others'.
 */
@Composable
internal fun resetChanges(
    current: Settings,
    reset: Settings,
    modelName: (String) -> String,
    developer: Boolean,
): List<String> =
    RESET_LINES
        .filter { it.developer == developer }
        .flatMap { it.lines(current, reset, modelName) }

/** The test tag of *Reset to the defaults*' button. */
internal const val RESET_TAG = "settings:reset"

/** The test tag of *Reset settings?*. */
internal const val RESET_DIALOG_TAG = "settings:resetDialog"

/** The test tag of *Reset settings?*'s *Reset*. */
internal const val RESET_CONFIRM_TAG = "settings:resetConfirm"

/** The test tag of *Include developer settings*. */
internal const val RESET_DEVELOPER_TAG = "settings:resetDeveloper"
