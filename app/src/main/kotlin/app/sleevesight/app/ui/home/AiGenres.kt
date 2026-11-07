package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.PanelLibrary
import app.sleevesight.app.ui.theme.PanelRule
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.LaterLabels
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.withLibrary
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
    // Under the genres an artist's description names too (#1000): they are no source's tags.
    if (track != null && track.genres.rows.isNotEmpty() || shown.looking.isNotEmpty()) return
    // An Instrumental among them weighed with LRCLIB's answer (#997, #998), as the rows' is.
    val weighed = withSearchLibrary(track, search, notes.notes)
    // With lyrics found, never Instrumental (#996), as in the rows; with no other genre, nothing found.
    val drops =
        weighed.track
            ?.genres
            ?.singing
            ?.dropsInstrumental == true
    val feed = remember(search.feed, drops) { if (drops) LyricsFoundFeed(search.feed) else search.feed }
    AiSearchBlock(PanelSearch(feed, search.track, search.header), SearchKind.GENRES) { answer, model ->
        val asked = weighed.track
        if (answer is SearchAnswer.Genres && asked != null) {
            AiGenres(asked, answer.genres, notes.notes, search, model)
            // From the web (#801): the pages that give them, under the chips.
            for (source in answer.sources) FromSite(source)
        }
    }
    // One LRCLIB line at most: the panel's own, unless *Genres* says it already.
    weighed.retry?.takeIf { !shown.libraryUnreachable }?.let { again -> LibraryRetry(true, again, { null }, {}) }
}

/**
 * [track], with the genres *Search with AI* found under an empty *Genres* (#773, read only from
 * [search]'s feed: subscribing asks nothing) holding Instrumental, weighed with LRCLIB's answer for it
 * (#997; #998: "a genre your AI service added"), which [notes] ask once the run's labels are in, as
 * for the AI's genre list ([GenreNotes.libraryLyrics]): lyrics drop it, its mark lifts what left it
 * weak; not reached, with the panel's *Retry*. Else [track].
 */
@Composable
private fun withSearchLibrary(
    track: TrackState?,
    search: PanelSearch,
    notes: GenreNotes,
): LibraryWeighed {
    val feed = search.feed
    val state =
        key(feed, track?.normalized, track?.nowPlaying?.duration, track?.nowPlaying?.album) {
            remember { track?.let { feed.answered(it, SearchKind.GENRES) } ?: flowOf(AiSearch.None) }
                .collectAsState(AiSearch.None)
                .value
        }
    val genres = ((state as? AiSearch.Answered)?.answer as? SearchAnswer.Genres)?.genres.orEmpty()
    val singing = track?.genres?.singing
    val asks =
        track != null &&
            singing?.library == null &&
            singing?.instrumental != true &&
            track.gathered &&
            track.genresAssembled &&
            LaterLabels.ASKING !in track.laterLabels.values &&
            genres.any { it.identity == TrackGenres.INSTRUMENTAL }
    val answer =
        key(notes, track?.normalized, track?.nowPlaying?.duration, track?.nowPlaying?.album, asks) {
            remember { track?.takeIf { asks }?.let(notes::libraryLyrics) ?: flowOf(null) }
                .collectAsState(null)
                .value
        }
    return when {
        track == null || answer == null -> LibraryWeighed(track)
        answer is PanelLibrary.Answered -> LibraryWeighed(track.copy(genres = track.genres.withLibrary(answer.lyrics)))
        else -> LibraryWeighed(track.copy(libraryUnreachable = true)) { notes.retryLibrary(track) }
    }
}

/**
 * [feed], for a track with lyrics found (#996): *Search with AI*'s genres without Instrumental, as
 * the rows have none ([TrackGenres.aiShown]); with no other genre, nothing found, as an answer with
 * nothing usable is shown.
 */
private class LyricsFoundFeed(
    private val feed: AiSearchFeed,
) : AiSearchFeed by feed {
    override fun search(
        track: TrackState,
        kind: SearchKind,
        genre: String?,
    ): Flow<AiSearch> = feed.search(track, kind, genre).map(::withoutInstrumental)

    private fun withoutInstrumental(state: AiSearch): AiSearch {
        val answer = (state as? AiSearch.Answered)?.answer as? SearchAnswer.Genres ?: return state
        val left = answer.genres.filter { it.identity != TrackGenres.INSTRUMENTAL }
        return if (left.isEmpty()) {
            AiSearch.NothingFound(state.model, state.way, state.keptAt)
        } else {
            state.copy(answer = answer.copy(genres = left))
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
    // Instrumental on a sung track (#996): weak, as the rows' AI genres are, with its line.
    val labels = row.labels.map { PlacedLabel(row, it, weak = track.genres.aiSignOfVocals(it.genre.identity) != null) }
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
                // Spaced as a genre's body is ([GenreBody]).
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GenreDefinitionNote(genre, notes, search) {
                        labels.firstOrNull { it.label.genre.identity == genre.identity }?.let {
                            handoff.moveTo(
                                chipOf(it),
                            )
                        }
                    }
                    WeakEvidenceNote(track.genres.aiSignOfVocals(genre.identity), genre)
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
