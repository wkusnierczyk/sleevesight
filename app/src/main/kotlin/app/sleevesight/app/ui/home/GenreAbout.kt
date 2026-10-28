package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState

// A genre's panel, above its table (#523): what the genre is, and why this track has it.

/**
 * What the genre [genre] is and why [track] has it, as [shown]'s notes give them (#523; `design/design.md`,
 * *Genre labels*), at the top of the genre's panel, in this order: the definition, from Wikipedia,
 * with its credit ("From Wikipedia", linking the article, and its licence); then *Why is this …?*,
 * a button carrying the **AI** mark ([AiLabel]), or, once asked for, the reason, under the question
 * as a heading, checked and cited as the insight is: its citations chips that go to their lines
 * ([PanelNotes.onCite]), its grounding note under it. Neither shows when [PanelNotes.notes] has
 * none: no definition for a genre with no article (or Wikipedia off), no button for a genre only
 * MusicBrainz gives, with
 * *Show insights* off or no AI service ready. A reason still being written reads its `[AI]` as the
 * settings allow it ([PanelNotes.modelAllowed]).
 */
@Composable
internal fun GenreAbout(
    track: TrackState,
    genre: String,
    shown: PanelNotes,
) {
    val notes = shown.notes
    val onCite = shown.onCite
    val modelAllowed = shown.modelAllowed
    // A new subscription only when what the reason's key is made of changes, not on every state of
    // the track (a source landing, labels joining).
    val reason by remember(track.normalized, track.evidence?.fingerprint, track.genres, genre, notes) {
        notes.reason(track, genre)
    }.collectAsState(GenreReason.None)
    val label =
        track.genres.all
            .firstOrNull { it.genre.identity == genre }
            ?.genre
            ?.label ?: return
    val question = stringResource(R.string.genre_why, inSentence(label))
    // Its own state per genre: another genre's definition never shows while this one's is looked up.
    key(genre, notes) {
        val definition by remember { notes.definition(genre) }.collectAsState(null)
        definition?.let { Definition(it) }
    }
    when (val shown = reason) {
        GenreReason.None -> Unit
        GenreReason.Offered -> WhyButton(question) { notes.explain(track, genre) }
        is GenreReason.Shown ->
            Reason(shown.state, question, InsightContext(track, true, true, modelAllowed, InsightOffers()), onCite) {
                notes.explain(track, genre)
            }
    }
}

// The definition, then its credit: the article linked, and the licence.
@Composable
private fun Definition(definition: GenreDefinition) {
    Column(modifier = Modifier.testTag(GENRE_DEFINITION_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(definition.text, style = MaterialTheme.typography.bodyMedium)
        LinkedText(
            listOf(
                TextPart.Link(stringResource(R.string.genre_definition_from), definition.url),
                TextPart.Words(" · "),
                TextPart.Link(stringResource(R.string.genre_definition_licence), WIKIPEDIA_LICENCE),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// *Why is this …?*: as tall as the genre chips, answering a tap across 48 dp, with the AI mark.
@Composable
private fun WhyButton(
    question: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick,
        Modifier.testTag(GENRE_WHY_TAG).heightIn(min = HEIGHT),
        shape = RoundedCornerShape(CORNER),
        contentPadding = PADDING,
    ) { AiLabel(question) }
}

// The reason asked for, in [state], under the [question]; offered again after a failure.
@Composable
private fun Reason(
    state: InsightState,
    question: String,
    context: InsightContext,
    onCite: (String) -> Unit,
    again: () -> Unit,
) {
    Column(modifier = Modifier.testTag(GENRE_REASON_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            question,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            is InsightState.Writing -> {
                for (paragraph in paragraphsOf(state.partial)) UncheckedProse(paragraph, context, onCite)
                Quiet(stringResource(R.string.genre_why_writing))
            }

            is InsightState.Written -> {
                for (paragraph in state.grounded.paragraphs) {
                    CheckedProse(paragraph, state.grounded, context.track?.evidence, onCite)
                }
                GroundingLine(state.grounded, state.cached, details = false)
                if (!state.complete) Quiet(stringResource(R.string.genre_why_incomplete))
            }

            InsightState.NoEvidence -> {
                Quiet(stringResource(R.string.genre_why_no_evidence))
            }

            is InsightState.Failed -> {
                Quiet(stringResource(R.string.genre_why_failed, failureText(state.reason)))
            }

            // Neither comes from a reason: not offered, and not stopped.
            is InsightState.Idle, InsightState.Stopped -> Unit
        }
    }
    if (state is InsightState.Failed) WhyButton(question, again)
}

@Composable
private fun Quiet(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * [label] as a sentence names it: each word in lower case ("Hip-Hop" is "hip-hop"), but for one
 * whose label keeps a capital past its first letter (an acronym, "UK"; "RnB") or that is a single
 * letter ("R&B").
 */
internal fun inSentence(label: String): String =
    WORD.replace(label) { match ->
        val word = match.value
        if (word.length == 1 || word.drop(1).any(Char::isUpperCase)) word else word.lowercase()
    }

private val WORD = Regex("\\p{L}+")

/** The address of the licence of Wikipedia's text, which the definition's credit links. */
internal const val WIKIPEDIA_LICENCE: String = "https://creativecommons.org/licenses/by-sa/4.0/"

/** The test tags of a genre's definition, its *Why is this …?* and its reason. */
const val GENRE_DEFINITION_TAG: String = "genre-panel:definition"
const val GENRE_WHY_TAG: String = "genre-panel:why"
const val GENRE_REASON_TAG: String = "genre-panel:reason"

private val HEIGHT = 32.dp
private val CORNER = 16.dp
private val PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
