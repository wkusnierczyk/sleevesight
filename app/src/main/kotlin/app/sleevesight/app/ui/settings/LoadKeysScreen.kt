package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.SetupRead
import kotlinx.coroutines.launch

// *Load keys* (#268, #446): how to make a setup code, the camera to read it, what it holds before
// anything is saved, and then what was saved.

/** The page that makes a setup code (`api-keys.html`, #436), which *Load keys* points to. */
internal const val API_KEYS_URL = "https://sleevesight.app/api-keys"

/** What a setup code read offers to save: all it holds, only its good keys, or nothing. */
internal enum class SetupOffer { ALL, SOME, NONE }

/**
 * What [read] offers: [SetupOffer.ALL] when every line is a key that can be saved,
 * [SetupOffer.SOME] when some are and others are refused, unknown or unreadable, and
 * [SetupOffer.NONE] when none is, or the code is not a setup code, or one of a later version.
 */
internal fun offerOf(read: SetupRead): SetupOffer =
    when {
        read !is SetupRead.Setup || read.keys.isEmpty() -> SetupOffer.NONE
        read.refused.isEmpty() && read.unknown.isEmpty() && read.unreadable == 0 -> SetupOffer.ALL
        else -> SetupOffer.SOME
    }

/** Where *Load keys* is: the camera, what the code read holds, or what was saved. */
private sealed interface LoadStep {
    data object Scanning : LoadStep

    class Found(
        val read: SetupRead,
    ) : LoadStep

    class Saved(
        val keys: List<ShownKey>,
    ) : LoadStep
}

/**
 * *Load keys*, a screen of its own ([KeyScreen], secure). First, how to make a setup code (the
 * instructions at [API_KEYS_URL]), the camera's view ([scanner]) to read it, and *Cancel*
 * ([GuidedScan], as the key field's scans, #541). Once a code is read, its view gives way to what the code
 * holds, before anything is saved: each key in its service's shape, by its last four characters,
 * and whether it is new or replaces a key stored ([secrets]); then every problem: a key refused and
 * why, a name this version does not know, a line that could not be read, a code that is not a setup
 * code or is of a later version. All good offers *Save*; some keys good, *Save these* (those only)
 * and *Scan again*; nothing usable, *Scan again*; each with *Cancel*. [onSave] saves the keys and
 * returns those it could not: the screen then names them and saves only those on the next try, and
 * once none is left, it says which keys were saved, with *Done*.
 */
@Composable
internal fun LoadKeysScreen(
    secrets: Map<SecretId, SecretState>,
    scanner: QrScanner,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf<LoadStep>(LoadStep.Scanning) }
    var busy by remember { mutableStateOf(false) }
    // While scanning, the title is the guided scan's, inside what scrolls (#541).
    val title = R.string.settings_scan_keys.takeIf { step != LoadStep.Scanning }
    KeyScreen(title, busy, onDismiss) {
        when (val shown = step) {
            LoadStep.Scanning -> {
                GuidedScan(
                    title = R.string.settings_scan_keys,
                    make = stringResource(R.string.load_keys_make),
                    url = API_KEYS_URL,
                    linkDescription = R.string.load_keys_instructions_of,
                    read = stringResource(R.string.load_keys_read),
                    scanner = scanner,
                    onScanned = { step = LoadStep.Found(KeySetup.read(it)) },
                    onCancel = onDismiss,
                )
            }

            is LoadStep.Found -> {
                FoundStep(
                    read = shown.read,
                    secrets = secrets,
                    onSave = onSave,
                    onBusy = { busy = it },
                    onSaved = { step = LoadStep.Saved(it) },
                    onScanAgain = { step = LoadStep.Scanning },
                    onCancel = onDismiss,
                )
            }

            is LoadStep.Saved -> {
                SavedStep(shown.keys, onDismiss)
            }
        }
    }
}

/**
 * What the code [read] holds, and what can be done with it ([offerOf]). [onSave] saves the keys
 * given and returns those it could not; [onBusy] hears while it runs, when nothing else can be
 * done, and [onSaved] the keys saved once none is left.
 */
@Suppress("LongParameterList") // What the code holds, and what each button does.
@Composable
private fun ColumnScope.FoundStep(
    read: SetupRead,
    secrets: Map<SecretId, SecretState>,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onBusy: (Boolean) -> Unit,
    onSaved: (List<ShownKey>) -> Unit,
    onScanAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    val keys = remember(read) { (read as? SetupRead.Setup)?.keys?.mapKeys { SecretId.of(it.key) }.orEmpty() }
    var unsaved by remember(read) { mutableStateOf<List<SecretId>>(emptyList()) }
    // The keys stored when the code was read: a key it holds stays *new* once a first try saved it.
    val before = remember(read) { secrets }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val offer = offerOf(read)
    StepHeading(stringResource(foundTitle(read)), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        when (read) {
            SetupRead.NotASetup, SetupRead.Sealed -> Text(stringResource(R.string.setup_not_a_setup), style = style)
            is SetupRead.Unsupported -> Text(stringResource(R.string.setup_unsupported, read.version), style = style)
            is SetupRead.Setup -> SetupContents(read, keys, before, unsaved)
        }
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        if (offer != SetupOffer.ALL) {
            TextButton(onClick = onScanAgain, enabled = !busy) { Text(stringResource(R.string.setup_scan_again)) }
        }
        if (offer != SetupOffer.NONE) {
            val save = if (offer == SetupOffer.ALL) R.string.settings_save else R.string.load_keys_save_these
            TextButton(
                onClick = {
                    // A second tap in the same frame finds the button still enabled.
                    if (busy) return@TextButton
                    busy = true
                    onBusy(true)
                    scope.launch {
                        // After a failure, only the keys not yet saved are saved again.
                        val left = if (unsaved.isEmpty()) keys else keys.filterKeys { it in unsaved }
                        try {
                            unsaved = onSave(left)
                        } finally {
                            busy = false
                            onBusy(false)
                        }
                        if (unsaved.isEmpty()) onSaved(keys.map { (id, key) -> ShownKey(id, endingOf(key.reveal())) })
                    }
                },
                enabled = !busy,
            ) { Text(stringResource(save)) }
        }
    }
}

/** The heading over what the code [read] holds. */
@StringRes
private fun foundTitle(read: SetupRead): Int =
    when {
        read is SetupRead.NotASetup -> R.string.setup_not_a_setup_title
        read is SetupRead.Unsupported -> R.string.setup_unsupported_title
        offerOf(read) == SetupOffer.NONE -> R.string.load_keys_nothing_title
        else -> R.string.setup_title
    }

/**
 * What the setup code [setup] holds: its [keys] in their services' shape, each by its last four
 * characters and whether it replaces a key stored ([secrets]), with, for an AI service's key, where
 * its terms are told (#409); then the problems: the keys refused, the names unknown, the lines
 * unreadable; and the keys that could not be saved ([unsaved]).
 */
@Composable
private fun SetupContents(
    setup: SetupRead.Setup,
    keys: Map<SecretId, Secret>,
    secrets: Map<SecretId, SecretState>,
    unsaved: List<SecretId>,
) {
    val style = MaterialTheme.typography.bodyMedium
    if (keys.isNotEmpty()) {
        Text(stringResource(R.string.setup_explained), style = style)
        if (keys.keys.any { serviceTerms(it) != null }) Text(stringResource(R.string.setup_terms), style = style)
        StepHeading(stringResource(R.string.load_keys_found))
        KeyTable(
            keys.map { (id, key) ->
                val stored = (secrets[id] ?: SecretState.ABSENT) != SecretState.ABSENT
                KeyRow(ShownKey(id, endingOf(key.reveal())), replaces = stored)
            },
        )
    }
    if (setup.refused.isNotEmpty() || setup.unknown.isNotEmpty() || setup.unreadable > 0) {
        StepHeading(stringResource(R.string.load_keys_problems))
    }
    for ((name, problem) in setup.refused) {
        val service = stringResource(keyName(SecretId.of(name)))
        Text(stringResource(R.string.setup_refused, service, stringResource(refusal(problem))), style = style)
    }
    if (setup.unknown.isNotEmpty()) {
        Text(stringResource(R.string.setup_unknown, setup.unknown.joinToString()), style = style)
    }
    if (setup.unreadable > 0) {
        Text(pluralStringResource(R.plurals.setup_unreadable, setup.unreadable, setup.unreadable), style = style)
    }
    if (keys.isEmpty()) Text(stringResource(R.string.setup_nothing), style = style)
    if (unsaved.isNotEmpty()) {
        val names = unsaved.map { stringResource(keyName(it)) }.joinToString()
        Text(stringResource(R.string.setup_failed, names), style = style, color = MaterialTheme.colorScheme.error)
    }
}

/** The confirmation: which [keys] were saved. */
@Composable
private fun ColumnScope.SavedStep(
    keys: List<ShownKey>,
    onDone: () -> Unit,
) {
    StepHeading(stringResource(R.string.load_keys_saved_title), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        KeyTable(keys.map(::KeyRow))
        Text(stringResource(R.string.setup_explained), style = style)
    }
    KeyScreenActions {
        TextButton(onClick = onDone) { Text(stringResource(R.string.key_screen_done)) }
    }
}

/** Why a key in a setup code is not saved. */
@StringRes
private fun refusal(problem: KeyProblem): Int =
    when (problem) {
        KeyProblem.EMPTY -> R.string.setup_refused_empty
        KeyProblem.BAD_CHARACTERS -> R.string.setup_refused_characters
        KeyProblem.WRONG_SHAPE -> R.string.setup_refused_shape
        KeyProblem.DUPLICATE -> R.string.setup_refused_duplicate
    }
