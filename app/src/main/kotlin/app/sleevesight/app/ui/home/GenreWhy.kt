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
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.pipeline.GenreLists
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.synthesis.GenreListMode

// *Why is this …?* once it is asked for, or where it cannot be (#523, #762): the reason under its
// question, the line while the AI service is asked, a failed call's *Retry*, and the button disabled
// with why under it.

// *Why is this …?* disabled (#762), and why under it ([why]), in the panel's quiet note style, its
// links opening the settings ([openSettingsAt]).
@Composable
internal fun ReasonUnavailable(
    question: String,
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

            Unavailability.NOT_LISTED -> {
                QuietNote(stringResource(R.string.genre_why_not_found))
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

// What a reason shown does: where a citation goes ([onCite]), its *Read aloud* ([readAloud], null for
// none), asking again ([again]: a failed call's *Retry*), and where the focus goes as *Retry* gives
// way to the loading line ([afterRetry]).
internal class ReasonActions(
    val onCite: (String) -> Unit,
    val readAloud: ReadAloudControl?,
    val again: () -> Unit,
    val afterRetry: () -> Unit,
)

// The reason asked for ([shown]), under the [question] (#523, #762): while the AI service is asked
// and nothing is written yet, "Looking for an explanation…" in the button's place; written, its text,
// then "by {model}", with *Read aloud* at the end of that row; the AI could not explain it, the
// button disabled with why under it; a failed call, why, in the error colour, with *Retry*.
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
                    for (paragraph in paragraphsOf(state.partial)) UncheckedProse(paragraph, context, actions.onCite)
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
                for (paragraph in state.grounded.paragraphs) {
                    // A reason asked again with web search cites the pages it found too (#801).
                    CheckedProse(paragraph, state.grounded, state.searched ?: context.track?.evidence, actions.onCite)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        shown.model?.let { SearchCredit(stringResource(R.string.genre_why_by, it)) }
                        GroundingLine(state.grounded, state.cached, details = false)
                    }
                    actions.readAloud?.let { ReadAloudButton(it, GENRE_READ_ALOUD_TAG) }
                }
                if (!state.complete) QuietNote(stringResource(R.string.genre_why_incomplete))
            }

        // The AI could not explain it from the evidence (#762): as when its list does not have it.
        InsightState.NoEvidence -> ReasonUnavailable(question, Unavailability.NOT_LISTED) {}

        is InsightState.Failed ->
            RetryLine(
                stringResource(R.string.genre_why_failed, failureText(state.reason)),
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
