package app.sleevesight.app.ui.home

import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.report.ReportAction
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState

// The footer of the song's, the artist's and the album's written insights (#522), with what the key
// insight's says of a rewrite (#911): the insight another model wrote, *Rewrite insight*, and
// "Rewritten with …".

/**
 * The footer under [item]'s written insight [state], as the key insight's, top to bottom: the history
 * line ([itemHistory], #911), the grounding note, which citations it translated from the track's
 * language ([TranslatedLine], #902; each by [label], the id its chip shows), that the reply stopped
 * early, *Report a problem*;
 * then, while the model is found unavailable (#540), that it is, last, just above the Write it leaves
 * disabled; and *Rewrite insight* beside another model's insight ([ItemShown.Shown.rewriteWith]).
 */
@Suppress("LongParameterList") // The insight, what it is shown with, and how its citations are named.
@Composable
internal fun WrittenFooter(
    item: InsightItem,
    shown: ItemShown.Shown,
    state: InsightState.Written,
    track: TrackState,
    context: ItemContext,
    label: (String) -> String,
) {
    itemHistory(shown, state, context)?.let { ItemQuiet(it) }
    GroundingLine(state.grounded, details = false)
    TranslatedLine(state.grounded, shown.evidence.evidence, label = label)
    if (!state.complete) ItemQuiet(stringResource(R.string.home_insight_incomplete))
    ReportAction { context.onReport(track.copy(evidence = shown.evidence.evidence), state, item) }
    context.unavailable?.let { ModelUnavailable(it, context.onModelSettings) }
    if (shown.rewriteWith != null) {
        RewriteButton(item, enabled = rewrites(shown, context)) { context.notes.write(track, item, ItemAsk.TAP) }
    }
}

// Whether *Rewrite insight* can be tapped beside [shown]: an insight may be written now, and the
// model is not found unavailable (#540), where a rewrite would fail.
private fun rewrites(
    shown: ItemShown.Shown,
    context: ItemContext,
): Boolean = shown.canWrite && context.unavailable == null

/**
 * The history line under an item's insight (#911), as the key insight's footer's first line says it
 * (`writtenBefore`): for the insight another AI service, model or effort wrote, shown in place of none
 * ([ItemShown.Shown.rewriteWith]), *As written previously.*, then that a rewrite of it stopped or failed
 * ([ItemShown.Shown.rewriteFailed]), then, while it can be tapped ([rewrites]), what *Rewrite
 * insight* would rewrite it with; for one a rewrite wrote, *Rewritten with …*; else none: an item's own
 * insight, kept or just written, says nothing of its history.
 */
@Composable
private fun itemHistory(
    shown: ItemShown.Shown,
    state: InsightState.Written,
    context: ItemContext,
): String? {
    val offer = shown.rewriteWith ?: return rewrittenLine(state)
    val offered =
        offer
            .takeIf { rewrites(shown, context) }
            ?.let { rewriteWhat(it.parts, it.with) }
            ?.let { stringResource(R.string.home_rewrite_offer, it) }
    val failed = shown.rewriteFailed?.let { rewriteFailure(it) }
    return listOfNotNull(stringResource(R.string.home_written_before), failed, offered).joinToString(" ")
}

/**
 * *Rewrite insight* beside the insight another AI service, model or effort wrote (#911), with the
 * **AI** mark, as the key insight's: a paid call, on a tap only ([onRewrite]), read "Rewrite insight,
 * uses AI". With the model found unavailable (#540, not [enabled]), *Write insight*, disabled, as the
 * key insight's split button shows it: a rewrite would fail.
 */
@Composable
internal fun RewriteButton(
    item: InsightItem,
    enabled: Boolean,
    onRewrite: () -> Unit,
) {
    Button(onClick = onRewrite, modifier = Modifier.testTag(itemWriteTag(item)), enabled = enabled) {
        AiLabel(stringResource(if (enabled) R.string.home_rewrite else R.string.home_write))
    }
}
