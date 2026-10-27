package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.WriteAutomatically

// What the insight card's split button offers (#615), and the rules its label and the card's lines
// share.

/**
 * What the split button's leading half offers (#615): its [label], which says what a tap does;
 * whether a tap stops the call under way ([stop]) rather than writes; and whether it can be tapped
 * now ([enabled]).
 */
internal class CardOffer(
    @StringRes val label: Int,
    val enabled: Boolean,
    val stop: Boolean = false,
) {
    /** A Write the pipeline offers: drawn filled, the card's call to act. */
    val invites: Boolean get() = enabled && !stop

    /**
     * Whether a tap calls the AI service, so the label carries the **AI** mark (#624): every Write,
     * a disabled one included; not *Stop writing*.
     */
    val usesAi: Boolean get() = !stop
}

/**
 * What the split button's leading half offers (#615, #625), its label saying what a tap does, the
 * same in every mode:
 * - *Write insight*, enabled, whenever the track has no insight and the pipeline offers Write,
 *   whatever the reason: under *On demand*; before the first action since the app started; for a
 *   track that came to play while *Saver* had paused automatic writing; for one that followed the
 *   last by itself under *On track skip (auto)*; and again after a call that failed or was stopped.
 *   A tap writes that one track (and is the first action, D5);
 * - *Write insight*, disabled, while automatic writing will write the track, before and while it
 *   writes, and once an insight is shown that nothing calls to rewrite;
 * - *Rewrite insight*, enabled, in every mode, beside an insight the coordinator offers to write
 *   again ([InsightOffers.rewrite]): a settings change (the AI service, its model, a source's key,
 *   the web engine) changed the card's key, so the card kept was written with earlier settings;
 *   and again after such a rewrite failed or was stopped. Nothing rewrites it unasked;
 * - *Stop writing* while the insight is written, enabled while the call can be stopped.
 * Disabled whenever a tap would do nothing: the pipeline offers no Write, or the model is found
 * unavailable (#540), where a Write, or a Rewrite, would fail again.
 */
internal fun cardOffer(
    insight: InsightState,
    context: InsightContext,
): CardOffer {
    val idle = CardOffer(R.string.home_write, enabled = false)
    if (context.unavailable != null && withoutInsight(insight)) return idle
    val write = CardOffer(R.string.home_write, enabled = true)
    return when (insight) {
        is InsightState.Idle -> if (insight.canWrite) write else idle
        is InsightState.Writing -> CardOffer(R.string.home_stop, enabled = context.offers.stop, stop = true)
        is InsightState.Written ->
            if (offersRewrite(context)) CardOffer(R.string.home_rewrite, enabled = true) else idle
        InsightState.NoEvidence -> idle
        is InsightState.Failed, InsightState.Stopped -> if (context.offers.retry) write else idle
    }
}

/**
 * Whether the split button offers *Rewrite insight* beside a written insight: the coordinator offers to write
 * the card paid for again ([InsightOffers.rewrite]), and the model is not found unavailable (#540).
 * The card's line about it follows the same rule.
 */
internal fun offersRewrite(context: InsightContext): Boolean = context.offers.rewrite && context.unavailable == null

/** *Reactive*'s mode while it writes automatically; null under *On demand*, or before the settings' first read. */
internal fun automaticMode(context: InsightContext): WriteAutomatically? =
    context.mode?.takeIf { it != WriteAutomatically.NEVER }

/**
 * Whether automatic writing runs by itself: an automatic mode, insights on, an AI service ready,
 * the evidence in, and the user not awaited (`PipelineView.waiting`: the first action made, and
 * the track did not come to play while *Saver* had paused automatic writing).
 */
internal fun runsByItself(context: InsightContext): Boolean =
    automaticMode(context) != null &&
        context.insightsOn &&
        context.aiReady &&
        context.track?.gathered == true &&
        !context.waiting

/** The line said while automatic writing runs by itself under [mode]. */
@StringRes
internal fun runningLine(mode: WriteAutomatically): Int =
    if (mode == WriteAutomatically.WHEN_I_SKIP) R.string.home_insight_auto_skip else R.string.home_insight_auto_change
