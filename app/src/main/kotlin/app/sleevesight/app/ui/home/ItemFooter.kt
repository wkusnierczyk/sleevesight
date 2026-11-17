package app.sleevesight.app.ui.home

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ItemAsk
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.lab.LabCompareUnder
import app.sleevesight.app.ui.lab.LabText
import app.sleevesight.app.ui.report.ReportAction
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.LabPrompts
import app.sleevesight.core.synthesis.PromptKind

// The footer of the song's, the artist's and the album's written insights (#522), with what the key
// insight's says of a rewrite (#911): the insight another model wrote, *Rewrite insight*, and the
// credit, "Rewritten by …" (#1162).

/**
 * The footer under [item]'s written insight [state], as the key insight's, top to bottom: the credit
 * (#959, #1162: [insightCredit], by the model that wrote it) with what follows from it on its line
 * ([afterCredit], #911) and *Read aloud* ([readAloud]) at the row's end, the grounding note, which
 * citations it translated from the track's language ([TranslatedLine], #902; each by [label], the id
 * its chip shows), that the reply stopped early, *Report the insight*;
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
    readAloud: ReadAloudControl? = null,
) {
    CreditRow(
        insightCredit(state, state.writtenBy?.model ?: shown.model),
        Modifier.testTag(INSIGHT_BY_TAG),
        after = afterCredit(shown, context),
        readAloud = readAloud?.let { { ReadAloudButton(it, itemReadAloudTag(item)) } },
        lab = if (BuildConfig.DEBUG) itemLab(item)?.let { LabText(it, state.grounded.text) } else null,
    )
    GroundingLine(state.grounded, details = false)
    TranslatedLine(state.grounded, shown.evidence.evidence, label = label)
    if (!state.complete) ItemQuiet(stringResource(R.string.home_insight_incomplete))
    ReportAction { context.onReport(track.copy(evidence = shown.evidence.evidence), state, item) }
    // The prompt lab's *Compare* (#1003), in the debug build only, after *Report the insight*.
    if (BuildConfig.DEBUG) itemLab(item)?.let { LabCompareUnder(it, track, state, shown.evidence.evidence) }
    context.unavailable?.let { ModelUnavailable(it, context.onModelSettings) }
    // A web search due for this insight failed (#958): beside it, as the whole line, with *Retry*.
    if (shown.rewriteWith == null && shown.rewriteFailed == AiSearches.WEB_SEARCH_FAILED) {
        RetryLine(
            stringResource(R.string.home_ai_web_search_failed),
            source = "",
            onRetry = { context.notes.write(track, item, ItemAsk.TAP) }.takeIf { rewrites(shown, context) },
            modifier = Modifier.testTag(ITEM_FAILED_TAG),
            action = stringResource(R.string.home_ai_retry_action),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            outlined = true,
            ai = true,
        )
    }
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
 * What follows the credit of an item's insight on its line (#911, #1162), as the key insight's footer
 * says it (`afterCredit`): for the insight another AI service, model or effort wrote, shown in place
 * of none ([ItemShown.Shown.rewriteWith]), that a rewrite of it stopped or failed
 * ([ItemShown.Shown.rewriteFailed]; not that the service refused the model while it is found
 * unavailable, which the footer's last line says, #969: [rewriteFailureShown]), then, while it can be
 * tapped ([rewrites]), what *Rewrite insight* would rewrite it with; else nothing.
 */
@Composable
private fun afterCredit(
    shown: ItemShown.Shown,
    context: ItemContext,
): List<String> {
    val offer = shown.rewriteWith ?: return emptyList()
    val offered =
        offer
            .takeIf { rewrites(shown, context) }
            ?.let { rewriteWhat(it.parts, it.with) }
            ?.let { stringResource(R.string.home_rewrite_offer, it) }
    val failed = rewriteFailureShown(shown.rewriteFailed, context.unavailable)?.let { rewriteFailure(it) }
    return listOfNotNull(failed, offered)
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

// The prompt lab's kind of [item]'s insight (#1003): the song's, the artist's or the album's.
private fun itemLab(item: InsightItem): PromptKind? = LabPrompts.kindOfCard(item.kind, null)
