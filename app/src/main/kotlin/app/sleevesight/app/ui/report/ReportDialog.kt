package app.sleevesight.app.ui.report

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.report.Mail
import app.sleevesight.app.report.MailHandoff

/**
 * A report composed in the app and handed to the user's mail app (#390), the one dialog every
 * report goes through (the tester's report to come, #452, too): [inputs] for what the user adds,
 * then the whole [mail] as it will be sent, so nothing leaves the phone unseen. [mail] is null
 * while the inputs are not enough to send (no reason chosen), and *Open in mail app* waits for it.
 * It hands the mail to [handoff]; a mail app taking it closes the dialog, and the user sends it
 * from there. With no mail app, the dialog says so and offers to copy the report instead.
 */
@Composable
internal fun ReportDialog(
    title: String,
    mail: Mail?,
    handoff: MailHandoff,
    onDismiss: () -> Unit,
    inputs: @Composable ColumnScope.() -> Unit,
) {
    var noMailApp by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(REPORT_DIALOG_TAG),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                inputs()
                Text(stringResource(R.string.report_preview_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.report_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Preview(mail)
                if (noMailApp) {
                    // Said as it appears, without moving the focus from the button tapped.
                    val said = if (copied) R.string.report_copied else R.string.report_no_mail_app
                    Text(
                        stringResource(said, Mail.SUPPORT),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (copied) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            if (noMailApp) {
                Action(R.string.report_copy, mail, REPORT_COPY_TAG) {
                    handoff.copy(it)
                    copied = true
                }
            } else {
                Action(R.string.report_send, mail, REPORT_SEND_TAG) {
                    if (handoff.send(it)) onDismiss() else noMailApp = true
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (copied) R.string.report_close else R.string.report_cancel))
            }
        },
    )
}

// *Open in mail app* or *Copy report*: enabled once there is a [mail] to act on.
@Composable
private fun Action(
    @StringRes label: Int,
    mail: Mail?,
    tag: String,
    onClick: (Mail) -> Unit,
) {
    TextButton(
        onClick = { mail?.let(onClick) },
        enabled = mail != null,
        modifier = Modifier.testTag(tag),
    ) { Text(stringResource(label)) }
}

// The mail as it will be sent: the address, the subject and the body, in a monospaced block.
@Composable
private fun Preview(mail: Mail?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag(REPORT_PREVIEW_TAG),
    ) {
        Text(
            mail?.asText() ?: stringResource(R.string.report_preview_waits),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** The test tag of the report dialog. */
const val REPORT_DIALOG_TAG: String = "report_dialog"

/** The test tag of *Open in mail app*. */
const val REPORT_SEND_TAG: String = "report_send"

/** The test tag of *Copy report*, offered when no mail app takes the report. */
const val REPORT_COPY_TAG: String = "report_copy"

/** The test tag of the report as it will be sent. */
const val REPORT_PREVIEW_TAG: String = "report_preview"
