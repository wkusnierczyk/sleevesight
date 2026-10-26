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
}

/**
 * What the split button's leading half offers (#615), its label saying what a tap does:
 * - no insight shown, under *Now*: *Write insight*, while the pipeline offers Write;
 * - no insight shown, under an automatic mode: *Write on change* or *Write on skip*, by the mode
 *   ([modeWrite]); enabled while automatic writing waits for a tap (no first action yet, or *Saver*
 *   has paused it: [waitsForTap]), when a tap writes this track and starts or resumes it; disabled
 *   while it runs by itself;
 * - a Write offered for a track automatic writing leaves (a natural end under *On track skip*), or
 *   again after a call that failed or was stopped: *Write insight*, which writes this one only;
 * - an insight shown, written now or kept from before: the mode's Write, disabled. No *Rewrite*
 *   (#615): a card kept matches the evidence, prompt, model and effort, so it would only write the
 *   same input again; the coordinator's offer of one is not shown;
 * - while it is written: *Stop*, while the call can be stopped.
 * Disabled whenever a tap would do nothing: the pipeline offers no Write, or the model is found
 * unavailable and no insight is shown (#540), where a Write would fail again.
 */
internal fun cardOffer(
    insight: InsightState,
    context: InsightContext,
): CardOffer {
    val idle = CardOffer(modeWrite(context), enabled = false)
    if (context.unavailable != null && withoutInsight(insight)) return idle
    return when (insight) {
        is InsightState.Idle ->
            when {
                !insight.canWrite -> idle
                waitsForTap(context) -> CardOffer(modeWrite(context), enabled = true)
                else -> CardOffer(R.string.home_write, enabled = true)
            }

        is InsightState.Writing -> CardOffer(R.string.home_stop, enabled = context.offers.stop, stop = true)
        is InsightState.Written -> idle
        InsightState.NoEvidence -> idle
        is InsightState.Failed, InsightState.Stopped ->
            if (context.offers.retry) CardOffer(R.string.home_write, enabled = true) else idle
    }
}

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
 * since the app started, or while *Saver* has paused it (the Write offered because of the pause
 * included). The one place the rule is kept, so the labels and the lines follow it together.
 */
internal fun waitsForTap(context: InsightContext): Boolean =
    automaticMode(context) != null && (context.waiting || context.offers.paused)

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
