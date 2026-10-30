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

/**
 * The guide to Home (#627), under the players while nothing plays: the picture ([GuidePicture]),
 * and under it a split button whose menu chooses what it does (#759): *Hide once* ([onHideOnce]),
 * the default, which hides the guide until Home is left and visited again, or *Dismiss*
 * ([onDismiss]), which puts it away for good, until *Restore dismissed messages* in the settings.
 */
@Composable
internal fun HomeGuide(
    onHideOnce: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(HOME_GUIDE_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuidePicture(Modifier.fillMaxWidth())
        HideButton(onHideOnce, onDismiss)
    }
}

/**
 * The guide's split button, after the insight card's ([WriteSplitButton]), outlined, laid out by
 * its [Halves] (32 dp high as drawn, each half a 48 dp target): the leading half does what the menu
 * has chosen, *Hide once* until another is chosen, and says which (#759); the arrow opens the menu.
 * A screen reader reads the arrow as *Hide options: <the choice>*, a drop-down list, and the choices
 * as one choice among them, the chosen one selected.
 */
@Composable
private fun HideButton(
    onHideOnce: () -> Unit,
    onDismiss: () -> Unit,
) {
    var dismiss by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val choice = stringResource(if (dismiss) R.string.home_guide_dismiss else R.string.home_guide_hide_once)
    val described = stringResource(R.string.home_guide_dismiss_options, choice)
    // Laid out as the insight card's split button is: 32 dp high as drawn, each half a 48 dp target.
    Halves(Modifier) {
        OutlinedButton(
            onClick = if (dismiss) onDismiss else onHideOnce,
            modifier = Modifier.testTag(GUIDE_HIDE_TAG),
            shape = RoundedCornerShape(SPLIT_OUTER_CORNER, SPLIT_INNER_CORNER, SPLIT_INNER_CORNER, SPLIT_OUTER_CORNER),
            contentPadding = SPLIT_PADDING,
        ) { Text(choice) }
        val shape = RoundedCornerShape(SPLIT_INNER_CORNER, SPLIT_OUTER_CORNER, SPLIT_OUTER_CORNER, SPLIT_INNER_CORNER)
        Box {
            Box(
                modifier =
                    Modifier
                        .testTag(GUIDE_HIDE_MENU_TAG)
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
                    modifier = Modifier.rotate(if (open) SPLIT_OPEN_ROTATION else 0f),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(Modifier.selectableGroup()) {
                    HideChoice(R.string.home_guide_hide_once, chosen = !dismiss, GUIDE_HIDE_ONCE_TAG) {
                        dismiss = false
                        open = false
                    }
                    HideChoice(R.string.home_guide_dismiss, chosen = dismiss, GUIDE_DISMISS_TAG) {
                        dismiss = true
                        open = false
                    }
                }
            }
        }
    }
}

// A choice in the guide's menu, checked when [chosen], as the insight card's menu marks its mode.
@Composable
private fun HideChoice(
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

/** The test tag of the guide: its picture and its split button. */
const val HOME_GUIDE_TAG: String = "home-guide"

/** The test tag of the split button's leading half, which says the choice and does it (#759). */
const val GUIDE_HIDE_TAG: String = "home-guide-hide"

/** The test tag of the split button's arrow, which opens its menu. */
const val GUIDE_HIDE_MENU_TAG: String = "home-guide-hide-menu"

/** The test tag of the menu's *Hide once* (#759). */
const val GUIDE_HIDE_ONCE_TAG: String = "home-guide-hide-once"

/** The test tag of the menu's *Dismiss* (#759). */
const val GUIDE_DISMISS_TAG: String = "home-guide-dismiss"
