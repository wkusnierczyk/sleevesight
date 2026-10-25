package app.sleevesight.app.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

// The key field's icons (#432): the eye, *Scan QR code* and *Type on a computer*, and the width
// of the dialog that leaves them room.

/** The key dialog's margin from each side of the screen. */
private val KEY_DIALOG_MARGIN = 16.dp

/** Each icon's touch target, the least Android's accessibility guidance allows. */
private val TARGET = 48.dp

/** Material 3's widest dialog, which the key dialog keeps on wider screens. */
private val KEY_DIALOG_MAX_WIDTH = 560.dp

/**
 * The key dialog's width: the screen's, less [KEY_DIALOG_MARGIN] at each side, up to Material's
 * widest dialog. So a phone's screen, narrower than 592 dp, gives the field all the room it can for
 * its three icons, and a wider one keeps the dialog at the width it has without this. With the
 * platform's own dialog width turned off (`usePlatformDefaultWidth = false`).
 */
internal fun Modifier.keyDialogWidth(): Modifier =
    padding(horizontal = KEY_DIALOG_MARGIN).widthIn(max = KEY_DIALOG_MAX_WIDTH).fillMaxWidth()

/**
 * The key field's trailing icons: the eye, only once something is [entered], which shows what is
 * in the field when it is not [shown] and hides it when it is, each icon naming what a tap does;
 * then *Scan QR code* and *Type on a computer*, which start [onScan] for the key or for a
 * keyboard session, and are off unless [scans]. The eye comes first, so that the other two keep
 * their place when it appears. Each is a 48 dp button with its name spoken and shown as a tooltip.
 */
@Composable
internal fun KeyFieldIcons(
    entered: Boolean,
    shown: Boolean,
    onShow: (Boolean) -> Unit,
    scans: Boolean,
    onScan: (ScanFor) -> Unit,
) {
    Row {
        if (entered) {
            FieldIcon(
                icon = if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                label = stringResource(if (shown) R.string.settings_key_hide else R.string.settings_key_show),
            ) { onShow(!shown) }
        }
        FieldIcon(R.drawable.ic_qr_scan, stringResource(R.string.settings_key_scan), enabled = scans) {
            onScan(ScanFor.KEY)
        }
        FieldIcon(R.drawable.ic_type_on_computer, stringResource(R.string.settings_key_type), enabled = scans) {
            onScan(ScanFor.KEYBOARD)
        }
    }
}

/** An icon button named [label], spoken and shown as a tooltip on a long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldIcon(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        // At 48 dp itself: in a text field's icon slot, Material's buttons are only the 40 dp drawn.
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(TARGET)) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}
