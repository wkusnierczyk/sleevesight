package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.ui.report.ReportAction
import app.sleevesight.core.pipeline.InsightState

/**
 * *Key insights*: the insight in each of its states (architecture, *Errors and empty
 * states*). Model prose is plain text: its only interactive parts are the citation chips, built
 * from the evidence ids the app assigned, each scrolling to its line. Write, Rewrite and Stop go
 * to the pipeline as taps; whether a tap writes or stops is the pipeline's to say. Under a written
 * insight, [GroundingLine]: a note, or with [groundingDetails] (*Show grounding details*, #437) the
 * grounding check's counts in full.
 *
 * A written insight offers *Report a problem* (#390), under it, which hands the
 * insight and its track to [InsightActions.onReport].
 *
 * No container of its own (#609): the heading and the text sit on Home's background with the rest.
 * At the card's end, in every state, the split button (#615, [WriteSplitButton]): the card's action
 * ([cardOffer]) and the menu of *Reactive*'s modes, from [writing]; its arrow cannot be used while
 * that is null, before the settings' first read.
 */
@Composable
internal fun InsightCard(
    insight: InsightState,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean = false,
    writing: WritingChoice? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_insight_title), style = MaterialTheme.typography.titleMedium)
        InsightBody(insight, context, actions, groundingDetails)
        WriteSplitButton(cardOffer(insight, context), actions.onWrite, actions.onStop, writing)
    }
}

// Why the card shows no insight, in words that match the split button's label.
@Composable
private fun IdleNote(
    insight: InsightState.Idle,
    context: InsightContext,
) {
    val auto = automaticMode(context)
    val line =
        when {
            insight.canWrite && waitsForTap(context) -> {
                val label = stringResource(modeWrite(context))
                // Automatic writing paused while the app was away (#203): say so, and that a tap resumes it.
                if (resumes(context)) {
                    stringResource(R.string.home_insight_paused, label)
                } else {
                    stringResource(R.string.home_insight_tap_write_auto, label)
                }
            }

            insight.canWrite -> stringResource(R.string.home_insight_tap_write)
            // Running by itself, the evidence in: it writes next, as the mode says.
            auto != null && runsByItself(context) -> stringResource(runningLine(auto))
            else -> stringResource(idleReason(context))
        }
    Quiet(line)
}

@Composable
private fun InsightBody(
    insight: InsightState,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean,
) {
    // A model found unavailable (#540): said in place of why nothing is written, or of the failure
    // it caused; the split button offers no Write, which would fail again.
    val unavailable = context.unavailable
    if (unavailable != null && withoutInsight(insight)) {
        ModelUnavailable(unavailable, actions.onSettings)
        return
    }
    when (insight) {
        is InsightState.Idle -> IdleNote(insight, context)

        is InsightState.Writing -> {
            val paragraphs = paragraphsOf(insight.partial)
            for (paragraph in paragraphs) UncheckedProse(paragraph, context, actions.onCite, insight.held)
            // One line, by the split button's Stop while it can stop the call (#615).
            when {
                context.offers.stop -> Quiet(stringResource(R.string.home_insight_writing_stop))
                paragraphs.isEmpty() -> Quiet(stringResource(R.string.home_insight_writing))
            }
        }

        is InsightState.Written -> Written(insight, context, actions, groundingDetails)
        InsightState.NoEvidence -> Quiet(stringResource(R.string.home_insight_no_evidence))
        is InsightState.Failed -> {
            Quiet(stringResource(R.string.home_insight_failed, failureText(insight.reason)))
            Retry(context)
        }

        InsightState.Stopped -> {
            Quiet(stringResource(R.string.home_insight_stopped))
            Retry(context)
        }
    }
}

// Write offered again after a call that failed or was stopped: a new paid call, on a tap only, from
// the split button. While automatic writing waits for a tap, the tap also starts or resumes it, and
// the line names the mode's Write, as the button does.
@Composable
private fun Retry(context: InsightContext) {
    if (!context.offers.retry) return
    val line =
        when {
            !waitsForTap(context) -> stringResource(R.string.home_insight_retry)
            resumes(context) -> stringResource(R.string.home_insight_retry_resume, stringResource(modeWrite(context)))
            else -> stringResource(R.string.home_insight_retry_auto, stringResource(modeWrite(context)))
        }
    Quiet(line)
}

/** Why no insight is offered: insights off, no AI service, the evidence still coming, or the pipeline not there yet. */
@StringRes
private fun idleReason(context: InsightContext): Int =
    when {
        !context.insightsOn -> R.string.home_insight_off
        !context.aiReady -> R.string.home_insight_unavailable
        context.track?.gathered != true -> R.string.home_insight_gathering
        else -> R.string.home_insight_preparing
    }

@Composable
private fun Written(
    insight: InsightState.Written,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean,
) {
    val paragraphs = insight.grounded.paragraphs
    if (paragraphs.isEmpty()) Prose(AnnotatedString(insight.grounded.text))
    for (paragraph in paragraphs) {
        CheckedProse(paragraph, insight.grounded, context.track?.evidence, actions.onCite, insight.held)
    }
    // The footer: the grounding line, then *Report a problem* at the row's end. The line's box takes
    // the room whatever it holds, so the action keeps its place should the line show only at times.
    Row(verticalAlignment = Alignment.CenterVertically) {
        // *Kept from before.* for the card paid for, whether or not it came from the cache.
        val kept = insight.cached || context.cardShown
        Box(Modifier.weight(1f)) { GroundingLine(insight.grounded, kept, groundingDetails) }
        context.track?.let { track -> ReportAction { actions.onReport(track, insight) } }
    }
    if (!insight.complete) Quiet(stringResource(R.string.home_insight_incomplete))
    // The card paid for, shown again after its rewrite failed or was stopped.
    context.rewriteFailed?.let {
        if (it == PipelineHost.STOPPED) {
            Quiet(stringResource(R.string.home_rewrite_stopped))
        } else {
            Quiet(stringResource(R.string.home_rewrite_failed, failureText(it)))
        }
    }
    // By the split button's Rewrite (#615): written with earlier settings, it can be written again.
    if (offersRewrite(context)) {
        Quiet(stringResource(R.string.home_rewrite_offered))
    }
}

@Composable
private fun Prose(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
