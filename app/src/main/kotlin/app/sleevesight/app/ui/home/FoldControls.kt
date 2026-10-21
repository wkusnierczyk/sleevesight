package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

// The controls of the *Sources* list's fold (#269), above the list and under it.

/**
 * Above the list: *show* while it is folded, else *hide*; beside it, how much of the list shows
 * ([shown] of [total]), which a screen reader announces as it changes.
 */
@Composable
internal fun FoldStatus(
    fold: SourcesFold,
    shown: Int,
    total: Int,
) {
    val status =
        when {
            shown == 0 -> pluralStringResource(R.plurals.home_sources_folded, total, total)
            shown < total -> stringResource(R.string.home_sources_showing, shown, total)
            else -> pluralStringResource(R.plurals.home_sources_showing_all, total, total)
        }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shown == 0) {
            FoldButton(R.string.home_sources_show, R.string.home_sources_show_words, FOLD_SHOW_TAG) {
                fold.set(Fold.Pages(1))
            }
        } else {
            FoldButton(R.string.home_sources_hide, R.string.home_sources_hide_words, FOLD_HIDE_TAG) {
                fold.set(Fold.Folded)
            }
        }
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .testTag(FOLD_STATUS_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Under the list: *hide*; *less* once past the first screenful; *more* and *show all* while lines
 * remain, of [total], past the [shown].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoldControls(
    fold: SourcesFold,
    shown: Int,
    total: Int,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FoldButton(R.string.home_sources_hide, R.string.home_sources_hide_words, FOLD_HIDE_TAG) {
            fold.set(Fold.Folded)
        }
        if (shown > Fold.PAGE) {
            FoldButton(R.string.home_sources_less, R.string.home_sources_less_words, FOLD_LESS_TAG) {
                fold.set(Fold.Pages(1))
            }
        }
        if (shown < total) {
            FoldButton(R.string.home_sources_more, R.string.home_sources_more_words, FOLD_MORE_TAG) {
                fold.set(fold.fold.more(total))
            }
            FoldButton(R.string.home_sources_all, R.string.home_sources_all_words, FOLD_ALL_TAG) {
                fold.set(Fold.All)
            }
        }
    }
}

/**
 * A control of the fold: its [label] drawn (arrows and dots), and [words] for a screen reader,
 * which reads those in its place; a button with a 48 dp target.
 */
@Composable
private fun FoldButton(
    @StringRes label: Int,
    @StringRes words: Int,
    tag: String,
    onClick: () -> Unit,
) {
    val said = stringResource(words)
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.testTag(tag).semantics { contentDescription = said },
    ) {
        Text(stringResource(label), modifier = Modifier.clearAndSetSemantics {})
    }
}

/** The test tags of the fold's controls (both *hide* share theirs) and of how much shows. */
const val FOLD_SHOW_TAG: String = "fold:show"
const val FOLD_HIDE_TAG: String = "fold:hide"
const val FOLD_MORE_TAG: String = "fold:more"
const val FOLD_ALL_TAG: String = "fold:all"
const val FOLD_LESS_TAG: String = "fold:less"
const val FOLD_STATUS_TAG: String = "fold:status"
