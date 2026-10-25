package app.sleevesight.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.ResetResult
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import kotlinx.coroutines.launch

// *Purge keys* (#446): the saved keys it deletes, the deletion, and what it did.

/**
 * *Purge keys*, a screen of its own ([KeyScreen], secure): the saved keys it would delete
 * ([secrets]), each by its name and last four characters ([ending], which reads them), or as one
 * that can no longer be read; *Delete* and *Cancel*; and *More about safe delete*, which unfolds
 * what the deletion does. [onPurge] deletes every key with the phone's key for them, and says how
 * each step went and what is stored after them: when both succeeded, the screen says the keys listed
 * were deleted, with *Done*; otherwise it says which step failed and lists the keys left, from
 * that result alone ([purgeFailure]), with *Try again*. While it runs, nothing else can be done.
 */
@Composable
internal fun PurgeKeysScreen(
    secrets: Map<SecretId, SecretState>,
    ending: suspend (SecretId) -> String?,
    onPurge: suspend () -> ResetResult,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PurgeFailure?>(null) }
    // The keys listed when *Delete* was tapped: what the screen then says it deleted.
    var deleting by remember { mutableStateOf<List<ShownKey>?>(null) }
    var done by remember { mutableStateOf(false) }
    // Every key stored, as `SecretStore.reset` removes every one, in the order *Secrets* shows them.
    val stored by produceState<List<ShownKey>?>(null, secrets) { value = shownKeys(secrets, ending) }
    val scope = rememberCoroutineScope()
    KeyScreen(R.string.settings_remove_all_keys, busy, onDismiss) {
        if (done) {
            DeletedStep(deleting.orEmpty(), onDismiss)
        } else {
            // The keys as they are now: after a failure, those the deletion left.
            PurgeStep(stored, failure, busy, onDismiss) {
                // A second tap in the same frame finds the button still enabled.
                if (!busy) {
                    busy = true
                    failure = null
                    // What the confirmation lists: the keys as they were when *Delete* was first tapped.
                    val listed = deleting ?: stored.orEmpty()
                    deleting = listed
                    scope.launch {
                        try {
                            // Read nothing more: what is left comes from the reset and the keys listed.
                            val outcome = purgeFailure(onPurge(), listed)
                            if (outcome == null) done = true else failure = outcome
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}

/** The keys [states] holds, readable ([ending]) or not, in the order *Secrets* shows them. */
private suspend fun shownKeys(
    states: Map<SecretId, SecretState>,
    ending: suspend (SecretId) -> String?,
): List<ShownKey> =
    inShownOrder(states.filterValues { it != SecretState.ABSENT }.keys)
        .map { ShownKey(it, if (states[it] == SecretState.SAVED) ending(it) else null) }

/**
 * What *Purge keys* deletes, the [listed] keys stored now (null while they are read), and its
 * buttons: *Delete* calls [onDelete]. After a [failure], it says which step failed instead and
 * lists the keys left ([purgeFailure]); and *Try again* calls [onDelete]. Nothing can be tapped
 * while [busy].
 */
@Composable
private fun ColumnScope.PurgeStep(
    listed: List<ShownKey>?,
    failure: PurgeFailure?,
    busy: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        when {
            failure != null -> {
                Text(
                    stringResource(failure.message),
                    style = style,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                for (key in failure.left) Text(shownKey(key), style = style)
            }

            listed == null -> {}

            listed.isEmpty() -> {
                Text(stringResource(R.string.purge_keys_none), style = style)
            }

            else -> {
                Text(stringResource(R.string.purge_keys_listed), style = style)
                for (key in listed) Text(shownKey(key), style = style)
            }
        }
        MoreAboutSafeDelete()
    }
    KeyScreenActions {
        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) }
        TextButton(onClick = onDelete, enabled = !busy && listed != null) {
            Text(stringResource(if (failure != null) R.string.purge_keys_try_again else R.string.purge_keys_delete))
        }
    }
}

/** The confirmation: the [keys] deleted, or, with none listed, that the phone's key for them was. */
@Composable
private fun ColumnScope.DeletedStep(
    keys: List<ShownKey>,
    onDone: () -> Unit,
) {
    StepHeading(stringResource(R.string.purge_keys_done_title), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        if (keys.isEmpty()) {
            Text(stringResource(R.string.purge_keys_done_none), style = style)
        } else {
            Text(stringResource(R.string.purge_keys_done), style = style)
            for (key in keys) Text(shownKey(key), style = style)
        }
        MoreAboutSafeDelete()
    }
    KeyScreenActions {
        TextButton(onClick = onDone) { Text(stringResource(R.string.key_screen_done)) }
    }
}

/**
 * *More about safe delete*, folded under the fold's triangle (as the settings' sections are), and
 * unfolded, what *Purge keys* does. A screen reader reads it as a button with its state.
 */
@Composable
private fun MoreAboutSafeDelete() {
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.purge_keys_more)
    val state =
        stringResource(if (unfolded) R.string.settings_section_expanded else R.string.settings_section_collapsed)
    val triangle = stringResource(if (unfolded) R.string.home_fold_unfolded else R.string.home_fold_folded)
    Text(
        "$title $triangle",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .padding(top = 8.dp)
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button) { unfolded = !unfolded }
                .clearAndSetSemantics {
                    contentDescription = title
                    stateDescription = state
                    role = Role.Button
                    onClick {
                        unfolded = !unfolded
                        true
                    }
                },
    )
    if (unfolded) {
        Text(stringResource(R.string.purge_keys_more_explained), style = MaterialTheme.typography.bodyMedium)
    }
}
