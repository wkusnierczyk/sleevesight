package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.Unavailability
import app.sleevesight.app.ui.GENRE_LABELS_DOCS_URL
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.pipeline.AiSearches
import app.sleevesight.core.pipeline.GenreLists
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.synthesis.GenreListMode

// *Why is this …?* once it is asked for, or where it cannot be (#523, #762): the reason under its
// question, the line while the AI service is asked, a failed call's *Retry*, and the button disabled
// with why under it.

// *Why is this …?* disabled (#762), and why under it ([why]), in the panel's quiet note style, its
// links opening the settings ([openSettingsAt]) or, for a genre the AI's list does not have, the
// docs; [genre] is the genre's name as the question gives it.
@Composable
internal fun ReasonUnavailable(
    question: String,
    genre: String,
    why: Unavailability,
    openSettingsAt: (SettingsTarget) -> Unit,
) {
    Column(Modifier.testTag(GENRE_WHY_UNAVAILABLE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WhyButton(question, enabled = false) {}
        when (why) {
            Unavailability.NO_SERVICE -> {
                val target = SettingsTarget.aiServices
                val link =
                    TextPart.Action(stringResource(R.string.home_settings_ai_services_link), target.linkTag) {
                        openSettingsAt(target)
                    }
                QuietLinks(linkedParts(stringResource(R.string.genre_why_no_service), link))
            }

            Unavailability.CANNOT_LIST -> {
                QuietNote(stringResource(R.string.genre_why_no_ai))
                QuietLinks(enableParts(GenreLists.MODES, openSettingsAt))
            }

            // Once tapped (#1059): the list lacks it, only MusicBrainz gives it, and *See why.* links
            // the docs' *Genre labels*, read as a link.
            Unavailability.NOT_LISTED -> {
                val link = TextPart.Link(stringResource(R.string.genre_why_not_listed_link), GENRE_LABELS_DOCS_URL)
                // The name as a part of its own, not formatted in, so nothing in it reads as a placeholder (#1091).
                QuietLinks(linkedParts(stringResource(R.string.genre_why_not_listed), TextPart.Words(genre), link))
            }
        }
    }
}

/**
 * "Enable AI service knowledge." (#762): what to switch on so that the AI's genre list can run, each
 * of [modes] named as its setting is and linked to where it is ([openSettingsAt]), several joined by
 * "or" in their order. Built from the modes the app has (`GenreLists.MODES`): "Enable AI service
 * knowledge or AI web search.", the second (#801) a link to *AI web search* under *Insights*.
 */
@Composable
internal fun enableParts(
    modes: List<GenreListMode>,
    openSettingsAt: (SettingsTarget) -> Unit,
): List<TextPart> {
    val links =
        modes.map { mode ->
            when (mode) {
                GenreListMode.KNOWLEDGE -> {
                    val target = SettingsTarget.sources
                    TextPart.Action(stringResource(R.string.settings_source_model), target.linkTag) {
                        openSettingsAt(target)
                    }
                }

                // #801: *AI web search*, under *Insights*.
                GenreListMode.WEB -> {
                    val target = SettingsTarget.aiWebSearch
                    TextPart.Action(stringResource(R.string.settings_ai_web_search), target.linkTag) {
                        openSettingsAt(target)
                    }
                }
            }
        }
    val or = stringResource(R.string.genre_why_or)
    val between = or.substringAfter("%1\$s").substringBefore("%2\$s")
    val joined =
        links.flatMapIndexed { at, link ->
            if (at ==
                0
            ) {
                listOf(link)
            } else {
                listOf(TextPart.Words(between), link)
            }
        }
    val enable = stringResource(R.string.genre_why_enable)
    return listOf(TextPart.Words(enable.substringBefore("%1\$s"))) + joined +
        TextPart.Words(enable.substringAfter("%1\$s"))
}

// How a reason's citation of its line [evidence] calls [it][String] shows, and opens: by the id
// [sources] (*Sources*' lines) lists that line under ([shownLine], #1205), else its own.
private fun shownAs(
    evidence: EvidenceSet?,
    sources: EvidenceSet?,
): (String) -> String = { id -> shownLine(id, evidence, sources) ?: id }

// A paragraph of a reason still being written, its citations shown and opened as [shownAs] has them.
@Composable
private fun PartialProse(
    paragraph: String,
    context: InsightContext,
    actions: ReasonActions,
) {
    val shownAs = shownAs(context.track?.evidence, actions.sources)
    UncheckedProse(paragraph, context, { id -> actions.onCite(shownAs(id)) }, label = shownAs)
}

// What a reason shown does: where a citation goes ([onCite]), its *Read aloud* ([readAloud], null for
// none), asking again ([again]: a failed call's *Retry*), where the focus goes as *Retry* gives
// way to the loading line ([afterRetry]), where the could-not line's links open ([openSettingsAt]);
// and the lines *Sources* shows ([sources]), whose ids its citations show and open, by the page or
// the words they cite (#1205, [shownLine]): a reason's own numbering is its evidence's, not theirs.
@Suppress("LongParameterList") // What each part of a reason does, and where its citations point.
internal class ReasonActions(
    val onCite: (String) -> Unit,
    val readAloud: ReadAloudControl?,
    val again: () -> Unit,
    val afterRetry: () -> Unit,
    val openSettingsAt: (SettingsTarget) -> Unit,
    val sources: EvidenceSet? = null,
)

// The reason asked for ([shown]), under the [question] (#523, #762): while the AI service is asked
// and nothing is written yet, "Looking for an explanation…" in the button's place; written, its text,
// then "by {model}" ("by {model}, with web search" for one from web pages, #695), the grounding note
// and, kept from before, when it was written (#851), with *Read aloud* at the end of those rows; one
// that cites no source, only the model's own knowledge, says so first, under the question (#695);
// the AI could not explain it, the button disabled with why under it, and what to switch on that
// could ([GenreReason.Shown.couldHelp]); a failed call, why, in the error colour, with *Retry*.
@Composable
internal fun ReasonShown(
    shown: GenreReason.Shown,
    question: String,
    context: InsightContext,
    actions: ReasonActions,
) {
    when (val state = shown.state) {
        is InsightState.Writing ->
            if (state.partial.isBlank()) {
                Looking()
            } else {
                Answer(question) {
                    for (paragraph in paragraphsOf(state.partial)) PartialProse(paragraph, context, actions)
                    // With the turning record before it (#785).
                    Busy(
                        stringResource(R.string.genre_why_writing),
                        MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        is InsightState.Written ->
            Answer(question) {
                // Read from its citations, not its words (#695): no sentence cites a source or a page.
                if (state.grounded.fromModelAlone) {
                    QuietNote(stringResource(R.string.genre_why_from_model), Modifier.testTag(GENRE_WHY_FROM_MODEL_TAG))
                }
                // A reason asked again with web search cites the pages it found too (#801).
                val evidence = state.searched ?: context.track?.evidence
                // Each citation shown, and opened, by the id *Sources* lists its line under (#1205).
                val shownAs = shownAs(evidence, actions.sources)
                for (paragraph in state.grounded.paragraphs) {
                    CheckedProse(
                        paragraph,
                        state.grounded,
                        evidence,
                        { id -> actions.onCite(shownAs(id)) },
                        label = shownAs,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReasonCredits(shown, state, evidence, Modifier.weight(1f))
                    actions.readAloud?.let { ReadAloudButton(it, GENRE_READ_ALOUD_TAG) }
                }
                if (!state.complete) QuietNote(stringResource(R.string.genre_why_incomplete))
            }

        // The AI could not explain it (#762), then what is off that could (#695). Not for a genre the
        // AI's list does not have, which says so (#1059, [ReasonUnavailable]).
        InsightState.NoEvidence -> NotFound(question, shown.couldHelp, actions.openSettingsAt)

        is InsightState.Failed ->
            RetryLine(
                // The web search AI web search makes failed (#801): that, as the whole line.
                if (state.reason == AiSearches.WEB_SEARCH_FAILED) {
                    stringResource(R.string.home_ai_web_search_failed)
                } else {
                    stringResource(R.string.genre_why_failed, failureText(state.reason))
                },
                source = "",
                onRetry = actions.again,
                modifier = Modifier.testTag(GENRE_WHY_FAILED_TAG),
                afterRetry = actions.afterRetry,
                action = stringResource(R.string.home_ai_retry_action),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                outlined = true,
            )

        // Neither comes from a reason: not offered, and not stopped.
        is InsightState.Idle, InsightState.Stopped -> Unit
    }
}

// Under a written reason ([state]): "by {model}", the grounding note, which of its citations of
// [evidence] it translated from the track's language (#902), and, kept from before, when it was
// written (#851).
@Composable
private fun ReasonCredits(
    shown: GenreReason.Shown,
    state: InsightState.Written,
    evidence: EvidenceSet?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // "with web search" for a reason from web pages (#695): asked again with them, and citing one.
        val fromWeb = state.searched != null && state.grounded.citesSource
        val by = if (fromWeb) R.string.genre_why_by_web else R.string.genre_why_by
        shown.model?.let { SearchCredit(stringResource(by, it)) }
        GroundingLine(state.grounded, details = false)
        TranslatedLine(state.grounded, evidence)
        shown.keptAt?.let { KeptLine(it) }
    }
}

// *Why is this …?* disabled once the AI, asked for the reason, could not explain it (#762), "AI
// could not find an explanation." under it, then "Enable {what}." naming what is off and could
// explain it ([couldHelp], #695), each linked to where it is set ([openSettingsAt]); no such line
// with both on.
@Composable
private fun NotFound(
    question: String,
    couldHelp: List<GenreListMode>,
    openSettingsAt: (SettingsTarget) -> Unit,
) {
    Column(Modifier.testTag(GENRE_WHY_UNAVAILABLE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WhyButton(question, enabled = false) {}
        QuietNote(stringResource(R.string.genre_why_not_found))
        if (couldHelp.isNotEmpty()) QuietLinks(enableParts(couldHelp, openSettingsAt))
    }
}

// The reason under the [question] as its heading.
@Composable
private fun Answer(
    question: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.testTag(GENRE_REASON_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            question,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

// In the button's place while the AI service is asked (#762): the turning record, then "Looking for
// an explanation…" in the panel's quiet note style (a screen reader reads the text, not the record).
@Composable
private fun Looking() =
    Busy(
        stringResource(R.string.genre_why_looking),
        MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textModifier = Modifier.testTag(GENRE_WHY_LOOKING_TAG),
    )

// [parts] in the panel's quiet note style, its links opening what they name.
@Composable
private fun QuietLinks(parts: List<TextPart>) =
    LinkedText(
        parts,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * The test tags of *Why is this …?* disabled with why under it, of "Looking for an explanation…",
 * and of a failed call's line with *Retry* (#762).
 */
const val GENRE_WHY_UNAVAILABLE_TAG: String = "genre-panel:why-unavailable"
const val GENRE_WHY_LOOKING_TAG: String = "genre-panel:why-looking"
const val GENRE_WHY_FAILED_TAG: String = "genre-panel:why-failed"

/** The test tag of the line above a reason that cites no source, only the model's knowledge (#695). */
const val GENRE_WHY_FROM_MODEL_TAG: String = "genre-panel:why-from-model"
