package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * Under the unfolded list: *… show less* once past the first screenful; *show more…* and *show
 * all* while entries remain, of [total], past the [shown]; nothing when the list shows whole within
 * its first screenful.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FoldControls(
    fold: SourcesFold,
    shown: Int,
    total: Int,
) {
    val less = shown > Fold.PAGE
    val more = shown < total
    if (!less && !more) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (less) {
            FoldButton(R.string.home_sources_less, R.string.home_sources_less_words, FOLD_LESS_TAG) {
                fold.set(Fold.Pages(1))
            }
        }
        if (more) {
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
 * A control of the paging: its [label] drawn, and [words] for a screen reader, which reads those
 * in its place ("Show more sources" for *show more…*); a button with a 48 dp target.
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

/** The test tags of the paging's controls and of how much shows. */
const val FOLD_MORE_TAG: String = "fold:more"
const val FOLD_ALL_TAG: String = "fold:all"
const val FOLD_LESS_TAG: String = "fold:less"
const val FOLD_STATUS_TAG: String = "fold:status"
