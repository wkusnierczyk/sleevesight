package app.sleevesight.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
// on a screen laid out the same way: what to do first, the camera's view, what to do with it, and
// *Cancel*. *Load keys*' words and intro share the layout, their words or panel in the camera's
// place (#765).

/** The least height of a guided scan's camera view, so a code can still be framed in a short window. */
internal val GUIDED_CAMERA_MIN_HEIGHT = 200.dp

/** The space between a guided scan's instructions, its camera view and the steps under it. */
private val GUIDED_GAP = 8.dp

/**
 * A key screen's content in three parts, one above the other, [GUIDED_GAP] apart, scrolling as one:
 * [above], then [middle], then [below]. [middle] fills the height the other two leave, its content
 * centred in it: never less than [least], when given (a camera's view, [GuidedScan]), or else than
 * its own height (*Load keys*' words and intro, #765). In a short window, in landscape or with large
 * text, the whole scrolls instead ([MiddleFill]), so [middle] can be scrolled wholly onto the screen.
 */
@Composable
internal fun ColumnScope.FilledMiddle(
    least: Dp?,
    above: @Composable ColumnScope.() -> Unit,
    middle: @Composable () -> Unit,
    below: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        val space = maxHeight
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            MiddleFill(space, least) {
                Column(verticalArrangement = Arrangement.spacedBy(GUIDED_GAP), content = above)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { middle() }
                Column(verticalArrangement = Arrangement.spacedBy(GUIDED_GAP), content = below)
            }
        }
    }
}

/**
 * A guided scan on a key screen: [above] it, its title ([KeyScreenTitle]; the screen shows none of
 * its own) and what to do first; then the [camera]'s view; then [below], what to do with it; and
 * *Cancel*, which calls [onCancel], at the start of its line, as in the key dialog (#543), with
 * [actions], if any, at its end.
 *
 * The camera's view fills the height the rest leaves, but never less than
 * [GUIDED_CAMERA_MIN_HEIGHT]: in a short window, in landscape or with large text, the title, the
 * steps and the view scroll instead ([FilledMiddle]), so the whole view can be scrolled onto the
 * screen, and only the buttons stay fixed under them.
 */
@Composable
internal fun ColumnScope.GuidedScan(
    above: @Composable ColumnScope.() -> Unit,
    camera: @Composable () -> Unit,
    below: @Composable ColumnScope.() -> Unit,
    onCancel: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    FilledMiddle(GUIDED_CAMERA_MIN_HEIGHT, above, camera, below)
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
        content = actions,
    )
}

/**
 * Lays out [content]'s three pieces, what goes above, the middle and what goes below, one above the
 * other, [GUIDED_GAP] apart: the middle as tall as the [space] the other two leave, and at least
 * [least], or, with none, its own height. When that is more than [space], the whole is taller than
 * it, for the scroll around it to show.
 */
@Composable
private fun MiddleFill(
    space: Dp,
    least: Dp?,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val (above, middle, below) = measurables
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val top = above.measure(loose)
        val bottom = below.measure(loose)
        val gap = GUIDED_GAP.roundToPx()
        val left = space.roundToPx() - top.height - bottom.height - 2 * gap
        val floor = least?.roundToPx() ?: middle.maxIntrinsicHeight(constraints.maxWidth)
        val height = maxOf(floor, left)
        val view = middle.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(constraints.maxWidth, top.height + gap + height + gap + bottom.height) {
            top.place(0, 0)
            view.place(0, top.height + gap)
            bottom.place(0, top.height + gap + height + gap)
        }
    }
}

/**
 * The API keys page's address ([API_KEYS_URL]) in full, as plain text (#765): selectable, so it can
 * be copied, but not a link, which would open the page on this phone, not on the computer that
 * makes the code. Medium weight; [centred] on *Load keys*' intro, with space above and below it.
 */
@Composable
internal fun ApiKeysAddress(centred: Boolean = false) {
    SelectionContainer(modifier = if (centred) Modifier.fillMaxWidth().padding(vertical = 14.dp) else Modifier) {
        Text(
            API_KEYS_URL,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        )
    }
}

/**
 * What the key field's icons open (#541): a key screen ([KeyScreen], secure, as the key dialog
 * is) that reads a QR code [for][purpose] the key, guided as *Load keys* is ([GuidedScan]). *Scan
 * QR code* (#765): to make a code of this one key on the API keys page, whose address shows as
 * plain text ([ApiKeysAddress]), the camera's view, to point it at the code, and that any code of
 * this key alone works. *Type on a computer*: what it is, the keyboard page at [KEYBOARD_PAGE_URL],
 * a link, reading its code, and choosing the phone on it. [onScanned] hears the code read; Back and
 * *Cancel* call [onDismiss].
 */
@Composable
internal fun KeyScanScreen(
    purpose: ScanFor,
    scanner: QrScanner,
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val body = MaterialTheme.typography.bodyMedium
    KeyScreen(title = null, busy = false, onDismiss) {
        when (purpose) {
            ScanFor.KEY -> {
                GuidedScan(
                    above = {
                        KeyScreenTitle(R.string.settings_key_scan)
                        Text(stringResource(R.string.key_scan_open), style = body)
                        ApiKeysAddress()
                    },
                    camera = { scanner(onScanned) },
                    below = {
                        Text(stringResource(R.string.key_scan_point), style = body)
                        Text(
                            stringResource(R.string.key_scan_any),
                            style = body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onCancel = onDismiss,
                )
            }

            ScanFor.KEYBOARD -> {
                GuidedScan(
                    above = {
                        KeyScreenTitle(R.string.settings_key_type)
                        Text(stringResource(R.string.keyboard_explained), style = body)
                        StepHeading(stringResource(R.string.keyboard_open))
                        val address = shownAddress(KEYBOARD_PAGE_URL)
                        ExternalLink(
                            text = address,
                            description = stringResource(R.string.keyboard_page_of, address),
                            onClick = rememberOpenUrl(KEYBOARD_PAGE_URL),
                        )
                    },
                    camera = { scanner(onScanned) },
                    below = {
                        StepHeading(stringResource(R.string.keyboard_scan))
                        StepHeading(stringResource(R.string.keyboard_connect))
                    },
                    onCancel = onDismiss,
                )
            }
        }
    }
}
