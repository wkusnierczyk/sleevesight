package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.appendCode
import app.sleevesight.app.ui.theme.codeStyle
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.SetupRead
import kotlinx.coroutines.launch

// *Load keys* once a setup code is read (#763): the keys to save, the services to enable, and the
// summary, which the key dialog shows too.

/** A key on the keys to save: [id], its [key], and whether it [replaces] one saved. */
private class ReadKey(
    val id: SecretId,
    val key: Secret,
    val replaces: Boolean,
)

/**
 * The keys a setup code [setup] holds, to save: a heading that asks (for several, one, or none), a
 * table of them, each ticked, by group and name, with whether it is new or replaces one saved
 * ([secrets], as when the code was read); under it, that only the ones ticked are saved, and the
 * keys that did not scan, in the error colour. An AI key's terms are told where their key dialog
 * says, and a code not [sealed] can be read by anyone who saw it (#590). *Next* saves the keys
 * ticked ([onSave], which returns those it could not save: the screen names them, and *Next* saves
 * only those ticked and not yet saved), dimmed while none is ticked and none saved; once every
 * key ticked is saved, [onSaved] hears the keys saved and the keys stored before: after a failure,
 * unticking the keys that failed goes on with those saved. *Scan again* ([onScanAgain]) only when a key did not
 * scan; *Cancel* ([onCancel]) saves nothing. [onBusy] hears while a save runs.
 */
@Suppress("LongParameterList", "LongMethod") // What the code holds, and what each button does.
@Composable
internal fun ColumnScope.KeysStep(
    setup: SetupRead.Setup,
    sealed: Boolean,
    secrets: Map<SecretId, SecretState>,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onBusy: (Boolean) -> Unit,
    onSaved: (List<SavedKey>, Map<SecretId, SecretState>) -> Unit,
    onScanAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    // The keys stored when the code was read: a key it holds stays *new* once a first try saved it.
    val before = remember(setup) { secrets }
    val keys =
        remember(setup) {
            setup.keys.map { (name, key) ->
                val id = SecretId.of(name)
                ReadKey(id, key, replaces = (before[id] ?: SecretState.ABSENT) != SecretState.ABSENT)
            }
        }
    var ticked by remember(setup) { mutableStateOf(keys.map { it.id }.toSet()) }
    var saved by remember(setup) { mutableStateOf(emptySet<SecretId>()) }
    var unsaved by remember(setup) { mutableStateOf(emptyList<SecretId>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notScanned = setup.refused.isNotEmpty() || setup.unknown.isNotEmpty() || setup.unreadable > 0
    StepHeading(stringResource(saveQuestion(keys.size)), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeysRead(setup, keys, ticked, unsaved, sealed, enabled = !busy) { id, on ->
            ticked = if (on) ticked + id else ticked - id
        }
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        if (notScanned) {
            TextButton(onClick = onScanAgain, enabled = !busy) { Text(stringResource(R.string.setup_scan_again)) }
        }
        if (keys.isNotEmpty()) {
            TextButton(
                onClick = {
                    // A second tap in the same frame finds the button still enabled.
                    if (busy) return@TextButton
                    busy = true
                    onBusy(true)
                    scope.launch {
                        // After a failure, the keys saved on the first try are not saved again; with
                        // none left ticked to save, it goes on with those.
                        val left = keys.filter { it.id in ticked && it.id !in saved }
                        try {
                            unsaved = if (left.isEmpty()) emptyList() else onSave(left.associate { it.id to it.key })
                            saved = saved + left.map { it.id }.filterNot { it in unsaved }
                        } finally {
                            busy = false
                            onBusy(false)
                        }
                        if (unsaved.isEmpty()) {
                            val done = keys.filter { it.id in saved }
                            onSaved(done.map { SavedKey(it.id, endingOf(it.key.reveal()), it.replaces) }, before)
                        }
                    }
                },
                enabled = !busy && (saved.isNotEmpty() || keys.any { it.id in ticked }),
            ) { Text(stringResource(R.string.load_keys_next)) }
        }
    }
}

/** The heading over [count] keys to save: several, one, or none. */
@StringRes
private fun saveQuestion(count: Int): Int =
    when (count) {
        0 -> R.string.load_keys_save_none
        1 -> R.string.load_keys_save_question_one
        else -> R.string.load_keys_save_question
    }

/**
 * What the code [setup] holds, under the heading: its [keys] to save ([ticked], [onTick] hearing a
 * tap, only while [enabled]: not while a save runs), the keys that did not scan, those that could
 * not be saved ([unsaved]), how the keys are stored, an AI key's terms note, and, for a code not
 * [sealed], that anyone who saw it can read them.
 */
@Suppress("LongParameterList") // What the step shows, and what a tap does.
@Composable
private fun KeysRead(
    setup: SetupRead.Setup,
    keys: List<ReadKey>,
    ticked: Set<SecretId>,
    unsaved: List<SecretId>,
    sealed: Boolean,
    enabled: Boolean,
    onTick: (SecretId, Boolean) -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium
    if (keys.isNotEmpty()) {
        KeysToSave(keys, ticked, enabled, onTick)
        Text(stringResource(R.string.load_keys_only_selected), style = style)
        Text(stringResource(R.string.setup_explained), style = style)
    }
    if (setup.refused.isNotEmpty() || setup.unknown.isNotEmpty() || setup.unreadable > 0) NotScanned(setup)
    if (unsaved.isNotEmpty()) {
        val names = unsaved.map { stringResource(keyName(it)) }.joinToString()
        Text(stringResource(R.string.setup_failed, names), style = style, color = MaterialTheme.colorScheme.error)
    }
    if (keys.any { serviceTerms(it.id) != null }) Text(stringResource(R.string.setup_terms), style = style)
    if (keys.isNotEmpty() && !sealed) Text(stringResource(R.string.load_keys_clear_code), style = style)
}

/**
 * The table of the [keys] to save, by group, then name: *Key* (the key's service, with *Save as
 * new* or *Replace existing* under it), *Group*, *Ending* and *Save*, its checkbox, [ticked] or
 * not; [onTick] hears a tap while [enabled].
 */
@Composable
private fun KeysToSave(
    keys: List<ReadKey>,
    ticked: Set<SecretId>,
    enabled: Boolean,
    onTick: (SecretId, Boolean) -> Unit,
) {
    val body = MaterialTheme.typography.bodyMedium
    val small = MaterialTheme.typography.bodySmall
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val code = codeStyle()
    val rows =
        sortedByGroup(keys, { it.id }, { stringResource(serviceName(it.id)) }).map { key ->
            val name = stringResource(serviceName(key.id))
            val group = stringResource(groupOf(key.id).label)
            val note = stringResource(if (key.replaces) R.string.key_replace_existing else R.string.key_save_as_new)
            val ending = endingOf(key.key.reveal())
            val choice = RowChoice(key.id in ticked, radio = false, enabled = enabled) { onTick(key.id, it) }
            TableRow.Cells(
                spoken = spokenParts(keyLine(R.string.key_ending, parts(name, group), ending), note),
                cells =
                    listOf<@Composable () -> Unit>(
                        {
                            Text(name, style = body)
                            Text(note, style = small, color = quiet)
                        },
                        { Text(group, style = body, color = quiet) },
                        { Text(buildAnnotatedString { appendCode(ending, code) }, style = body) },
                        { ChoiceCell(choice) },
                    ),
                choice = choice,
            )
        }
    ChoiceTable(
        headers =
            listOf(
                stringResource(R.string.key_table_key),
                stringResource(R.string.key_table_group),
                stringResource(R.string.key_table_ending),
                stringResource(R.string.key_table_save),
            ),
        aligns = listOf(CellAlign.START, CellAlign.START, CellAlign.END, CellAlign.CENTER),
        rows = rows,
    )
}

/**
 * The keys of [setup] that did not scan, in the error colour: each refused key by its name and
 * ending, or its name alone when it has none, and the names this version does not know, with the
 * lines that could not be read, as *n unknown keys*.
 */
@Composable
private fun NotScanned(setup: SetupRead.Setup) {
    val style = MaterialTheme.typography.bodyMedium
    val error = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.load_keys_not_scanned), style = style, color = error)
        for (name in setup.refused.keys) {
            val service = stringResource(serviceName(SecretId.of(name)))
            val ending = setup.refusedEndings[name]
            val entry = if (ending == null) AnnotatedString(service) else keyLine(R.string.key_ending, service, ending)
            Text(bullet(entry), style = style, color = error, modifier = Modifier.padding(start = 8.dp))
        }
        val unknown = setup.unknown.size + setup.unreadable
        if (unknown > 0) {
            Text(
                bullet(AnnotatedString(pluralStringResource(R.plurals.load_keys_unknown_keys, unknown, unknown))),
                style = style,
                color = error,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** [entry] as an item of a list, after a bullet. */
private fun bullet(entry: AnnotatedString): AnnotatedString =
    buildAnnotatedString {
        append("• ")
        append(entry)
    }

/**
 * The services to enable once keys are saved, as [plan] has them: a table, *Key*, *Group* and
 * *Enable*, of the services whose keys were saved, by group and name, each with its checkbox, or,
 * for an AI service where more than one can be chosen, its radio; under *Already configured*, the
 * AI services whose keys were saved before. Under it, the AI service on, if it is one of those;
 * that only one AI service can be on, with radios; and where the services are, each a link
 * ([onOpen], which leaves *Load keys*). *Next* writes the choices ([onNext]); *Cancel*
 * ([onCancel]) changes nothing, the keys staying saved.
 */
@Suppress("LongMethod") // The table, the lines under it, and the buttons.
@Composable
internal fun ColumnScope.EnableStep(
    plan: EnablePlan,
    onOpen: (SettingsTarget) -> Unit,
    onNext: suspend (Map<SecretId, Boolean>) -> Unit,
    onCancel: () -> Unit,
) {
    var choices by remember(plan) { mutableStateOf(plan.initial) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    StepHeading(stringResource(R.string.load_keys_enable_question), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodySmall
        val quiet = MaterialTheme.colorScheme.onSurfaceVariant
        val row = @Composable { id: SecretId ->
            enableRow(id, plan, choices, enabled = !busy) { on -> choices = choices.chosen(id, on) }
        }
        val rows =
            sortedByGroup(plan.saved, { it }, { stringResource(serviceName(it)) }).map { row(it) } +
                if (plan.configured.isEmpty()) {
                    emptyList()
                } else {
                    listOf(TableRow.Across(stringResource(R.string.load_keys_configured))) +
                        sortedByGroup(plan.configured, { it }, { stringResource(serviceName(it)) }).map { row(it) }
                }
        ChoiceTable(
            headers =
                listOf(
                    stringResource(R.string.key_table_key),
                    stringResource(R.string.key_table_group),
                    stringResource(R.string.key_table_enable),
                ),
            aligns = listOf(CellAlign.START, CellAlign.START, CellAlign.CENTER),
            rows = rows,
        )
        plan.current?.let { current ->
            Text(
                stringResource(R.string.key_current_ai, stringResource(aiServiceName(current))),
                style = style,
                color = quiet,
            )
        }
        if (plan.radios) Text(stringResource(R.string.load_keys_one_ai), style = style, color = quiet)
        val sources = stringResource(R.string.settings_section_sources)
        val genres = stringResource(R.string.settings_section_labels)
        val ai = stringResource(R.string.settings_section_ai)
        LinkedText(
            linkedParts(
                stringResource(R.string.load_keys_where),
                TextPart.Action(sources, SettingsTarget.sources.linkTag) { onOpen(SettingsTarget.sources) },
                TextPart.Action(genres, SettingsTarget.genres.linkTag) { onOpen(SettingsTarget.genres) },
                TextPart.Action(ai, SettingsTarget.aiServices.linkTag) { onOpen(SettingsTarget.aiServices) },
            ),
            style = style,
            color = quiet,
        )
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        TextButton(
            onClick = {
                // A second tap in the same frame finds the button still enabled.
                if (busy) return@TextButton
                busy = true
                scope.launch {
                    try {
                        onNext(choices)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
        ) { Text(stringResource(R.string.load_keys_next)) }
    }
}

/**
 * The services' table's row for the key [id]'s service, as [choices] has it; [onChange] hears a
 * tap while [enabled].
 */
@Composable
private fun enableRow(
    id: SecretId,
    plan: EnablePlan,
    choices: Map<SecretId, Boolean>,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
): TableRow.Cells {
    val name = stringResource(serviceName(id))
    val group = stringResource(groupOf(id).label)
    val radio = plan.radios && groupOf(id) == KeyGroup.AI
    val choice = RowChoice(choices[id] == true, radio = radio, enabled = enabled, onChange = onChange)
    return TableRow.Cells(
        spoken = AnnotatedString(parts(name, group)),
        cells =
            listOf<@Composable () -> Unit>(
                { Text(name, style = MaterialTheme.typography.bodyMedium) },
                {
                    Text(
                        group,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                { ChoiceCell(choice) },
            ),
        choice = choice,
    )
}

/** [items] by their key's group ([groupOf], by [id]), then by [name]. */
@Composable
private fun <T> sortedByGroup(
    items: List<T>,
    id: (T) -> SecretId,
    name: @Composable (T) -> String,
): List<T> {
    val named = items.map { it to name(it) }
    return named
        .sortedWith(
            compareBy<Pair<T, String>> {
                groupOf(id(it.first)).ordinal
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.second },
        ).map { it.first }
}
