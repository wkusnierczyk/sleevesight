package app.sleevesight.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

/** The toggle's touch target, the least Android's accessibility guidance allows. */
private val TARGET = 48.dp

/** The alpha of a control that cannot be used, as Material's. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The insight card's toggle for automatic writing (#553), as the settings have it: [on] while
 * *Reactive* writes automatically (*On track change* or *On skip*), off under *Manual*. [paused]
 * while *Saver* has paused automatic writing (#203): the toggle stays on and says so, so it does
 * not claim more than is happening. It cannot be used while not [enabled] (*Show insights* off, as
 * *Reactive* cannot be changed then). [onToggle] turns it on or off; the settings decide which
 * automatic mode on restores.
 */
internal class AutoWriting(
    val on: Boolean,
    val paused: Boolean = false,
    val enabled: Boolean = true,
    val onToggle: (Boolean) -> Unit = {},
)

/**
 * The toggle itself: one symbol in an on and an off version (Material Symbols `motion_photos_auto`
 * and `motion_photos_off`), as the key field's eye has; while paused, *Paused* beside the symbol. A
 * switch to a screen reader, named *Write insights automatically* with its state (*on*, *off*, *on,
 * paused*), which a long press shows as a tooltip; a 48 dp target. Turned on, it says in a short
 * message that each new track's insight is a paid call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoWriteToggle(state: AutoWriting) {
    val context = LocalContext.current
    val paused = state.on && state.paused
    val name = stringResource(R.string.home_auto_write)
    val said =
        stringResource(
            when {
                paused -> R.string.home_auto_write_paused
                state.on -> R.string.home_auto_write_on
                else -> R.string.home_auto_write_off
            },
        )
    val paid = stringResource(R.string.home_auto_write_paid)
    val colour =
        when {
            !state.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            state.on && !paused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(R.string.home_auto_write_tooltip, said)) } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier =
                Modifier
                    .testTag(AUTO_WRITE_TAG)
                    .defaultMinSize(minWidth = TARGET, minHeight = TARGET)
                    .clip(CircleShape)
                    .toggleable(value = state.on, enabled = state.enabled, role = Role.Switch) { on ->
                        if (on) Toast.makeText(context, paid, Toast.LENGTH_SHORT).show()
                        state.onToggle(on)
                    }.semantics {
                        contentDescription = name
                        stateDescription = said
                    }.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (paused) {
                Text(
                    stringResource(R.string.home_auto_write_paused_mark),
                    style = MaterialTheme.typography.labelMedium,
                    color = colour,
                    // Said in the toggle's state; tagged for the tests alone.
                    modifier = Modifier.clearAndSetSemantics { testTag = AUTO_WRITE_PAUSED_TAG },
                )
            }
            Icon(
                painterResource(if (state.on) R.drawable.ic_motion_photos_auto else R.drawable.ic_motion_photos_off),
                contentDescription = null,
                tint = colour,
            )
        }
    }
}

/** The test tag of the insight card's toggle for automatic writing. */
const val AUTO_WRITE_TAG: String = "auto-write"

/** The test tag of the toggle's *Paused*. */
const val AUTO_WRITE_PAUSED_TAG: String = "auto-write-paused"
