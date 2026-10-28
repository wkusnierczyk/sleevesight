package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.ActionButtonGroup
import app.sleevesight.app.ui.theme.FocusHandoff
import app.sleevesight.app.ui.theme.GroupAction
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff

// The paging of the *Sources* list (#269, #440): how much of it shows, above it, and the controls
// under it. The fold itself is *Sources*' chip and its panel's *Close* ([SourcesSection]).

/**
 * Above the unfolded list: how much of it shows ([shown] of [total] entries), or, with every entry
 * hidden by the chips, that they hide the [filtered] lines. A screen reader announces it as it
 * changes once the listener has changed the fold on this track ([SourcesFold.announced]): not
 * while sources land and the list grows by itself, nor on each new track.
 */
@Composable
internal fun FoldStatus(
    fold: SourcesFold,
    shown: Int,
    total: Int,
    filtered: Int,
) {
    val status =
        when {
            total == 0 -> pluralStringResource(R.plurals.home_sources_filtered, filtered, filtered)
            shown < total -> stringResource(R.string.home_sources_showing, shown, total)
            else -> pluralStringResource(R.plurals.home_sources_showing_all, total, total)
        }
    val announced = fold.announced
    Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .testTag(FOLD_STATUS_TAG)
                .semantics { if (announced) liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Under the unfolded list, always in the same place: *Show less* | *Show more* | *Show all*, one
 * connected group of buttons ([ActionButtonGroup], #666), those that do not apply disabled rather
 * than gone, so the group neither moves nor appears as the list grows: *Show less* at the first
 * screenful, *Show more* and *Show all* once the [shown] entries are all of the [total]. A screen
 * reader reads three buttons by their labels, a disabled one as such.
 *
 * A button that disables itself when used hands the focus on within the group, so a keyboard's or a
 * screen reader's focus is not left on a button that cannot be used ([FocusHandoff]; merely
 * touched, nothing moves): *Show all*, and *Show more* reaching the end, to *Show less*; *Show
 * less* to *Show more*.
 */
@Composable
internal fun FoldControls(
    fold: SourcesFold,
    shown: Int,
    total: Int,
) {
    val handoff = rememberFocusHandoff()
    val lessFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val more = shown < total
    ActionButtonGroup(
        listOf(
            GroupAction(
                stringResource(R.string.home_sources_less),
                enabled = shown > Fold.PAGE,
                modifier = Modifier.testTag(FOLD_LESS_TAG).handoffTarget(handoff, lessFocus),
            ) {
                fold.set(Fold.Pages(1))
                handoff.moveTo(moreFocus)
            },
            GroupAction(
                stringResource(R.string.home_sources_more),
                enabled = more,
                modifier = Modifier.testTag(FOLD_MORE_TAG).handoffTarget(handoff, moreFocus),
            ) {
                val next = fold.fold.more(total)
                fold.set(next)
                if (next.shown(total) >= total) handoff.moveTo(lessFocus)
            },
            GroupAction(
                stringResource(R.string.home_sources_all),
                enabled = more,
                modifier = Modifier.testTag(FOLD_ALL_TAG),
            ) {
                fold.set(Fold.All)
                handoff.moveTo(lessFocus)
            },
        ),
    )
}

/** The test tags of the paging's controls and of how much shows. */
const val FOLD_MORE_TAG: String = "fold:more"
const val FOLD_ALL_TAG: String = "fold:all"
const val FOLD_LESS_TAG: String = "fold:less"
const val FOLD_STATUS_TAG: String = "fold:status"
