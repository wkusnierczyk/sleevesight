package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.settings.Resets
import app.sleevesight.app.settings.SettingsReset
import app.sleevesight.app.ui.theme.LocalMessages
import app.sleevesight.core.settings.Settings

// *Save and reset* (#960): *Reset to the defaults*, and its dialog. *Save to a file* and *Load from a
// file* (#815) join the section's rows here.

/**
 * *Save and reset*'s rows: *Reset to the defaults*, whose button opens [ResetDialog] for
 * [settings]; once the reset is saved, [ResetNotice] says so. [modelName] names a
 * model as its row does.
 */
@Composable
internal fun SaveAndResetSection(
    settings: Settings,
    resetter: SettingsResetter,
    modelName: (String) -> String,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    ButtonRow(
        title = stringResource(R.string.settings_reset),
        summary = stringResource(R.string.settings_reset_summary),
        label = stringResource(R.string.settings_reset_button),
        tag = RESET_TAG,
        onClick = { asking = true },
    )
    if (asking) {
        ResetDialog(
            changes = resetChanges(settings, resetter.preview(settings), modelName),
            stays = resetStays(settings, resetter.preview(settings)),
            onReset = {
                asking = false
                // Its message comes from [ResetNotice], which outlives a recreation.
                resetter.reset()
            },
            onDismiss = { asking = false },
        )
    }
}

/**
 * Says *Settings reset to the defaults.* ([LocalMessages]) once the last reset asked for ([resets])
 * is saved, once only: on this screen, or on the one made after a recreation, as when the reset
 * changed the theme and Android restarted the activity.
 */
@Composable
internal fun ResetNotice(resets: Resets) {
    val pending by resets.pending.collectAsState()
    val messages = LocalMessages.current
    val done = stringResource(R.string.settings_reset_done)
    LaunchedEffect(pending, messages) {
        val reset = pending ?: return@LaunchedEffect
        if (messages != null && reset.saved.await() && resets.take(reset)) messages.show(done)
    }
}

/** *Save and reset*'s rows as the settings' search finds them. */
@Composable
internal fun saveAndResetRows(): List<IndexedRow> =
    listOf(indexRow(stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_summary)))

/**
 * *Reset settings?* (#960): [changes], the settings that go back to their defaults, each as its
 * value now and its default, or, with none, that every setting is at its default already; then
 * [stays], what the reset keeps. *Reset* calls [onReset], and is disabled while nothing would change;
 * *Cancel*, or a tap outside, [onDismiss].
 */
@Composable
internal fun ResetDialog(
    changes: List<String>,
    stays: List<String>,
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = onReset,
                enabled = changes.isNotEmpty(),
                modifier = Modifier.testTag(RESET_CONFIRM_TAG),
            ) { Text(stringResource(R.string.settings_reset_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
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
 * *Keep history for* where a reset to [reset] keeps it off its default.
 */
@Composable
internal fun resetStays(
    current: Settings,
    reset: Settings,
): List<String> {
    val history = stringResource(R.string.settings_keep_history)
    val historyKept =
        current.historyLifetime != Settings.DEFAULT_HISTORY_LIFETIME && reset.historyLifetime == current.historyLifetime
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
 * default*, in the order the settings show them ([RESET_LINES]).
 */
@Composable
internal fun resetChanges(
    current: Settings,
    reset: Settings,
    modelName: (String) -> String,
): List<String> = RESET_LINES.flatMap { it.lines(current, reset, modelName) }

/** The test tag of *Reset to the defaults*' button. */
internal const val RESET_TAG = "settings:reset"

/** The test tag of *Reset settings?*. */
internal const val RESET_DIALOG_TAG = "settings:resetDialog"

/** The test tag of *Reset settings?*'s *Reset*. */
internal const val RESET_CONFIRM_TAG = "settings:resetConfirm"
