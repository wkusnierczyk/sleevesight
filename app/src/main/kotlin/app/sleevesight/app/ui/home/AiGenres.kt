package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.ui.theme.Panel
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind

// The genres *Search with AI* named under an empty *Genres* (#773): chips as the panel's, each opening
// its genre in a box of its own, which holds what the genre is and nothing else.

/**
 * *Search with AI* in *Genres*' panel or page (#773, [GenresContent]) for [track], under no genre and
 * no source still asked ([shown]: every genre source off, one not reached, none found), its genres
 * as chips ([AiGenres]) whose boxes show what [notes] say the genre is.
 */
@Composable
internal fun GenresSearch(
    track: TrackState?,
    shown: GenresShown,
    search: PanelSearch,
    notes: PanelNotes,
) {
    if (track != null && !track.genres.isEmpty || shown.looking.isNotEmpty()) return
    AiSearchBlock(search, SearchKind.GENRES) { answer, _ ->
        if (answer is SearchAnswer.Genres && track != null) AiGenres(track, answer.genres, notes.notes)
    }
}

/**
 * The [genres] *Search with AI* named for [track] (#773), as *Genres*' chips ([Chips]):
 * a tap opens the genre in a box under them, as the chips of the sources' genres do ([GenresBox]);
 * another chip switches it, the same chip or its *Close* folds it, and the focus moves to *Close*
 * and back to the chip. Closed on each new track. The box ([AiGenrePanel]) holds the genre's
 * definition from Wikipedia ([notes], with Wikipedia's own states) alone, the credit under the chips
 * being the answer's: no second one, no *Why is this …?*, whose evidence does not carry the genre, and no table, as no
 * source's tags give it.
 */
@Composable
internal fun AiGenres(
    track: TrackState,
    genres: List<GenreLabel>,
    notes: GenreNotes,
) {
    var chosen by rememberSaveable(track.normalized.lookupKey) { mutableStateOf<String?>(null) }
    val row = GenreRow(GenreLevel.TRACK, null, genres.map { TrackLabel(it, emptyList()) })
    val labels = row.labels.map { PlacedLabel(row, it) }
    val open = genres.firstOrNull { it.identity == chosen }
    val handoff = rememberFocusHandoff()
    val close = remember { FocusRequester() }
    val chips = remember(track.normalized.lookupKey) { mutableMapOf<String, FocusRequester>() }
    val chipOf = { label: PlacedLabel -> chips.getOrPut(tagOf(GENRE_PREFIX, label)) { FocusRequester() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chips(labels, open?.identity, chipModifier = { Modifier.handoffTarget(handoff, chipOf(it)) }) { label ->
            val genre = label.label.genre.identity
            chosen = if (genre == open?.identity) null else genre
            if (genre != open?.identity) handoff.moveTo(close)
        }
        open?.let { genre ->
            Box {
                AiGenrePanel(genre, notes, Modifier.handoffTarget(handoff, close)) {
                    chosen = null
                    labels.firstOrNull { it.label.genre.identity == genre.identity }?.let { handoff.moveTo(chipOf(it)) }
                }
            }
        }
    }
}

/**
 * The box of [genre], named by *Search with AI* (#773): its name as a heading, with *Close*
 * ([onClose], under [closeModifier]), on the surface colour, as a genre's panel inside *Genres*' is
 * ([GenrePanel]); what the genre is, from [notes] ([GenreDefinitionNote]), and nothing else.
 */
@Composable
private fun AiGenrePanel(
    genre: GenreLabel,
    notes: GenreNotes,
    closeModifier: Modifier,
    onClose: () -> Unit,
) {
    Panel(
        AnnotatedString(genre.label),
        GENRE_PANEL_TAG,
        onClose,
        closeModifier = closeModifier,
        fill = MaterialTheme.colorScheme.surface,
    ) {
        GenreDefinitionNote(genre.identity, notes)
    }
}
