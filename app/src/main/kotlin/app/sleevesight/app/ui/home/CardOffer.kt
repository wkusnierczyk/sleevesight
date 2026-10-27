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
     * a disabled one included; not *Stop*.
     */
    val usesAi: Boolean get() = !stop
}

/**
 * What the split button's leading half offers (#615), its label saying what a tap does:
 * - no insight shown, under *Now*: *Write insight*, while the pipeline offers Write;
 * - no insight shown, under an automatic mode: *Write on change* or *Write on skip*, by the mode
 *   ([modeWrite]); enabled while automatic writing waits for a tap (no first action yet, or *Saver*
 *   has paused it: [waitsForTap]), when a tap writes this track and starts or resumes it; disabled
 *   while it runs by itself;
 * - a Write offered for a track automatic writing leaves (a natural end under *On track skip*):
 *   *Write insight*, which writes this one only;
 * - a Write offered again after a call that failed or was stopped: *Write insight*, which writes
 *   this one only; or, while automatic writing waits for a tap, the mode's Write, since the tap
 *   also starts or resumes it;
 * - an insight shown that the coordinator offers to write again ([InsightOffers.rewrite]):
 *   *Rewrite*, enabled, in every mode. It is offered only when a settings change (the AI service,
 *   its model, a source's key, the web engine) changed the card's key, so the card kept was written
 *   with earlier settings; and again after such a rewrite failed or was stopped. Like any tap on
 *   the Write offered, it also starts or resumes automatic writing while that waits for a tap;
 * - any other insight shown, written now or kept from before: the mode's Write, disabled;
 * - while it is written: *Stop*, while the call can be stopped.
 * Disabled whenever a tap would do nothing: the pipeline offers no Write, or the model is found
 * unavailable (#540), where a Write, or a Rewrite, would fail again.
 */
internal fun cardOffer(
    insight: InsightState,
    context: InsightContext,
): CardOffer {
    val idle = CardOffer(modeWrite(context), enabled = false)
    if (context.unavailable != null && withoutInsight(insight)) return idle
    return when (insight) {
        is InsightState.Idle -> if (insight.canWrite) write(context) else idle
        is InsightState.Writing -> CardOffer(R.string.home_stop, enabled = context.offers.stop, stop = true)
        is InsightState.Written ->
            if (offersRewrite(context)) CardOffer(R.string.home_rewrite, enabled = true) else idle
        InsightState.NoEvidence -> idle
        is InsightState.Failed, InsightState.Stopped -> if (context.offers.retry) write(context) else idle
    }
}

// The Write offered, enabled: the mode's while automatic writing waits for a tap, which the tap
// starts or resumes; else *Write insight*, which writes this one only.
private fun write(context: InsightContext): CardOffer =
    CardOffer(if (waitsForTap(context)) modeWrite(context) else R.string.home_write, enabled = true)

/**
 * Whether the split button offers *Rewrite* beside a written insight: the coordinator offers to write
 * the card paid for again ([InsightOffers.rewrite]), and the model is not found unavailable (#540).
 * The card's line about it follows the same rule.
 */
internal fun offersRewrite(context: InsightContext): Boolean = context.offers.rewrite && context.unavailable == null

/** *Reactive*'s mode while it writes automatically; null under *Now*, or before the settings' first read. */
internal fun automaticMode(context: InsightContext): WriteAutomatically? =
    context.mode?.takeIf { it != WriteAutomatically.NEVER }

/** The Write the mode names: *Write on change*, *Write on skip*, or under *Now* *Write insight*. */
@StringRes
internal fun modeWrite(context: InsightContext): Int =
    when (automaticMode(context)) {
        WriteAutomatically.ON_EVERY_TRACK_CHANGE -> R.string.home_write_on_change
        WriteAutomatically.WHEN_I_SKIP -> R.string.home_write_on_skip
        else -> R.string.home_write
    }

/**
 * Whether automatic writing waits for a tap (#615): under an automatic mode, before the first action
 * since the app started, or while *Saver* has paused it ([resumes]). The one place the rule is kept,
 * so the labels and the lines follow it together.
 */
internal fun waitsForTap(context: InsightContext): Boolean =
    automaticMode(context) != null && (context.waiting || resumes(context))

/**
 * Whether a tap resumes automatic writing rather than starts it: under an automatic mode, while
 * *Saver* has paused it (#203), whether or not the Write offered is offered because of the pause.
 */
internal fun resumes(context: InsightContext): Boolean =
    automaticMode(context) != null && (context.paused || context.offers.paused)

/**
 * Whether automatic writing runs by itself: an automatic mode, insights on, an AI service ready,
 * the evidence in, and no tap awaited.
 */
internal fun runsByItself(context: InsightContext): Boolean =
    automaticMode(context) != null &&
        context.insightsOn &&
        context.aiReady &&
        context.track?.gathered == true &&
        !waitsForTap(context)

/** The line said while automatic writing runs by itself under [mode]. */
@StringRes
internal fun runningLine(mode: WriteAutomatically): Int =
    if (mode == WriteAutomatically.WHEN_I_SKIP) R.string.home_insight_auto_skip else R.string.home_insight_auto_change
