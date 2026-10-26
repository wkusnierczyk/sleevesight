package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.KEYBOARD_PAGE_URL
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.shownAddress

// The guided scans (#541): every QR code the key settings read, a setup code (*Load keys*), one
// key (the key field's *Scan QR code*) or a keyboard session's code (*Type on a computer*), is read
// on a screen laid out the same way: what to do first, with a link to where, the camera's view,
// what to do with it, and *Cancel*.

/**
 * A guided scan's steps on a key screen: [make], a step's heading, with the link to [url] under it,
 * which a screen reader reads as [linkDescription] (`%1$s`, its address); then [scanner]'s view,
 * filling the space left; then [read], the step that reads the code, and [then], a step after it,
 * if any; and *Cancel*, which calls [onCancel]. [onScanned] hears the code read.
 */
@Suppress("LongParameterList") // The steps, the link, and the camera.
@Composable
internal fun ColumnScope.GuidedScan(
    make: String,
    url: String,
    @StringRes linkDescription: Int,
    read: String,
    then: String? = null,
    scanner: QrScanner,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    StepHeading(make)
    val address = shownAddress(url)
    ExternalLink(
        text = address,
        description = stringResource(linkDescription, address),
        onClick = rememberOpenUrl(url),
    )
    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) { scanner(onScanned) }
    StepHeading(read)
    then?.let { StepHeading(it) }
    KeyScreenActions {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) }
    }
}

/**
 * What the key field's icons open (#541): a key screen ([KeyScreen], secure, as the key dialog
 * is) that reads a QR code [for][purpose] the key, guided as *Load keys* is ([GuidedScan]). *Scan
 * QR code*: a code of this one key, made at [API_KEYS_URL]. *Type on a computer*: what it is, the
 * keyboard page at [KEYBOARD_PAGE_URL], reading its code, and choosing the phone on it. [onScanned]
 * hears the code read; Back and *Cancel* call [onDismiss].
 */
@Composable
internal fun KeyScanScreen(
    purpose: ScanFor,
    scanner: QrScanner,
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    when (purpose) {
        ScanFor.KEY -> {
            KeyScreen(R.string.settings_key_scan, busy = false, onDismiss) {
                GuidedScan(
                    make = stringResource(R.string.key_scan_make),
                    url = API_KEYS_URL,
                    linkDescription = R.string.load_keys_instructions_of,
                    read = stringResource(R.string.load_keys_read),
                    scanner = scanner,
                    onScanned = onScanned,
                    onCancel = onDismiss,
                )
            }
        }

        ScanFor.KEYBOARD -> {
            KeyScreen(R.string.settings_key_type, busy = false, onDismiss) {
                Text(stringResource(R.string.keyboard_explained), style = MaterialTheme.typography.bodyMedium)
                GuidedScan(
                    make = stringResource(R.string.keyboard_open),
                    url = KEYBOARD_PAGE_URL,
                    linkDescription = R.string.keyboard_page_of,
                    read = stringResource(R.string.keyboard_scan),
                    then = stringResource(R.string.keyboard_connect),
                    scanner = scanner,
                    onScanned = onScanned,
                    onCancel = onDismiss,
                )
            }
        }
    }
}
