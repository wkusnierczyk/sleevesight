package app.sleevesight.app.ui.report

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
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
 * while the inputs are not enough to send (no reason chosen), and *Open in mail app* and *Copy
 * report* wait for it. *Open in mail app* hands the mail to [handoff]; a mail app taking it closes
 * the dialog, and the user sends it from there. With no mail app, the dialog says so. *Copy report*
 * is offered either way, for a mail app the hand-off does not reach; where the system confirms
 * the copy itself ([MailHandoff.confirmsCopy]), the dialog says only where to paste it.
 *
 * Its state is held only while it is open, not saved: closed by a rotation, it opens afresh.
 */
@Composable
internal fun ReportDialog(
    title: String,
    mail: Mail?,
    handoff: MailHandoff,
    onDismiss: () -> Unit,
    inputs: @Composable ColumnScope.() -> Unit,
) {
    var noMailApp by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
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
                Status(noMailApp, copied, handoff.confirmsCopy)
            }
        },
        confirmButton = {
            Action(R.string.report_send, mail, REPORT_SEND_TAG) {
                if (handoff.send(it)) onDismiss() else noMailApp = true
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(if (copied) R.string.report_close else R.string.report_cancel))
                }
                Action(R.string.report_copy, mail, REPORT_COPY_TAG) {
                    handoff.copy(it)
                    copied = true
                }
            }
        },
    )
}

// What became of the hand-off: no mail app took it, or it was copied. Said as it appears, without
// moving the focus from the button tapped.
@Composable
private fun Status(
    noMailApp: Boolean,
    copied: Boolean,
    systemConfirmsCopy: Boolean,
) {
    val said =
        when {
            copied -> if (systemConfirmsCopy) R.string.report_paste else R.string.report_copied
            noMailApp -> R.string.report_no_mail_app
            else -> return
        }
    Text(
        stringResource(said, Mail.SUPPORT),
        style = MaterialTheme.typography.bodyMedium,
        color = if (copied) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
