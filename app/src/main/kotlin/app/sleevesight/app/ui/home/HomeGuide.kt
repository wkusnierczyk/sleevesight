package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.TextPart

/**
 * The guide to Home (#627), under the players while nothing plays: the picture ([GuidePicture]),
 * and under it *Dismiss*, a split button whose menu chooses what it does: *Dismiss once*
 * ([onDismissOnce]), the default, or *Do not show again* ([onNever], which switches *Home guide*
 * off); under them, *Re-enable in Settings* ([onReenable]) opens the settings at *Home guide*.
 */
@Composable
internal fun HomeGuide(
    onDismissOnce: () -> Unit,
    onNever: () -> Unit,
    onReenable: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(HOME_GUIDE_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuidePicture(Modifier.fillMaxWidth())
        DismissButton(onDismissOnce, onNever, onReenable)
    }
}

/**
 * *Dismiss*, after the insight card's split button ([WriteSplitButton]), outlined, laid out by its
 * [Halves] (32 dp high as drawn, each half a 48 dp target): the leading half
 * dismisses the guide as the menu says, *Dismiss once* until another is chosen, and says *Dismiss*
 * either way; the arrow opens the menu. A screen reader reads the arrow as *Dismiss options: <the
 * choice>*, a drop-down list, the choices as one choice among them, the chosen one selected, and
 * *Re-enable in Settings* as a link.
 */
@Composable
private fun DismissButton(
    onDismissOnce: () -> Unit,
    onNever: () -> Unit,
    onReenable: () -> Unit,
) {
    var never by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val choice = stringResource(if (never) R.string.home_guide_dismiss_never else R.string.home_guide_dismiss_once)
    val described = stringResource(R.string.home_guide_dismiss_options, choice)
    // Laid out as the insight card's split button is: 32 dp high as drawn, each half a 48 dp target.
    Halves(Modifier) {
        OutlinedButton(
            onClick = if (never) onNever else onDismissOnce,
            modifier = Modifier.testTag(GUIDE_DISMISS_TAG),
            shape = RoundedCornerShape(OUTER_CORNER, INNER_CORNER, INNER_CORNER, OUTER_CORNER),
            contentPadding = PADDING,
        ) { Text(stringResource(R.string.home_guide_dismiss)) }
        val shape = RoundedCornerShape(INNER_CORNER, OUTER_CORNER, OUTER_CORNER, INNER_CORNER)
        Box {
            Box(
                modifier =
                    Modifier
                        .testTag(GUIDE_DISMISS_MENU_TAG)
                        // As wide and high as [Halves] has it: 48 dp, and the leading half's height.
                        .fillMaxSize()
                        .clip(shape)
                        .border(ButtonDefaults.outlinedButtonBorder(), shape)
                        .clickable(role = Role.DropdownList) { open = true }
                        .semantics { contentDescription = described },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_keyboard_arrow_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(if (open) OPEN_ROTATION else 0f),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(Modifier.selectableGroup()) {
                    DismissChoice(R.string.home_guide_dismiss_once, chosen = !never, GUIDE_DISMISS_ONCE_TAG) {
                        never = false
                        open = false
                    }
                    DismissChoice(R.string.home_guide_dismiss_never, chosen = never, GUIDE_DISMISS_NEVER_TAG) {
                        never = true
                        open = false
                    }
                }
                // A link of its own under the choices, set in at their text, not one of them.
                LinkLabel(
                    TextPart.Action(stringResource(R.string.home_guide_reenable), GUIDE_REENABLE_TAG) {
                        open = false
                        onReenable()
                    },
                    onClickLabel = stringResource(R.string.home_guide_reenable_open),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = MENU_TEXT_START, end = 12.dp),
                )
            }
        }
    }
}

// A choice in *Dismiss*'s menu, checked when [chosen], as the insight card's menu marks its mode.
@Composable
private fun DismissChoice(
    @StringRes name: Int,
    chosen: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(name)) },
        onClick = onClick,
        leadingIcon = {
            if (chosen) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        modifier =
            Modifier.testTag(tag).semantics {
                role = Role.RadioButton
                selected = chosen
            },
    )
}

/** Where a menu item's text starts: the item's padding, its leading icon and the gap after it. */
private val MENU_TEXT_START = 48.dp

/** The test tag of the guide: its picture and *Dismiss*. */
const val HOME_GUIDE_TAG: String = "home-guide"

/** The test tag of *Dismiss*'s leading half. */
const val GUIDE_DISMISS_TAG: String = "home-guide-dismiss"

/** The test tag of *Dismiss*'s arrow, which opens its menu. */
const val GUIDE_DISMISS_MENU_TAG: String = "home-guide-dismiss-menu"

/** The test tag of the menu's *Dismiss once*. */
const val GUIDE_DISMISS_ONCE_TAG: String = "home-guide-dismiss-once"

/** The test tag of the menu's *Do not show again*. */
const val GUIDE_DISMISS_NEVER_TAG: String = "home-guide-dismiss-never"

/** The test tag of the menu's *Re-enable in Settings*. */
const val GUIDE_REENABLE_TAG: String = "home-guide-reenable"
