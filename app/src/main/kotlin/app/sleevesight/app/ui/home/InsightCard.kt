package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.ui.lab.LabCompare
import app.sleevesight.app.ui.lab.labSubject
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.report.ReportAction
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.synthesis.PromptKind

/**
 * *Key insights*: the insight in each of its states (architecture, *Errors and empty states*).
 * Model prose is plain text: its only interactive parts are the citation chips, built from the
 * evidence ids the app assigned, each scrolling to its line. Write, Rewrite and Stop go to the
 * pipeline as taps; whether a tap writes or stops is the pipeline's to say. Under a written
 * insight's text, its footer ([Footer], #671), each part on its own line: first the credit
 * (#959, #1162), *Written by {model}.* or *Rewritten by {model}.* with what else it was written with,
 * then what follows from it, with *Read aloud* at the row's end; when the evidence it was written from
 * holds nothing beyond what Home shows ([InsightState.Written.nothingBeyondHome], #1142), a quiet line
 * saying no more source information was found; [GroundingLine], a note, or with [groundingDetails]
 * (*Show grounding details*, #437) the grounding check's counts in full; and *Report the insight*
 * (#390), which hands the insight and its track to [InsightActions.onReport].
 *
 * No container of its own (#609): the heading and the text sit on Home's background with the rest.
 * At the card's end, in every state, the split button (#615, [WriteSplitButton]): the card's action
 * ([cardOffer]) and the menu of *Reactive*'s modes, from [writing]; its arrow cannot be used while
 * that is null, before the settings' first read. Beside a written insight, the menu begins with
 * *Look again* (#617), enabled while it can be tapped ([offersLookAgain]).
 *
 * With a written insight, at the end of the credit's row (#959), *Read aloud* (#325, #725,
 * [ReadAloudButton]) when there is [readAloud]: a plain icon button, by the text it reads rather than
 * by the split button, where it was taken for part of the card's actions; it makes no AI call and
 * carries no **AI** mark. The heading stands alone, as tall as the button, as it has always been, so
 * the insight does not move down when it is written. While it reads with the music paused though
 * *Reduce volume* is chosen, on a speaker whose volume the phone cannot set (#803), a quiet note under
 * the credit's row says so.
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
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(INSIGHT_HEADING_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.home_insight_title), style = MaterialTheme.typography.titleMedium)
        }
        // A track the last *Clear history* holds (#1112): its findings were cleared and nothing is
        // asked, so the card says so, with *Look up again*, rather than why nothing is written yet;
        // a model found unavailable (#540) is still said, under it, with its link.
        if (LocalTrackCleared.current && insight is InsightState.Idle) {
            ClearedInsight(actions.onLookUpAgain)
            context.unavailable?.let { ModelUnavailable(it, actions.onSettings) }
        } else {
            InsightBody(insight, context, actions, groundingDetails, readAloud)
        }
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
            else -> null
        }
    val reason = idleReason(context)
    // The evidence or the insight on its way, with the turning record before it (#785); one note in
    // every case, so its node lasts as the record comes and goes.
    Busy(
        line ?: stringResource(reason),
        MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        record = line == null && reason in ON_ITS_WAY,
    )
}

@Composable
private fun InsightBody(
    insight: InsightState,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean,
    readAloud: ReadAloudControl?,
) {
    // A model found unavailable (#540): said in place of why nothing is written, or of the failure
    // it caused; the split button offers no Write, which would fail again. With an insight, it is
    // said under it, last in its footer (#911).
    val unavailable = context.unavailable
    if (unavailable != null && withoutInsight(insight)) {
        ModelUnavailable(unavailable, actions.onSettings)
        return
    }
    when (insight) {
        is InsightState.Idle -> IdleNote(insight, context)

        is InsightState.Writing -> {
            for (paragraph in paragraphsOf(insight.partial)) {
                UncheckedProse(paragraph, context, actions.onCite, insight.held)
            }
            // *Writing…* with the turning record, for the whole write, under what is written so far
            // (#785); while the split button's Stop can end the call, what stopping costs under it (#615).
            Busy(
                stringResource(R.string.home_insight_writing),
                MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (context.offers.stop) Quiet(stringResource(R.string.home_insight_writing_stop))
        }

        is InsightState.Written -> Written(insight, context, actions, groundingDetails, readAloud)
        InsightState.NoEvidence -> Quiet(stringResource(R.string.home_insight_no_evidence))
        // Write offered again after a call that failed or was stopped: a new paid call, on a tap
        // only, from the split button, which the line says in the same breath.
        is InsightState.Failed -> {
            val reason = failedReason(insight.reason, groundingDetails)
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
    readAloud: ReadAloudControl?,
) {
    val paragraphs = insight.grounded.paragraphs
    if (paragraphs.isEmpty()) Prose(AnnotatedString(insight.grounded.text))
    for (paragraph in paragraphs) {
        CheckedProse(paragraph, insight.grounded, context.track?.evidence, actions.onCite, insight.held)
    }
    Footer(insight, context, actions, groundingDetails, readAloud)
}

/**
 * The footer under a written insight (#671): each part on its own line, at the start, top to
 * bottom, so each wraps on its own at a large font size and a screen reader reads them in order:
 * - first, the credit (#959, #1162, [insightCredit]): *Written by {model}.*, or for an insight a
 *   rewrite wrote (*Rewrite insight*, #854, or an automatic write in place of another model's, #972),
 *   *Rewritten by {model}.*, with what else it was written with; then what follows from it on the same
 *   line ([afterCredit]); *Read aloud* ([readAloud]) at the row's end, and under the row, while it
 *   reads with the music paused in place of a lower volume (#803), that it does;
 * - while *Look again* looks the sources up, what it is doing ([lookingAgainLine]), with the turning
 *   record before it (#785);
 * - written from evidence that holds nothing beyond what Home shows (#1142), that no more source
 *   information was found; and a source not reached ([InsightPartial]), with its *Retry*, whose focus
 *   goes once tapped to *Report the insight* (#1121);
 * - the grounding line ([GroundingLine]): the note on the dimmed sentences, or, with
 *   [groundingDetails] (developer mode, inside its mark), the grounding check's counts. The note
 *   stays where the counts are, which take its place in developer mode: under the credit and above
 *   *Report the insight*, the action it leads to;
 * - which citations it translated from the track's language ([TranslatedLine], #902);
 * - that the reply stopped early, for an incomplete insight;
 * - *Report the insight* ([ReportAction]), in the theme's error colour, where the focus goes from the
 *   *Retry* of the line on a source not reached (#1121) as it goes;
 * - while the model is found unavailable (#540), that it is ([ModelUnavailable]), with its link to the
 *   settings (#911): last, just above the split button, whose Write it leaves disabled, and in place
 *   of what *Rewrite insight* would use, which is not offered then.
 */
@Composable
private fun Footer(
    insight: InsightState.Written,
    context: InsightContext,
    actions: InsightActions,
    groundingDetails: Boolean,
    readAloud: ReadAloudControl?,
) {
    Column(Modifier.fillMaxWidth()) {
        CreditRow(
            insightCredit(insight),
            Modifier.testTag(INSIGHT_BY_TAG),
            after = afterCredit(context),
            readAloud = readAloud?.let { { ReadAloudButton(it, READ_ALOUD_TAG) } },
            labKind = PromptKind.KEY,
        )
        if (readAloud?.pausedInstead == true) Quiet(stringResource(R.string.home_read_aloud_paused_instead))
        lookingAgainLine(context)?.let {
            Busy(
                stringResource(it),
                MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Written from evidence that holds nothing beyond what Home shows (#1142), in the card's quiet
        // note style, as the line on a source not reached under it is.
        if (insight.nothingBeyondHome) {
            Quiet(stringResource(R.string.home_insight_nothing_beyond), Modifier.testTag(INSIGHT_NOTHING_BEYOND_TAG))
        }
        // Its *Retry* goes once tapped, so it hands the focus to *Report the insight* under it, which
        // stays beside a written insight in either layout (#1121).
        val handoff = rememberFocusHandoff()
        val report = remember { FocusRequester() }
        context.unanswered?.let { InsightPartial(it) { handoff.moveTo(report) } }
        val reportModifier = Modifier.handoffTarget(handoff, report)
        // No *Kept from before.*: the credit says who wrote it.
        GroundingLine(insight.grounded, details = groundingDetails)
        TranslatedLine(insight.grounded, context.track?.evidence, insight.held)
        if (!insight.complete) Quiet(stringResource(R.string.home_insight_incomplete))
        context.track?.let { track -> ReportAction(reportModifier) { actions.onReport(track, insight) } }
        // The prompt lab's *Compare* (#1003), in the debug build only, after *Report the insight*.
        context.track?.let { track -> LabCompare(labSubject(PromptKind.KEY, track, insight)) }
        // Last, just above the split button's disabled Write, which it explains (#911).
        context.unavailable?.let { ModelUnavailable(it, actions.onSettings) }
    }
}

/**
 * What follows the credit on its line (#671, #1162), in the order it happened: that a rewrite failed
 * or was stopped (not that the service refused the model while it is found unavailable, which the
 * footer's last line says, #969: [rewriteFailureShown]), that *Look again* found nothing new (#617,
 * [lookAgainLine]), and, while *Rewrite insight* is offered (#615, #625), what it would rewrite the
 * insight with ([rewriteOfferLine], #854), what a look again found new included. What *Look again* is
 * looking up is not on this line but on its own under it (#785, [lookingAgainLine]).
 */
@Composable
private fun afterCredit(context: InsightContext): List<String> =
    buildList {
        rewriteFailureShown(context.rewriteFailed, context.unavailable)?.let { add(rewriteFailure(it)) }
        lookAgainLine(context)?.let { add(stringResource(it)) }
        rewriteOfferLine(context)?.let { add(it) }
    }

/**
 * Why a rewrite ended without an insight (#854): stopped ([PipelineHost.STOPPED]), or failed and why;
 * said after the credit, by the key insight's footer and by the items' (#911).
 */
@Composable
internal fun rewriteFailure(why: String): String =
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
private fun Quiet(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// The idle notes that say something is on its way, with the turning record (#785).
private val ON_ITS_WAY = setOf(R.string.home_insight_gathering, R.string.home_insight_preparing)

/** The test tag of the row of the *Key insights* heading, with *Read aloud* at its end. */
const val INSIGHT_HEADING_TAG: String = "insight-heading"

/** The test tag of the line under an insight written from nothing beyond what Home shows (#1142). */
const val INSIGHT_NOTHING_BEYOND_TAG: String = "insight-nothing-beyond"
