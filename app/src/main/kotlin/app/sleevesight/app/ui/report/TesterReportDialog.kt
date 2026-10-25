package app.sleevesight.app.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.TesterPart
import app.sleevesight.app.report.TesterReport
import app.sleevesight.app.report.TesterSnapshot
import kotlinx.coroutines.launch

/**
 * The review of a tester's report (#452), through [ReportDialog], the path every report takes: what
 * it holds and that nothing is sent until the tester sends it; a note on what they expected; each
 * part ([TesterPart]) with a switch that keeps it in or leaves it out, and *Edit*, which opens its
 * text for editing; then the whole mail as it will be sent, which only the tester hands to their
 * mail app. The history is left out until the tester turns it on, and only then read
 * ([readHistory]), as of the tap.
 *
 * Everything is as it was at the tap ([snapshot]), whatever happens meanwhile. Held only while the
 * dialog is open, not saved: closed by a rotation, a report starts afresh from a new tap.
 */
@Composable
internal fun TesterReportDialog(
    snapshot: TesterSnapshot,
    readHistory: suspend () -> String,
    handoff: MailHandoff,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val drafts =
        remember(snapshot) { TesterPart.entries.associateWith { PartDraft(TesterReport.part(it, snapshot), it) } }
    val scope = rememberCoroutineScope()
    val kept =
        TesterPart.entries.mapNotNull { part ->
            drafts
                .getValue(part)
                .takeIf { it.included }
                ?.text
                ?.let { part to it }
        }
    val mail = TesterReport.mail(snapshot, note, kept)
    ReportDialog(
        title = stringResource(R.string.tester_report_title),
        mail = mail,
        handoff = handoff,
        onDismiss = onDismiss,
        previewHint = stringResource(R.string.tester_report_preview_hint),
    ) {
        Text(stringResource(R.string.tester_report_intro, Mail.SUPPORT), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(NOTE_LIMIT) },
            label = { Text(stringResource(R.string.tester_report_note)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(TESTER_NOTE_TAG),
        )
        Text(stringResource(R.string.tester_report_parts), style = MaterialTheme.typography.titleSmall)
        for (part in TesterPart.entries) {
            val draft = drafts.getValue(part)
            PartRow(part, draft) { on ->
                draft.included = on
                if (on && draft.text == null && !draft.reading) {
                    draft.reading = true
                    scope.launch {
                        try {
                            draft.text = readHistory()
                        } finally {
                            draft.reading = false
                        }
                    }
                }
            }
        }
    }
}

// One part's state in the review: its text as the tester left it (null until read, for the
// history), whether it is kept, whether its text is open for editing, and whether it is being read.
private class PartDraft(
    text: String?,
    part: TesterPart,
) {
    var text by mutableStateOf(text)
    var included by mutableStateOf(part.includedAtFirst)
    var editing by mutableStateOf(false)
    var reading by mutableStateOf(false)
}

@Composable
private fun PartRow(
    part: TesterPart,
    draft: PartDraft,
    onInclude: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = draft.included, role = Role.Switch, onValueChange = onInclude)
                    .testTag(testerPartTag(part))
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(part.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summary(part),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.included, onCheckedChange = null)
        }
        if (draft.included) PartText(part, draft)
    }
}

// The part's text, behind *Edit*; for the history, while it is read, that it is.
@Composable
private fun PartText(
    part: TesterPart,
    draft: PartDraft,
) {
    val text = draft.text
    if (text == null) {
        if (draft.reading) {
            Text(stringResource(R.string.tester_report_history_reading), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val description = stringResource(R.string.tester_report_edit_description, part.label)
    TextButton(
        onClick = { draft.editing = !draft.editing },
        modifier =
            Modifier.testTag(testerEditTag(part)).semantics {
                if (!draft.editing) contentDescription = description
            },
    ) {
        Text(stringResource(if (draft.editing) R.string.tester_report_edit_done else R.string.tester_report_edit))
    }
    if (draft.editing) {
        OutlinedTextField(
            value = text,
            onValueChange = { draft.text = it },
            label = { Text(part.label) },
            maxLines = EDIT_LINES,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().testTag(testerTextTag(part)),
        )
    }
}

@Composable
private fun summary(part: TesterPart): String =
    when (part) {
        TesterPart.APP -> stringResource(R.string.tester_report_part_app)
        TesterPart.SETTINGS -> stringResource(R.string.tester_report_part_settings)
        TesterPart.NOW_PLAYING -> stringResource(R.string.tester_report_part_now_playing)
        TesterPart.DIAGNOSTICS -> stringResource(R.string.tester_report_part_diagnostics)
        TesterPart.HISTORY -> stringResource(R.string.tester_report_part_history, TesterReport.HISTORY_LIMIT)
    }

// A note longer than this is cut, as the insight report's is.
private const val NOTE_LIMIT = 2000

// The lines a part's text shows while edited; it scrolls past them.
private const val EDIT_LINES = 12

/** The test tag of the tester's note. */
const val TESTER_NOTE_TAG: String = "tester_note"

/** The test tag of [part]'s switch row, which keeps it in the report or leaves it out. */
fun testerPartTag(part: TesterPart): String = "tester_part_${part.name}"

/** The test tag of [part]'s *Edit*. */
fun testerEditTag(part: TesterPart): String = "tester_edit_${part.name}"

/** The test tag of [part]'s text, open for editing. */
fun testerTextTag(part: TesterPart): String = "tester_text_${part.name}"
