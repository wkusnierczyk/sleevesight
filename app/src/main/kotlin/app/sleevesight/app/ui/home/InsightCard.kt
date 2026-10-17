package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.synthesis.GroundedText

/**
 * *Why it's interesting*: the insight in each of its states (architecture, *Errors and empty
 * states*). Model prose is plain text: its only interactive parts are the citation chips, built
 * from the evidence ids the app assigned, each scrolling to its line ([onCite]). Write and
 * Rewrite go to the pipeline as a tap ([onWrite]); whether a tap writes is the pipeline's to say.
 */
@Composable
internal fun InsightCard(
    insight: InsightState,
    context: InsightContext,
    onWrite: () -> Unit,
    onCite: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_insight_title), style = MaterialTheme.typography.titleMedium)
            InsightBody(insight, context, onWrite, onCite)
        }
    }
}

@Composable
private fun InsightBody(
    insight: InsightState,
    context: InsightContext,
    onWrite: () -> Unit,
    onCite: (String) -> Unit,
) {
    when (insight) {
        is InsightState.Idle -> {
            if (insight.canWrite) {
                Quiet(stringResource(R.string.home_insight_tap_write))
                Button(onClick = onWrite, modifier = Modifier.testTag(WRITE_TAG)) {
                    Text(stringResource(R.string.home_write))
                }
            } else {
                Quiet(stringResource(idleReason(context)))
            }
        }

        is InsightState.Writing -> {
            val paragraphs = paragraphsOf(insight.partial)
            if (paragraphs.isEmpty()) Quiet(stringResource(R.string.home_insight_writing))
            for (paragraph in paragraphs) Prose(uncheckedProse(paragraph, context, onCite))
        }

        is InsightState.Written -> Written(insight, context, onWrite, onCite)
        InsightState.NoEvidence -> Quiet(stringResource(R.string.home_insight_no_evidence))
        is InsightState.Failed -> Quiet(stringResource(R.string.home_insight_failed, failureText(insight.reason)))
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
    onWrite: () -> Unit,
    onCite: (String) -> Unit,
) {
    val paragraphs = insight.grounded.paragraphs
    if (paragraphs.isEmpty()) Prose(AnnotatedString(insight.grounded.text))
    for (paragraph in paragraphs) Prose(checkedProse(paragraph, context, onCite))
    Meta(insight.grounded, insight.cached)
    if (!insight.complete) Quiet(stringResource(R.string.home_insight_incomplete))
    context.rewriteFailed?.let { Quiet(stringResource(R.string.home_rewrite_failed, failureText(it))) }
    if (context.rewrite) {
        // The card paid for, written from earlier evidence or with another AI service: a tap
        // writes it again.
        Quiet(stringResource(R.string.home_rewrite_offered))
        OutlinedButton(onClick = onWrite, modifier = Modifier.testTag(WRITE_TAG)) {
            Text(stringResource(R.string.home_rewrite))
        }
    }
}

/** The grounding check's counts, as the simulator gives them. */
@Composable
private fun Meta(
    grounded: GroundedText,
    cached: Boolean,
) {
    val counts = grounded.counts
    val parts =
        buildList {
            add(stringResource(R.string.home_meta_grounded, counts.ok + counts.hedge, counts.total))
            if (counts.model > 0) add(stringResource(R.string.home_meta_model, counts.model))
            if (counts.uncited > 0) add(stringResource(R.string.home_meta_uncited, counts.uncited))
            if (counts.unknown > 0) add(stringResource(R.string.home_meta_unknown, counts.unknown))
            if (cached) add(stringResource(R.string.home_meta_kept))
        }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
