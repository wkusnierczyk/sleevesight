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
import androidx.compose.runtime.derivedStateOf
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
import app.sleevesight.app.diagnostics.DiagnosticsLog
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.TesterPart
import app.sleevesight.app.report.TesterReport
import app.sleevesight.app.report.TesterSnapshot
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.DeveloperMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat

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
 *
 * Nothing can be sent or copied while a part kept is still being read, nor while the report is
 * longer than a mail app can be handed ([TesterReport.fits]); the dialog says why. A part longer
 * than [EDIT_LIMIT] when collected has no *Edit*, which would hold the whole text in one field:
 * it can be left out, or edited in the mail app. The mail is built again only when what it is
 * made of changes.
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
    val mail by remember(snapshot) {
        derivedStateOf {
            val kept =
                TesterPart.entries.mapNotNull { part ->
                    drafts
                        .getValue(part)
                        .takeIf { it.included }
                        ?.text
                        ?.let { part to it }
                }
            TesterReport.mail(snapshot, note, kept)
        }
    }
    val fits by remember(snapshot) { derivedStateOf { TesterReport.fits(mail) } }
    val reading = drafts.values.any { it.included && it.reading }
    val held =
        when {
            reading -> stringResource(R.string.tester_report_waits_for_history)
            !fits ->
                NumberFormat.getIntegerInstance().let { count ->
                    stringResource(
                        R.string.tester_report_too_long,
                        count.format(mail.asText().length),
                        count.format(TesterReport.MAX_CHARS),
                    )
                }
            else -> null
        }
    ReportDialog(
        title = stringResource(R.string.tester_report_title),
        mail = mail,
        handoff = handoff,
        onDismiss = onDismiss,
        previewHint = stringResource(R.string.tester_report_preview_hint),
        held = held,
    ) {
        TesterInputs(note, { note = it }, drafts) { draft, on -> include(draft, on, scope, readHistory) }
    }
}

/**
 * What the tester's review alone asks, above the preview: its introduction, the [note] (changed by
 * [onNote]), and a switch per part of [drafts] ([onInclude]). Only developer mode's, so marked as
 * such (#536).
 */
@Composable
private fun TesterInputs(
    note: String,
    onNote: (String) -> Unit,
    drafts: Map<TesterPart, PartDraft>,
    onInclude: (PartDraft, Boolean) -> Unit,
) {
    DeveloperMark(spacing = 8.dp) {
        Text(stringResource(R.string.tester_report_intro, Mail.SUPPORT), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = note,
            onValueChange = { onNote(it.take(NOTE_LIMIT)) },
            label = { Text(stringResource(R.string.tester_report_note)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(TESTER_NOTE_TAG),
        )
        Text(stringResource(R.string.tester_report_parts), style = MaterialTheme.typography.titleSmall)
        for (part in TesterPart.entries) {
            val draft = drafts.getValue(part)
            PartRow(part, draft) { on -> onInclude(draft, on) }
        }
    }
}

// Keeps [draft] in the report or leaves it out; kept for the first time with no text (the
// history), it is read then, once.
private fun include(
    draft: PartDraft,
    on: Boolean,
    scope: CoroutineScope,
    readHistory: suspend () -> String,
) {
    draft.included = on
    if (!on || draft.text != null || draft.reading) return
    draft.reading = true
    scope.launch {
        try {
            val read = readHistory()
            draft.editable = read.length <= EDIT_LIMIT
            draft.text = read
        } finally {
            draft.reading = false
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
    var editable by mutableStateOf(text == null || text.length <= EDIT_LIMIT)
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
            // With the turning record before it (#785).
            Busy(stringResource(R.string.tester_report_history_reading), MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (!draft.editable) {
        Text(
            stringResource(R.string.tester_report_too_long_to_edit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(testerTooLongTag(part)),
        )
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
        TesterPart.DIAGNOSTICS -> stringResource(R.string.tester_report_part_diagnostics, DiagnosticsLog.CAPACITY)
        TesterPart.HISTORY -> stringResource(R.string.tester_report_part_history, TesterReport.HISTORY_LIMIT)
    }

// A note longer than this is cut, as the insight report's is.
private const val NOTE_LIMIT = 2000

/**
 * The longest part, in characters as collected, that *Edit* opens: a few screenfuls. The
 * diagnostics' events and the history are often longer, and a text field holding all of them is
 * slow to type in.
 */
internal const val EDIT_LIMIT: Int = 10_000

// The lines a part's text shows while edited; it scrolls past them.
private const val EDIT_LINES = 12

/** The test tag of the tester's note. */
const val TESTER_NOTE_TAG: String = "tester_note"

/** The test tag of [part]'s switch row, which keeps it in the report or leaves it out. */
fun testerPartTag(part: TesterPart): String = "tester_part_${part.name}"

/** The test tag of [part]'s *Edit*. */
fun testerEditTag(part: TesterPart): String = "tester_edit_${part.name}"

/** The test tag of the line saying [part] is too long to edit here. */
fun testerTooLongTag(part: TesterPart): String = "tester_too_long_${part.name}"

/** The test tag of [part]'s text, open for editing. */
fun testerTextTag(part: TesterPart): String = "tester_text_${part.name}"
