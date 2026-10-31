package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.ActionButtonGroup
import app.sleevesight.app.ui.theme.GroupAction

/**
 * The guide to Home (#627), under the players while nothing plays: the picture ([GuidePicture]),
 * and under it, at the end, its two actions as one connected group (#759): *Hide once*
 * ([onHideOnce]), which hides the guide until Home is left and visited again, and *Dismiss*
 * ([onDismiss]), which puts it away for good, until *Restore dismissed messages* in the settings.
 * Each acts at once; a screen reader reads two buttons by their labels.
 */
@Composable
internal fun HomeGuide(
    onHideOnce: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(HOME_GUIDE_TAG), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuidePicture(Modifier.fillMaxWidth())
        ActionButtonGroup(
            listOf(
                GroupAction(
                    stringResource(R.string.home_guide_hide_once),
                    modifier = Modifier.testTag(GUIDE_HIDE_ONCE_TAG),
                    onClick = onHideOnce,
                ),
                GroupAction(
                    stringResource(R.string.home_guide_dismiss),
                    modifier = Modifier.testTag(GUIDE_DISMISS_TAG),
                    onClick = onDismiss,
                ),
            ),
            compact = Alignment.End,
        )
    }
}

/** The test tag of the guide: its picture and its buttons. */
const val HOME_GUIDE_TAG: String = "home-guide"

/** The test tag of the guide's *Hide once* (#759). */
const val GUIDE_HIDE_ONCE_TAG: String = "home-guide-hide-once"

/** The test tag of the guide's *Dismiss* (#759). */
const val GUIDE_DISMISS_TAG: String = "home-guide-dismiss"
