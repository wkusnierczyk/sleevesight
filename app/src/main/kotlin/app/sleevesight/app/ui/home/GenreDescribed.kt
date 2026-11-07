package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.DescribedIn
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.WeakEvidence
import app.sleevesight.core.genres.describedGenres
import app.sleevesight.core.genres.describedIn
import app.sleevesight.core.genres.withDescribed
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.NameMatcher

// The genres an artist's description names (#1000; `design/design.md`, *Genre labels*): read from the
// lines *Sources* lists, beside the tags, and the line under such a genre's definition.

/**
 * [track] with the genres its artists' descriptions name (#1000, `TrackGenres.withDescribed`): a
 * plain match of [notes]' genre list ([app.sleevesight.app.pipeline.GenreNotes.vocabulary]) against
 * the artists' own lines among those *Sources* lists ([PanelNotes.lines], else the track's evidence),
 * in every mode, as soon as the lines are in, no AI; [track] itself with no list or no lines.
 */
@Composable
internal fun withDescribed(
    track: TrackState?,
    notes: PanelNotes,
): TrackState? {
    val vocabulary = notes.notes.vocabulary
    val lines = notes.lines ?: track?.evidence
    if (track == null || vocabulary == null || lines == null) return track
    val artists = track.normalized.artists
    val found = remember(vocabulary, lines, artists) { describedGenres(vocabulary, lines.lines, artists, NAMES) }
    return if (found.isEmpty()) track else track.copy(genres = track.genres.withDescribed(found))
}

/**
 * The track the reason for [genre] is asked on, *Why is this …?* (#1000): [track] itself, keyed by
 * its own evidence as any genre's reason, when that evidence holds the line the genre was read from
 * (a Last.fm biography, a Wikipedia article), or for a genre no description gives; for one read from
 * a line only *Sources* lists (#912's artist web lines), [track] with [lines], *Sources*' lines, as
 * its evidence, so the reason reads it, cites it by the ids *Sources* shows, and is keyed by their
 * fingerprint. With no lines, [track].
 */
internal fun reasonedOn(
    track: TrackState,
    genre: String,
    lines: EvidenceSet?,
): TrackState {
    val where = track.genres.describedIn(genre)
    if (lines == null || where == null) return track
    val own = track.evidence?.get(where.line)
    return if (own != null && own.text == lines[where.line]?.text) track else track.copy(evidence = lines)
}

/**
 * Under the definition of a genre the artist's description names ([where], #1000), before *Why is
 * this …?*, in the panel's quiet note style: "Named in the artist’s description: “{quote}”", the
 * citation of its line after it, a link to the line in *Sources* ([onCite]). Nothing for a genre
 * no description gives, or for Instrumental the artist's hint leaves weak ([why]), whose own line
 * says it ([WeakEvidenceNote]).
 */
@Composable
internal fun DescribedNote(
    where: DescribedIn?,
    why: WeakEvidence?,
    onCite: (String) -> Unit,
) {
    if (where == null || why is WeakEvidence.ArtistHint) return
    CitedNote(stringResource(R.string.genre_described_named, where.quote), where.line, onCite, GENRE_DESCRIBED_TAG)
}

/**
 * [text] in the panel's quiet note style, then the citation of the line [line], a link that opens it
 * in *Sources* ([onCite]), as a citation does; [tag] names it to tests.
 */
@Composable
internal fun CitedNote(
    text: String,
    line: String,
    onCite: (String) -> Unit,
    tag: String,
) {
    // Read as the insights' citations are, "Source S8", the link offered as an action too.
    val cite = stringResource(R.string.home_cite, line)
    LinkedText(
        listOf(TextPart.Words("$text "), citation(line, onCite)),
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.testTag(tag).semantics {
                contentDescription = "$text ($cite)"
                customActions =
                    listOf(
                        CustomAccessibilityAction(cite) {
                            onCite(line)
                            true
                        },
                    )
            },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The citation of the line [line], by the id *Sources* shows it under, a link to it there ([onCite]). */
internal fun citation(
    line: String,
    onCite: (String) -> Unit,
): TextPart.Action = TextPart.Action(line, GENRE_CITE_PREFIX + line) { onCite(line) }

// A described row's sources (#1000): whose description it is, then the citation of each of its lines,
// once, in the order its genres cite them, " · " between, each a link to the line in *Sources* ([onCite]).
@Composable
internal fun describedParts(
    row: GenreRow,
    onCite: (String) -> Unit,
): List<TextPart> {
    val whose = TextPart.Words(stringResource(R.string.home_genre_description_of, row.artist.orEmpty()))
    val lines = row.labels.mapNotNull { it.describedIn?.line }.distinct()
    return lines.fold(listOf<TextPart>(whose)) { parts, line -> parts + TextPart.Words(" · ") + citation(line, onCite) }
}

// How a line's artist is matched to the credit, as the item insights match them.
private val NAMES = NameMatcher(JvmAccentFolder)

/** The test tag of the line under a genre the artist's description names (#1000). */
const val GENRE_DESCRIBED_TAG: String = "genre-panel:described"

/** The test tag of a citation of a line in a genre's notes or its sources, before the line's id (#1000). */
const val GENRE_CITE_PREFIX: String = "genre-cite:"
