package app.sleevesight.app.ui.home

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState

// The states of the song's, the artist's and the album's insights (#522) that say something of their
// own, in the key insight's words: gathering, writing with *Stop writing*, stopped with *Write
// insight*, and the button that writes.

/**
 * While [item]'s insight is written: *Writing…* with the turning record, *Stop writing*, which ends
 * the call at once ([onStop]), and what stopping costs under it, as the key insight says it (#615).
 */
@Composable
internal fun WritingItem(
    item: InsightItem,
    onStop: () -> Unit,
) {
    Writing()
    // No AI mark: it makes no call.
    OutlinedButton(onClick = onStop, modifier = Modifier.testTag(itemStopTag(item))) {
        Text(stringResource(R.string.home_stop))
    }
    ItemQuiet(stringResource(R.string.home_insight_writing_stop))
}

/**
 * [item]'s insight stopped before its reply ended (*Stop writing*): nothing was kept, as the key
 * insight says, and *Write insight* writes it again, a new call, on a tap only ([onWrite]).
 */
@Composable
internal fun StoppedItem(
    item: InsightItem,
    onWrite: () -> Unit,
) {
    ItemQuiet(stringResource(R.string.home_insight_stopped))
    ItemQuiet(stringResource(R.string.home_insight_retry))
    WriteButton(item, onWrite)
}

/** *Write insight*, with the **AI** mark: a paid call. */
@Composable
internal fun WriteButton(
    item: InsightItem,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.testTag(itemWriteTag(item))) {
        AiLabel(stringResource(R.string.home_write))
    }
}

/** The evidence still coming: *Gathering evidence…* with the turning record. */
@Composable
internal fun Gathering() =
    Busy(
        stringResource(R.string.home_insight_gathering),
        MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** *Writing…* with the turning record. */
@Composable
internal fun Writing() =
    Busy(
        stringResource(R.string.home_insight_writing),
        MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textModifier = Modifier.testTag(ITEM_WRITING_TAG),
    )

/**
 * The line above *Write insight* for an item with nothing written: under an automatic mode, for one
 * asked for by itself (a panel left open at a new track, one unfolded at its start) that it held back,
 * why, in the key insight's words (#948): *Saver*'s pause ([ItemContext.lapsed]), or a track that
 * followed the last one by itself under *On track skip (auto)* ([ItemContext.followed]); else "Tap
 * Write insight…".
 */
internal fun offeredLine(
    context: ItemContext,
    tapped: Boolean,
): Int =
    when {
        !context.automatic || tapped -> R.string.home_item_tap_write
        context.lapsed -> R.string.home_insight_resumed
        context.followed -> R.string.home_insight_followed
        else -> R.string.home_item_tap_write
    }

/** Whether [shown] is an insight being written: asked for, or its reply coming (#948). */
internal fun writing(shown: ItemShown): Boolean =
    shown is ItemShown.Asking || (shown as? ItemShown.Shown)?.state is InsightState.Writing

/** [text] in the notes' quiet style. */
@Composable
internal fun ItemQuiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What [item]'s insight says when the sources give nothing to write it from. */
internal fun noEvidence(item: InsightItem): Int =
    when (item) {
        InsightItem.SONG -> R.string.home_item_no_evidence_song
        InsightItem.ARTIST -> R.string.home_item_no_evidence_artist
        InsightItem.ALBUM -> R.string.home_item_no_evidence_album
    }

/** The test tag of [item]'s *Stop writing*. */
fun itemStopTag(item: InsightItem): String = "item-stop:${item.kind.key}"
