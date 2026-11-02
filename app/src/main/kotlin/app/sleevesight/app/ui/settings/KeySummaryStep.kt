package app.sleevesight.app.ui.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.theme.appendCode
import app.sleevesight.app.ui.theme.codeStyle
import kotlinx.coroutines.launch

// The summary after keys are saved (#763), on *Load keys* and after the key dialog, and how a
// screen reader says the key tables' rows.

/**
 * The summary (#763), on *Load keys* and after the key dialog, of the [keys] saved, with the keys
 * stored before ([secrets]) and the choices written as [first] has them, or null when they could
 * not be: *Keys saved* (*Key saved* for one), a table of the keys, *Key*, *Ending* and *Saved*
 * (*New* or *Replaced*); then *Services configured*, a table of the services, *Service* and
 * *State* (*Enabled* or *Disabled*), as written ([summaryOf]); each by name. When the choices
 * could not be written, it says so in their place, with *Try again*, which writes them again
 * ([retry]); [onBusy] hears while it runs. *Done* calls [onDone].
 */
@Suppress("LongParameterList") // What was saved and written, and what the buttons do.
@Composable
internal fun ColumnScope.SummaryStep(
    keys: List<SavedKey>,
    secrets: Map<SecretId, SecretState>,
    first: Applied?,
    retry: suspend () -> Applied?,
    onBusy: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    var applied by remember { mutableStateOf(first) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val written = applied
    SummaryTables(keys, written?.let { summaryOf(keys, it.before, secrets, it.after) })
    KeyScreenActions(
        cancel =
            {
                if (written == null) {
                    TextButton(
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            onBusy(true)
                            scope.launch {
                                try {
                                    applied = retry()
                                } finally {
                                    busy = false
                                    onBusy(false)
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.load_keys_try_again)) }
                }
            },
    ) {
        TextButton(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.key_screen_done)) }
    }
}

/**
 * The summary's tables: the [keys] saved, and the services configured ([summary]), or, for null,
 * that the choices could not be written, in the error colour.
 */
@Suppress("LongMethod") // Its two tables.
@Composable
private fun ColumnScope.SummaryTables(
    keys: List<SavedKey>,
    summary: KeySummary?,
) {
    val title = if (keys.size == 1) R.string.load_keys_saved_title_one else R.string.load_keys_saved_title
    StepHeading(stringResource(title), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val body = MaterialTheme.typography.bodyMedium
        val code = codeStyle()
        val keyRows =
            keys
                .map { it to stringResource(serviceName(it.id)) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
                .map { (key, name) ->
                    val saved =
                        stringResource(if (key.replaced) R.string.key_saved_replaced else R.string.key_saved_new)
                    TableRow.Cells(
                        spoken = spokenParts(keyLine(R.string.key_ending, name, key.ending), saved),
                        cells =
                            listOf<@Composable () -> Unit>(
                                { Text(name, style = body) },
                                { Text(buildAnnotatedString { appendCode(key.ending, code) }, style = body) },
                                { Text(saved, style = body) },
                            ),
                    )
                }
        ChoiceTable(
            headers =
                listOf(
                    stringResource(R.string.key_table_key),
                    stringResource(R.string.key_table_ending),
                    stringResource(R.string.key_table_saved),
                ),
            aligns = listOf(CellAlign.START, CellAlign.END, CellAlign.START),
            rows = keyRows,
        )
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StepHeading(stringResource(R.string.load_keys_services_configured))
        }
        if (summary == null) {
            Text(
                stringResource(R.string.load_keys_services_failed),
                style = body,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        // Each service by its name, and whether it is on; an AI service that needs no key by its own.
        val named =
            summary.services.map { (id, on) -> stringResource(serviceName(id)) to on } +
                listOfNotNull(summary.keylessOff?.let { stringResource(aiServiceName(it)) to false })
        val serviceRows =
            named
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
                .map { (name, on) ->
                    val state =
                        stringResource(if (on) R.string.key_service_enabled else R.string.key_service_disabled)
                    TableRow.Cells(
                        spoken = AnnotatedString(parts(name, state)),
                        cells =
                            listOf<@Composable () -> Unit>(
                                { Text(name, style = body) },
                                { Text(state, style = body) },
                            ),
                    )
                }
        ChoiceTable(
            headers = listOf(stringResource(R.string.key_table_service), stringResource(R.string.key_table_state)),
            aligns = listOf(CellAlign.START, CellAlign.START),
            rows = serviceRows,
        )
    }
}

/** A row's two parts, as a screen reader says them: "Anthropic, AI". */
@Composable
internal fun parts(
    first: String,
    second: String,
): String = stringResource(R.string.key_row_parts, first, second)

/** [first], a key's line with its ending read character by character, then [second]. */
@Composable
internal fun spokenParts(
    first: AnnotatedString,
    second: String,
): AnnotatedString {
    val template = stringResource(R.string.key_row_parts, PART_MARK, second)
    val at = template.indexOf(PART_MARK)
    return buildAnnotatedString {
        append(template.substring(0, at))
        append(first)
        append(template.substring(at + PART_MARK.length))
    }
}

/** Where [spokenParts] puts its first part: a character no name has. */
private const val PART_MARK = "￼"
