package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.ui.theme.PanelRule
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

// The genres *Search with AI* named under an empty *Genres* (#773): chips as the panel's, each unfolding
// its genre under them, which shows what the genre is and nothing else.

/**
 * *Search with AI* in *Genres*' panel or page (#773, [GenresContent]) for [track], under no genre and
 * no source still asked ([shown]: every genre source off, one not reached, none found), its genres
 * as chips ([AiGenres]) under which what [notes] say the genre is unfolds.
 */
@Composable
internal fun GenresSearch(
    track: TrackState?,
    shown: GenresShown,
    search: PanelSearch,
    notes: PanelNotes,
) {
    if (track != null && !track.genres.isEmpty || shown.looking.isNotEmpty()) return
    AiSearchBlock(search, SearchKind.GENRES) { answer, model ->
        if (answer is SearchAnswer.Genres && track != null) {
            AiGenres(track, answer.genres, notes.notes, search, model)
            // From the web (#801): the pages that give them, under the chips.
            for (source in answer.sources) FromSite(source)
        }
    }
}

/**
 * The [genres] *Search with AI* named for [track] (#773), as *Genres*' chips ([Chips]): a tap
 * unfolds the genre under them and a rule, as the chips of the sources' genres do ([GenresBox],
 * #786), with no heading or *Close*; another chip replaces it, the same chip folds it, and the focus
 * stays on the chip. Folded on each new track. What unfolds is the genre's definition from Wikipedia
 * ([notes], in each of its states, with *Search with AI* for one Wikipedia does not give, [search])
 * alone, the credit under the chips being the answer's: no *Why is this …?*, whose evidence does not
 * carry the genre, and no tags, as no source's tags give it.
 */
@Composable
internal fun AiGenres(
    track: TrackState,
    genres: List<GenreLabel>,
    notes: GenreNotes,
    search: PanelSearch = PanelSearch(),
    model: String? = null,
) {
    var chosen by rememberSaveable(track.normalized.lookupKey) { mutableStateOf<String?>(null) }
    // Marked AI, as the genres the AI's list adds are (#762, #801), credited to [model].
    val row = GenreRow(GenreLevel.TRACK, null, genres.map { TrackLabel(it, emptyList(), aiModel = model) })
    val labels = row.labels.map { PlacedLabel(row, it) }
    val open = genres.firstOrNull { it.identity == chosen }
    val handoff = rememberFocusHandoff()
    val chips = remember(track.normalized.lookupKey) { mutableMapOf<String, FocusRequester>() }
    val chipOf = { label: PlacedLabel -> chips.getOrPut(tagOf(GENRE_PREFIX, label)) { FocusRequester() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chips(labels, open?.identity, chipModifier = { Modifier.handoffTarget(handoff, chipOf(it)) }) { label ->
            val genre = label.label.genre.identity
            chosen = if (genre == open?.identity) null else genre
        }
        open?.let { genre ->
            PanelRule()
            Box(Modifier.testTag(GENRE_PANEL_TAG)) {
                // *Retry* under a failed lookup gives way to the loading line: the focus to the chip.
                GenreDefinitionNote(genre, notes, search) {
                    labels.firstOrNull { it.label.genre.identity == genre.identity }?.let { handoff.moveTo(chipOf(it)) }
                }
            }
        }
    }
}

// The AI's genre list for [track] (#762) as [notes] give it: null while none is known. A new
// subscription only when what the list's key is made of changes, not on every state of the track.
@Composable
internal fun aiGenres(
    track: TrackState,
    notes: GenreNotes,
): AiGenreList? {
    val list by remember(
        track.normalized,
        track.nowPlaying.duration,
        track.evidence?.fingerprint,
        track.gathered,
        notes,
    ) {
        notes.aiGenres(track)
    }.collectAsState(null)
    return list
}
