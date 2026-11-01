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
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.speech.Reading
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind

// A genre's panel, above its table (#523): what the genre is, and why this track has it.

/**
 * What the genre [genre] is and why [track] has it, as [shown]'s notes give them (#523; `design/design.md`,
 * *Genre labels*), at the top of the genre's panel, in this order: the definition, from Wikipedia,
 * in each of its states ([GenreDefinitionNote], #786: looking, found with its credit, failed with
 * *Retry*, or none with *Search with AI* from [search]; nothing with Wikipedia off); then *Why is
 * this …?*, a button carrying the **AI** mark ([AiLabel]), or, once asked for, the reason, under the
 * question as a heading, checked and cited as the insight is: its citations chips that go to their
 * lines ([PanelNotes.onCite]), its grounding note under it. No button with *Show insights* off, no
 * evidence is in. [afterRetry] takes the focus as a failed lookup's *Retry*, or a failed reason's,
 * gives way to the loading line. While the AI service is asked (#762: the AI's genre list for a
 * genre only MusicBrainz gives, then the reason), "Looking for an explanation…" in the button's
 * place; the reason under it is credited "by {model}". Disabled, with why under it ([ReasonUnavailable]):
 * with no AI service ready, a link to *Settings → AI services*; for a genre only MusicBrainz gives,
 * when the AI's genre list cannot run, what to switch on, linked; when the AI could not explain it
 * (the list does not have it, or the evidence cannot support it), "AI could not find an
 * explanation.". A reason still being written reads its `[AI]` as the settings allow it
 * ([PanelNotes.modelAllowed]). A written reason has *Read aloud* (#325, [ReadAloudButton]) at the
 * end of its credit's row, which reads the question, then the reason ([PanelNotes.readAloud]).
 */
@Composable
internal fun GenreAbout(
    track: TrackState,
    genre: String,
    shown: PanelNotes,
    search: PanelSearch = PanelSearch(),
    afterRetry: () -> Unit = {},
) {
    val notes = shown.notes
    val onCite = shown.onCite
    val modelAllowed = shown.modelAllowed
    val reader = shown.readAloud
    val openSettingsAt = shown.openSettingsAt
    // A new subscription only when what the reason's key is made of changes, not on every state of
    // the track (a source landing, labels joining).
    val reason by remember(track.normalized, track.evidence?.fingerprint, track.genres, genre, notes) {
        notes.reason(track, genre)
    }.collectAsState(GenreReason.None)
    val named =
        track.genres.all
            .firstOrNull { it.genre.identity == genre }
            ?.genre ?: return
    val question = stringResource(R.string.genre_why, inSentence(named.label))
    GenreDefinitionNote(named, notes, search, afterRetry)
    val explain = { notes.explain(track, genre) }
    when (val shown = reason) {
        GenreReason.None -> Unit
        GenreReason.Offered -> WhyButton(question, onClick = explain)
        is GenreReason.Unavailable -> ReasonUnavailable(question, shown.why, openSettingsAt)
        is GenreReason.Shown -> {
            val readAloud =
                readAloudControl(reader, Reading.genreReason(genre)) {
                    // Read aloud follows the reason as the notes give it, whatever screen shows.
                    (shown.state as? InsightState.Written)?.let { written ->
                        reader?.readGenreReason(track, genre, question, written, notes.reason(track, genre))
                    }
                }
            ReasonShown(
                shown,
                question,
                InsightContext(track, true, true, modelAllowed, InsightOffers()),
                ReasonActions(onCite, readAloud, explain, afterRetry),
            )
        }
    }
}

/**
 * What the genre [genre] is, as [notes] give it (#523, #786; `design/design.md`, *Genre labels*), in
 * the definition's place:
 *
 * - looking: the turning record, then "Looking on Wikipedia…" in the panel's quiet note style
 *   ([Busy]: a screen reader reads the text, not the record);
 * - found: the definition from Wikipedia, with its credit;
 * - failed: "Wikipedia could not be reached." in the error colour, *Retry* (outlined) at the right of
 *   the line ([RetryLine]), which asks Wikipedia again ([GenreNotes.retryDefinition]) and runs
 *   [afterRetry], as the line gives way to the loading line;
 * - none (#663): it says so, then *Search with AI* for a definition ([search], [SearchKind.DEFINITION],
 *   #773's block), whose answer shows under a rule, credited to the model;
 * - nothing with Wikipedia or *Show genres* off.
 *
 * At the top of a genre's body ([GenreAbout]), and alone under a genre *Search with AI* named (#773),
 * which no source's tags give.
 */
@Composable
internal fun GenreDefinitionNote(
    genre: GenreLabel,
    notes: GenreNotes,
    search: PanelSearch = PanelSearch(),
    afterRetry: () -> Unit = {},
) {
    // Its own state per genre: another genre's definition never shows while this one's is looked up.
    key(genre.identity, notes) {
        val definition by remember { notes.definition(genre.identity) }.collectAsState(null)
        when (val state = definition) {
            DefinitionState.Looking ->
                Busy(
                    stringResource(R.string.genre_definition_looking),
                    MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textModifier = Modifier.testTag(GENRE_DEFINITION_LOOKING_TAG),
                )
            is DefinitionState.Found -> Definition(state.definition)
            DefinitionState.Failed ->
                RetryLine(
                    stringResource(R.string.genre_definition_failed),
                    stringResource(R.string.genre_definition_source),
                    onRetry = { notes.retryDefinition(genre.identity) },
                    modifier = Modifier.testTag(GENRE_DEFINITION_FAILED_TAG),
                    afterRetry = afterRetry,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    outlined = true,
                )
            DefinitionState.NotFound ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoDefinition()
                    AiSearchBlock(search, SearchKind.DEFINITION, genre) { answer, _ ->
                        if (answer is SearchAnswer.Definition) {
                            Text(answer.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            null -> Unit
        }
    }
}

// In the definition's place, for a genre that has none (#663), in the panel's quiet note style.
@Composable
private fun NoDefinition() =
    QuietNote(stringResource(R.string.genre_definition_none), Modifier.testTag(GENRE_NO_DEFINITION_TAG))

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

// *Why is this …?*: as tall as the genre chips, answering a tap across 48 dp, with the AI mark;
// disabled ([enabled] false) where it cannot be asked (#762).
@Composable
internal fun WhyButton(
    question: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick,
        Modifier.testTag(GENRE_WHY_TAG).heightIn(min = HEIGHT),
        enabled = enabled,
        shape = RoundedCornerShape(CORNER),
        contentPadding = PADDING,
    ) { AiLabel(question) }
}

// [text] in the panel's quiet note style.
@Composable
internal fun QuietNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * The test tags of a genre's definition (or the line saying it has none), its *Why is this …?* and
 * its reason.
 */
const val GENRE_DEFINITION_TAG: String = "genre-panel:definition"
const val GENRE_NO_DEFINITION_TAG: String = "genre-panel:no-definition"

/** The test tags of a genre's definition's line while it is looked up, and when the lookup failed (#786). */
const val GENRE_DEFINITION_LOOKING_TAG: String = "genre-panel:definition-looking"
const val GENRE_DEFINITION_FAILED_TAG: String = "genre-panel:definition-failed"
const val GENRE_WHY_TAG: String = "genre-panel:why"
const val GENRE_REASON_TAG: String = "genre-panel:reason"

private val HEIGHT = 32.dp
private val CORNER = 16.dp
private val PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
