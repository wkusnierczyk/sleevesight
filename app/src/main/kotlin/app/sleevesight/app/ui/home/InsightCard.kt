package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 */
@Composable
internal fun InsightCard(
    insight: InsightState,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_insight_title), style = MaterialTheme.typography.titleMedium)
        InsightBody(insight, context, actions, groundingDetails)
    }
}

@Composable
private fun InsightBody(
    insight: InsightState,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean,
) {
    // A model found unavailable (#540): said in place of why nothing is written, or of the failure
    // it caused, with no Write, which would fail again.
    val unavailable = context.unavailable
    if (unavailable != null && withoutInsight(insight)) {
        ModelUnavailable(unavailable, actions.onSettings)
        return
    }
    when (insight) {
        is InsightState.Idle -> {
            if (insight.canWrite) {
                // Offered because automatic writing paused while the app was away (#203): say so.
                val why = if (context.offers.paused) R.string.home_insight_paused else R.string.home_insight_tap_write
                Quiet(stringResource(why))
                WriteButton(actions.onWrite)
            } else {
                Quiet(stringResource(idleReason(context)))
            }
        }

        is InsightState.Writing -> {
            val paragraphs = paragraphsOf(insight.partial)
            if (paragraphs.isEmpty()) Quiet(stringResource(R.string.home_insight_writing))
            for (paragraph in paragraphs) UncheckedProse(paragraph, context, actions.onCite, insight.held)
            if (context.offers.stop) StopButton(actions.onStop)
        }

        is InsightState.Written -> Written(insight, context, actions, groundingDetails)
        InsightState.NoEvidence -> Quiet(stringResource(R.string.home_insight_no_evidence))
        is InsightState.Failed -> {
            Quiet(stringResource(R.string.home_insight_failed, failureText(insight.reason)))
            Retry(context, actions.onWrite)
        }

        InsightState.Stopped -> {
            Quiet(stringResource(R.string.home_insight_stopped))
            Retry(context, actions.onWrite)
        }
    }
}

// Write offered again after a call that failed or was stopped: a new paid call, on a tap only.
@Composable
private fun Retry(
    context: InsightContext,
    onWrite: () -> Unit,
) {
    if (!context.offers.retry) return
    Quiet(stringResource(R.string.home_insight_retry))
    WriteButton(onWrite)
}

@Composable
private fun WriteButton(onWrite: () -> Unit) {
    Button(onClick = onWrite, modifier = Modifier.testTag(WRITE_TAG)) {
        Text(stringResource(R.string.home_write))
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    OutlinedButton(onClick = onStop, modifier = Modifier.testTag(STOP_TAG)) {
        Text(stringResource(R.string.home_stop))
    }
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
        Box(Modifier.weight(1f)) { GroundingLine(insight.grounded, insight.cached, groundingDetails) }
        context.track?.let { track -> ReportAction { actions.onReport(track, insight) } }
    }
    if (!insight.complete) Quiet(stringResource(R.string.home_insight_incomplete))
    context.rewriteFailed?.let {
        if (it == PipelineHost.STOPPED) {
            Quiet(stringResource(R.string.home_rewrite_stopped))
        } else {
            Quiet(stringResource(R.string.home_rewrite_failed, failureText(it)))
        }
    }
    if (context.offers.rewrite) {
        // The card paid for, written from earlier evidence or with another AI service, or kept
        // after its rewrite failed or was stopped: a tap writes it again.
        Quiet(stringResource(R.string.home_rewrite_offered))
        OutlinedButton(onClick = actions.onWrite, modifier = Modifier.testTag(WRITE_TAG)) {
            Text(stringResource(R.string.home_rewrite))
        }
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

/** The test tag of Write and Rewrite. */
const val WRITE_TAG: String = "write"

/** The test tag of Stop. */
const val STOP_TAG: String = "stop"
