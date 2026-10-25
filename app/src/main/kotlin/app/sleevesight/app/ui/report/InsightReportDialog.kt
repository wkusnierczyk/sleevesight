package app.sleevesight.app.ui.report

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff
import app.sleevesight.app.report.ReportReason
import app.sleevesight.app.report.insightReport
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState

/**
 * *Report a problem* for [insight], written for [track] (#390): a reason, one of three, and an
 * optional note, then the report as it will be sent ([insightReport]), through [ReportDialog].
 * Both are as they were when the dialog opened, whatever plays meanwhile.
 */
@Composable
internal fun InsightReportDialog(
    track: TrackState,
    insight: InsightState.Written,
    handoff: MailHandoff,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    val mail = reason?.let { insightReport(track, insight, it, note) }
    ReportDialog(stringResource(R.string.report_title), mail, handoff, onDismiss) {
        Text(stringResource(R.string.report_intro, Mail.SUPPORT), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.report_reason), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            for (option in ReportReason.entries) {
                ReasonRow(option, selected = option == reason) { reason = option }
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(NOTE_LIMIT) },
            label = { Text(stringResource(R.string.report_note)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(REPORT_NOTE_TAG),
        )
    }
}

@Composable
private fun ReasonRow(
    reason: ReportReason,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            stringResource(reasonLabel(reason)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun reasonLabel(reason: ReportReason): Int =
    when (reason) {
        ReportReason.OFFENSIVE -> R.string.report_reason_offensive
        ReportReason.WRONG -> R.string.report_reason_wrong
        ReportReason.OTHER -> R.string.report_reason_other
    }

// A note longer than this is cut: a report is a few lines, and the mail app takes it in its address.
private const val NOTE_LIMIT = 2000

/** *Report a problem*: a small text action, which a screen reader names with the insight it is for. */
@Composable
internal fun ReportAction(onReport: () -> Unit) {
    val description = stringResource(R.string.report_action_description)
    TextButton(
        onClick = onReport,
        modifier = Modifier.testTag(REPORT_TAG).semantics { contentDescription = description },
    ) {
        Text(stringResource(R.string.report_action), style = MaterialTheme.typography.labelLarge)
    }
}

/** The test tag of *Report a problem*. */
const val REPORT_TAG: String = "report"

/** The test tag of the report's note. */
const val REPORT_NOTE_TAG: String = "report_note"
