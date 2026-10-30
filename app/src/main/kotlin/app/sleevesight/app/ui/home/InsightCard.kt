package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
 * insight, its footer ([Footer], #671), each part on its own line: *As written previously.* with what
 * follows from it, for an insight written earlier; [GroundingLine], a note, or with
 * [groundingDetails] (*Show grounding details*, #437) the grounding check's counts in full; and
 * *Report a problem* (#390), which hands the insight and its track to [InsightActions.onReport].
 *
 * No container of its own (#609): the heading and the text sit on Home's background with the rest.
 * At the card's end, in every state, the split button (#615, [WriteSplitButton]): the card's action
 * ([cardOffer]) and the menu of *Reactive*'s modes, from [writing]; its arrow cannot be used while
 * that is null, before the settings' first read. Beside a written insight, the menu begins with
 * *Look again* (#617), enabled while it can be tapped ([offersLookAgain]).
 *
 * With a written insight, at the end of the heading's row, *Read aloud* (#325, #725,
 * [ReadAloudButton]) when there is [readAloud]: a plain icon button, by the text it reads rather than
 * by the split button, where it was taken for part of the card's actions; it makes no AI call and
 * carries no **AI** mark. With no insight written, the heading stands alone. Its width is kept first:
 * at a large font size the heading wraps in what is left.
 */
@Suppress("LongParameterList") // The insight, what it is shown with, and its actions, each on its own.
@Composable
internal fun InsightCard(
    insight: InsightState,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean = false,
    writing: WritingChoice? = null,
    readAloud: ReadAloudControl? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val read = readAloud?.takeIf { insight is InsightState.Written }
        val title = @Composable { modifier: Modifier ->
            Text(stringResource(R.string.home_insight_title), modifier, style = MaterialTheme.typography.titleMedium)
        }
        if (read == null) {
            title(Modifier)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Measured after the button, in the width it leaves.
                title(Modifier.weight(1f))
                ReadAloudButton(read, READ_ALOUD_TAG)
            }
        }
        InsightBody(insight, context, actions, groundingDetails)
        val lookAgain =
            (insight as? InsightState.Written)?.let {
                LookAgainItem(
                    offersLookAgain(context),
                    actions.onLookAgain,
                )
            }
        WriteSplitButton(cardOffer(insight, context), actions.onWrite, actions.onStop, writing, lookAgain)
    }
}

// Why the card shows no insight, in words that match the split button's label (#625): with Write
// offered, why automatic writing left this track, where it did.
@Composable
private fun IdleNote(
    insight: InsightState.Idle,
    context: InsightContext,
) {
    val auto = automaticMode(context)
    val line =
        when {
            // The track came to play while *Saver* had paused automatic writing, which opening
            // Sleevesight has resumed (#203, #625).
            insight.canWrite && context.offers.paused -> stringResource(R.string.home_insight_resumed)
            // *On track skip (auto)*: the track followed the last one by itself.
            insight.canWrite && context.offers.followed -> stringResource(R.string.home_insight_followed)
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
        // Write offered again after a call that failed or was stopped: a new paid call, on a tap
        // only, from the split button, which the line says in the same breath.
        is InsightState.Failed -> {
            val reason = failureText(insight.reason)
            val line = if (context.offers.retry) R.string.home_insight_failed_retry else R.string.home_insight_failed
            Quiet(stringResource(line, reason))
        }

        InsightState.Stopped -> {
            Quiet(stringResource(R.string.home_insight_stopped))
            if (context.offers.retry) Quiet(stringResource(R.string.home_insight_retry))
        }
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
    Footer(insight, context, actions, groundingDetails)
}

/**
 * The footer under a written insight (#671): each part on its own line, at the start, top to
 * bottom, so each wraps on its own at a large font size and a screen reader reads them in order:
 * - *As written previously.* ([writtenBefore]), with what follows from it on the same line;
 * - the grounding line ([GroundingLine]): the note on the sentences underlined in red, or, with
 *   [groundingDetails] (developer mode, inside its mark), the grounding check's counts. The note
 *   stays where the counts are, which take its place in developer mode: under the line about the
 *   insight's history and above *Report a problem*, the action it leads to;
 * - that the reply stopped early, for an incomplete insight;
 * - *Report a problem* ([ReportAction]), in the theme's error colour.
 */
@Composable
private fun Footer(
    insight: InsightState.Written,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        writtenBefore(insight, context)?.let { Quiet(it) }
        // Not *Kept from before.*: the line above says so.
        GroundingLine(insight.grounded, cached = false, details = groundingDetails)
        if (!insight.complete) Quiet(stringResource(R.string.home_insight_incomplete))
        context.track?.let { track -> ReportAction { actions.onReport(track, insight) } }
    }
}

/**
 * The footer's first line (#671): *As written previously.* for an insight not written in this run
 * but earlier, on an earlier play of the track (from the cache, or the card paid for shown in place
 * of an insight the run lacks, #606), or before a *Look again*, a settings change or a rewrite that
 * failed; then, on the same line, what follows from it, in the order it happened: that a rewrite
 * failed or was stopped, what *Look again* found or is looking up (#617, [lookAgainLine]), and that
 * *Rewrite insight* writes
 * it with the updated settings, while offered (#615, #625; not for what a look again found, whose
 * own words say what *Rewrite insight* uses). Null for an insight just written, with nothing to
 * follow.
 */
@Composable
private fun writtenBefore(
    insight: InsightState.Written,
    context: InsightContext,
): String? {
    val after =
        buildList {
            // In the order things happened: a rewrite that failed, then what a later look again found.
            context.rewriteFailed?.let { add(rewriteFailure(it)) }
            lookAgainLine(context)?.let { add(stringResource(it)) }
            if (offersRewrite(context) && !context.offers.somethingNew) {
                add(stringResource(R.string.home_rewrite_offered))
            }
        }
    val kept = insight.cached || context.cardShown
    if (!kept && after.isEmpty()) return null
    return (listOf(stringResource(R.string.home_written_before)) + after).joinToString(" ")
}

// Why the card's rewrite ended without an insight: stopped, or failed and why.
@Composable
private fun rewriteFailure(why: String): String =
    if (why == PipelineHost.STOPPED) {
        stringResource(R.string.home_rewrite_stopped)
    } else {
        stringResource(R.string.home_rewrite_failed, failureText(why))
    }

@Composable
private fun Prose(text: AnnotatedString) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
