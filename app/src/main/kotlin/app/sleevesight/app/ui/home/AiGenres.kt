package app.sleevesight.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AiGenreList
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.PanelLibrary
import app.sleevesight.app.speech.Reading
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
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// The genres *Ask AI* named under an empty *Genres* (#773): chips as the panel's, each unfolding its
// genre under them, which shows what the genre is, then why the track is that genre (#952).

/**
 * What *Search with AI* in *Genres*' panel or page (#773) works from, for [track], under no genre and
 * no source still asked ([shown]: every genre source off, one not reached, none found); null where it
 * is not offered. Its [feed] drops Instrumental with lyrics found (#996), for what it shows and for
 * whether it names a genre ([aiGenresShown], #766), and [weighed] is the track with LRCLIB's answer.
 */
@Composable
internal fun genresAsk(
    track: TrackState?,
    shown: GenresShown,
    search: PanelSearch,
    notes: PanelNotes,
): GenresAsk? {
    // Under the genres an artist's description names too (#1000), and LRCLIB's Instrumental alone
    // (#1024): they are no source's tags.
    if (track != null && track.genres.hasTags || shown.looking.isNotEmpty()) return null
    // An Instrumental among them weighed with LRCLIB's answer (#997, #998), as the rows' is.
    val weighed = withSearchLibrary(track, search, notes.notes)
    // With lyrics found, never Instrumental (#996), as in the rows; with no other genre, nothing found.
    val drops =
        weighed.track
            ?.genres
            ?.singing
            ?.dropsInstrumental == true
    val feed = remember(search.feed, drops) { if (drops) LyricsFoundFeed(search.feed) else search.feed }
    return GenresAsk(weighed, feed)
}

/** [genresAsk]'s: the track weighed with LRCLIB's answer, and the feed its genres are read from. */
internal class GenresAsk(
    val weighed: LibraryWeighed,
    val feed: AiSearchFeed,
)

/**
 * *Search with AI* in *Genres*' panel or page (#773, [GenresContent]), from [ask] ([genresAsk]; none
 * where it is not offered), its genres as chips ([AiGenres]) under which what [notes] say the genre
 * is unfolds.
 */
@Composable
internal fun GenresSearch(
    ask: GenresAsk?,
    shown: GenresShown,
    search: PanelSearch,
    notes: PanelNotes,
) {
    ask ?: return
    val weighed = ask.weighed
    AiSearchBlock(PanelSearch(ask.feed, search.track, search.header), SearchKind.GENRES) { answer, model ->
        val asked = weighed.track
        if (answer is SearchAnswer.Genres && asked != null) {
            AiGenres(asked, answer, notes, search, model)
            // From the web (#801): the pages that give them, under the chips, on one line (#1204).
            FromSites(answer.sources)
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

    // Read only, as [search] gives it (#766: whether the panel shows a genre).
    override fun answered(
        track: TrackState,
        kind: SearchKind,
    ): Flow<AiSearch> = feed.answered(track, kind).map(::withoutInstrumental)

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
 * The genres *Ask AI* named for [track] ([answer], #773), as *Genres*' chips ([Chips]): a tap
 * unfolds the genre under them and a rule, as the chips of the sources' genres do ([GenresBox],
 * #786), with no heading or *Close*; another chip replaces it, the same chip folds it, and the focus
 * stays on the chip. Folded on each new track. What unfolds is the genre's definition from Wikipedia
 * ([shown]'s notes, in each of its states, with *Ask AI* for one Wikipedia does not give, [search]),
 * then the reason the answer gave with the genre ([AnswerReason], #952); the credit under the chips
 * is the answer's, and no tags, as no source's tags give it.
 */
@Composable
internal fun AiGenres(
    track: TrackState,
    answer: SearchAnswer.Genres,
    shown: PanelNotes,
    search: PanelSearch = PanelSearch(),
    model: String? = null,
) {
    val genres = answer.genres
    val notes = shown.notes
    var chosen by rememberSaveable(track.normalized.lookupKey) { mutableStateOf<String?>(null) }
    // Marked AI, as the genres the AI's list adds are (#762, #801), credited to [model].
    val row = GenreRow(GenreLevel.TRACK, null, genres.map { TrackLabel(it, emptyList(), aiModel = model) })
    // Instrumental on a sung track (#996), or one only LRCLIB's mark lifts (#1055): weak, as the rows'
    // AI genres are, with its line.
    val labels = row.labels.map { PlacedLabel(row, it, weak = track.genres.aiWeakEvidence(it.genre.identity) != null) }
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
                    WeakEvidenceNote(track.genres.aiWeakEvidence(genre.identity), genre)
                    answer.reasons[genre.identity]?.let { reason -> AnswerReason(track, genre, reason, shown) }
                }
            }
        }
    }
}

/**
 * Why [track] is [genre], as *Ask AI* said with the genre ([reason], #952), as the AI list's genres
 * show theirs ([ReasonShown], #762): under *Why is this …?* as a heading, its sentences cited as *Explain
 * the song*'s are ([AiExplanation]: from the web, each its page's chip, named and opening *Sources*'
 * line for it ([shown]'s lines, which list the answer's pages, `answeredGenrePages`, and
 * [PanelNotes.onCite]), so its id is never another page's; from the model's own
 * knowledge, the **AI** chip at the end of each sentence, and no line saying so); then *Read aloud*
 * alone at the right of the row under it, which reads the question, then the reason. No credit of its
 * own: the answer's, under the chips, is its.
 */
@Composable
private fun AnswerReason(
    track: TrackState,
    genre: GenreLabel,
    reason: SearchAnswer.Explanation,
    shown: PanelNotes,
) {
    val question = stringResource(R.string.genre_why, inSentence(genre.label))
    val reader = shown.readAloud
    val readAloud =
        readAloudControl(reader, Reading.genreReason(genre.identity)) {
            reader?.readAnswerReason(track, genre.identity, question, reason.grounded)
        }
    Column(Modifier.testTag(AI_GENRE_REASON_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        AiExplanation(reason, shown.lines, shown.onCite, tag = AI_GENRE_REASON_TEXT_TAG)
        readAloud?.let { CreditRow(null, readAloud = { ReadAloudButton(it, GENRE_READ_ALOUD_TAG) }) }
    }
}

/**
 * The pages the reasons of *Ask AI*'s genres in [search] cite (#952), each once, in the answer's
 * order, as web lines of *Sources* list them: each named by its site ([siteOf]), its title not kept;
 * none for no answer, or one from the model's knowledge.
 */
internal fun answeredGenrePages(search: AiSearch): List<WebPage> {
    val answer = (search as? AiSearch.Answered)?.answer as? SearchAnswer.Genres ?: return emptyList()
    return answer.sources.map { url -> WebPage(url, null, siteOf(url)) }
}

/** The test tags of the reason *Ask AI* gave with a genre (#952), and of its text. */
const val AI_GENRE_REASON_TAG: String = "ai-genre:reason"
const val AI_GENRE_REASON_TEXT_TAG: String = "ai-genre:reason-text"

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
