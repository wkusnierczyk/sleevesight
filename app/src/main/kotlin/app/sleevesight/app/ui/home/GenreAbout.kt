package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.speech.Reading
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.Lookup

// A genre's panel, above its table (#523): what the genre is, and why this track has it.

/**
 * What the genre [genre] is and why [track] has it, as [shown]'s notes give them (#523; `design/design.md`,
 * *Genre labels*), at the top of the genre's panel, in this order: the definition, from Wikipedia,
 * with its credit ("From Wikipedia", linking the article, and its licence); then *Why is this …?*,
 * a button carrying the **AI** mark ([AiLabel]), or, once asked for, the reason, under the question
 * as a heading, checked and cited as the insight is: its citations chips that go to their lines
 * ([PanelNotes.onCite]), its grounding note under it. A genre with no article says so in the
 * definition's place, once the lookup has answered (#663). Neither shows when [PanelNotes.notes]
 * has none: no definition while it is looked up, after a failed lookup, or with Wikipedia off; no
 * button for a genre only MusicBrainz gives, with
 * *Show insights* off or no AI service ready. When the evidence says nothing about how the track
 * sounds, the app says so above the reason, with where the label came from (#664). A reason still
 * being written reads its `[AI]` as the settings allow it ([PanelNotes.modelAllowed]). A written
 * reason has *Read aloud* (#325, [ReadAloudButton]) at the end of its grounding note's row, which
 * reads the question, then the reason ([PanelNotes.readAloud]).
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
    val reader = shown.readAloud
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
        when (val found = definition) {
            is Lookup.Found -> Definition(found.value)
            // Said only once the lookup answered: not while it runs, after it failed, or with it off.
            Lookup.NotFound -> NoDefinition()
            is Lookup.Failed, null -> Unit
        }
    }
    when (val shown = reason) {
        GenreReason.None -> Unit
        GenreReason.Offered -> WhyButton(question) { notes.explain(track, genre) }
        is GenreReason.Shown -> {
            val silent = if (shown.silent) silentLine(track, genre) else null
            val readAloud =
                readAloudControl(reader, Reading.genreReason(genre)) {
                    // Read aloud follows the reason as the notes give it, whatever screen shows.
                    (shown.state as? InsightState.Written)?.let { written ->
                        reader?.readGenreReason(track, genre, question, written, notes.reason(track, genre))
                    }
                }
            Reason(
                shown.state,
                question,
                InsightContext(track, true, true, modelAllowed, InsightOffers()),
                onCite,
                silent,
                readAloud,
            ) { notes.explain(track, genre) }
        }
    }
}

// In the definition's place, for a genre that has none (#663), in the panel's quiet note style.
@Composable
private fun NoDefinition() {
    Text(
        stringResource(R.string.genre_definition_none),
        modifier = Modifier.testTag(GENRE_NO_DEFINITION_TAG),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

// The reason asked for, in [state], under the [question], the app's own [silent] line above its text
// while it is written and once it is; offered again after a failure. Written, with [readAloud] at the
// end of its grounding note's row.
@Suppress("LongParameterList") // What the reason shows, the lines around it, and its two actions.
@Composable
private fun Reason(
    state: InsightState,
    question: String,
    context: InsightContext,
    onCite: (String) -> Unit,
    silent: String?,
    readAloud: ReadAloudControl?,
    again: () -> Unit,
) {
    Column(modifier = Modifier.testTag(GENRE_REASON_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            question,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (silent != null && (state is InsightState.Writing || state is InsightState.Written)) {
            Quiet(silent, Modifier.testTag(GENRE_SILENT_TAG))
        }
        when (state) {
            is InsightState.Writing -> {
                for (paragraph in paragraphsOf(state.partial)) UncheckedProse(paragraph, context, onCite)
                Quiet(stringResource(R.string.genre_why_writing))
            }

            is InsightState.Written -> {
                for (paragraph in state.grounded.paragraphs) {
                    CheckedProse(paragraph, state.grounded, context.track?.evidence, onCite)
                }
                if (readAloud == null) {
                    GroundingLine(state.grounded, state.cached, details = false)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { GroundingLine(state.grounded, state.cached, details = false) }
                        ReadAloudButton(readAloud, GENRE_READ_ALOUD_TAG)
                    }
                }
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
private fun Quiet(
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
 * The app's own line above a reason whose evidence says nothing about how the track sounds (#664):
 * that, and where the label came from, as the genre's rows give it: a clause for each source whose
 * tags gave it, in [LabelSource] order, naming what it tagged (the track, its artists by name, the
 * album). The app's statement, not the model's: no citations, no underline.
 */
@Composable
private fun silentLine(
    track: TrackState,
    genre: String,
): String {
    val thisTrack = stringResource(R.string.genre_why_this_track)
    val thisAlbum = stringResource(R.string.genre_why_this_album)
    val theArtist = stringResource(R.string.genre_why_the_artist)
    // What each source tagged with the genre, in row order: the track, the artists, the album.
    val tagged =
        track.genres.rows
            .flatMap { row ->
                val subject =
                    when (row.level) {
                        GenreLevel.TRACK -> thisTrack
                        GenreLevel.ALBUM -> thisAlbum
                        GenreLevel.ARTIST -> row.artist ?: theArtist
                    }
                row.labels
                    .filter { it.genre.identity == genre }
                    .flatMap { label -> label.credits.map { it.source to subject } }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, subjects) -> subjects.distinct() }
    val clauses =
        LabelSource.entries.mapNotNull { source ->
            tagged[source]?.let { subjects ->
                val who = listed(subjects)
                when (source) {
                    LabelSource.LASTFM -> stringResource(R.string.genre_why_from_lastfm, who)
                    LabelSource.MUSICBRAINZ -> stringResource(R.string.genre_why_from_musicbrainz, who)
                }
            }
        }
    val origin =
        clauses
            .reduceOrNull { all, clause -> stringResource(R.string.genre_why_clauses, all, clause) }
            ?.let { stringResource(R.string.genre_why_from, it) }
    return listOfNotNull(stringResource(R.string.genre_why_silent), origin).joinToString(" ")
}

// [items] as a sentence lists them, with the separators of the resources: "A", "A and B", "A, B and C".
@Composable
private fun listed(items: List<String>): String {
    var all = items.first()
    for (i in 1 until items.size) {
        val separator = if (i == items.lastIndex) R.string.genre_why_and else R.string.genre_why_list
        all = stringResource(separator, all, items[i])
    }
    return all
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

/** The test tags of a genre's definition (or the line saying it has none), its *Why is this …?* and its reason. */
const val GENRE_DEFINITION_TAG: String = "genre-panel:definition"
const val GENRE_NO_DEFINITION_TAG: String = "genre-panel:no-definition"
const val GENRE_WHY_TAG: String = "genre-panel:why"
const val GENRE_REASON_TAG: String = "genre-panel:reason"
const val GENRE_SILENT_TAG: String = "genre-panel:silent"

private val HEIGHT = 32.dp
private val CORNER = 16.dp
private val PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
