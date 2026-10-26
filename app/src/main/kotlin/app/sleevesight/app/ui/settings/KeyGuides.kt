package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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

/** The least height of a guided scan's camera view, so a code can still be framed in a short window. */
internal val GUIDED_CAMERA_MIN_HEIGHT = 200.dp

/** The space between a guided scan's instructions, its camera view and the steps under it. */
private val GUIDED_GAP = 8.dp

/**
 * A guided scan's steps on a key screen: its [title] ([KeyScreenTitle]; the screen shows none of its
 * own), [intro], if any, then [make], a step's heading, with the
 * link to [url] under it, which a screen reader reads as [linkDescription] (`%1$s`, its address);
 * then [scanner]'s view; then [read], the step that reads the code, and [then], a step after it, if
 * any; and *Cancel*, which calls [onCancel], at the start of its line, as in the key dialog (#543).
 * [onScanned] hears the code read.
 *
 * The camera's view fills the height the steps leave, but never less than
 * [GUIDED_CAMERA_MIN_HEIGHT]: in a short window, in landscape or with large text, the title, the
 * steps and the view scroll instead ([CameraFill]), so the whole view can be scrolled onto the
 * screen, and only *Cancel* stays fixed under them.
 */
@Suppress("LongParameterList") // The steps, the link, and the camera.
@Composable
internal fun ColumnScope.GuidedScan(
    @StringRes title: Int,
    intro: String? = null,
    make: String,
    url: String,
    @StringRes linkDescription: Int,
    read: String,
    then: String? = null,
    scanner: QrScanner,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val space = maxHeight
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            CameraFill(space) {
                Column(verticalArrangement = Arrangement.spacedBy(GUIDED_GAP)) {
                    KeyScreenTitle(title)
                    intro?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    StepHeading(make)
                    val address = shownAddress(url)
                    ExternalLink(
                        text = address,
                        description = stringResource(linkDescription, address),
                        onClick = rememberOpenUrl(url),
                    )
                }
                Box(modifier = Modifier.fillMaxWidth()) { scanner(onScanned) }
                Column(verticalArrangement = Arrangement.spacedBy(GUIDED_GAP)) {
                    StepHeading(read)
                    then?.let { StepHeading(it) }
                }
            }
        }
    }
    KeyScreenActions(cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } })
}

/**
 * Lays out [content]'s three pieces, the instructions, the camera's view and the steps under it, one
 * above the other, [GUIDED_GAP] apart: the view as tall as the [space] the other two leave, and at
 * least [GUIDED_CAMERA_MIN_HEIGHT]. When that is more than [space], the whole is taller than it, for
 * the scroll around it to show.
 */
@Composable
private fun CameraFill(
    space: Dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val (above, camera, below) = measurables
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val top = above.measure(loose)
        val bottom = below.measure(loose)
        val gap = GUIDED_GAP.roundToPx()
        val left = space.roundToPx() - top.height - bottom.height - 2 * gap
        val height = maxOf(GUIDED_CAMERA_MIN_HEIGHT.roundToPx(), left)
        val view = camera.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(constraints.maxWidth, top.height + gap + height + gap + bottom.height) {
            top.place(0, 0)
            view.place(0, top.height + gap)
            bottom.place(0, top.height + gap + height + gap)
        }
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
            KeyScreen(title = null, busy = false, onDismiss) {
                GuidedScan(
                    title = R.string.settings_key_scan,
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
            KeyScreen(title = null, busy = false, onDismiss) {
                GuidedScan(
                    title = R.string.settings_key_type,
                    intro = stringResource(R.string.keyboard_explained),
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
