package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.genres.withoutInstrumental
import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.lyrics.LyricsLibrary
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether these genres call for LRCLIB's answer (#997; `design/design.md`, *Genre labels*): a row has
 * Instrumental ([TrackGenres.INSTRUMENTAL]: a source's tag, or a genre the AI's list added), or an
 * artist's description names it (#998: the artist's hint, a row of [TrackGenres.described], which only
 * the panel reads), or the song's work credits composers alone (#998's hint, `Singing.composersHint`,
 * with no row of it: LRCLIB answers before the paid check of the track's vocals, and may settle it for
 * free); and nothing
 * settles it already: no answer from LRCLIB yet, and the track not marked instrumental by MusicBrainz,
 * where Instrumental stands whatever LRCLIB says. Lyrics MusicBrainz lists leave no row with
 * Instrumental, and settle the hint, so they call for none either.
 */
fun TrackGenres.wantsLibrary(): Boolean {
    val hinted = singing.composersHint && !singing.lyrics
    return singing.library == null && !singing.instrumental && (showsInstrumental() || hinted)
}

/**
 * Whether a row of these genres shows Instrumental: a source's tag, the AI's, or one an artist's
 * description names (#1000). LRCLIB's failure is said only then (#998's review): asked for the
 * composers' hint alone, nothing in *Genres* would tell what it was asked about.
 */
fun TrackGenres.showsInstrumental(): Boolean =
    (rows + described).any { row -> row.labels.any { it.genre.identity == TrackGenres.INSTRUMENTAL } }

/**
 * These genres weighed with LRCLIB's [answer] (#997, [app.sleevesight.core.genres.Singing.library]):
 * lyrics drop Instrumental from every row, combined again without it, as MusicBrainz's lyrics links
 * do; its instrumental mark lifts what left Instrumental weak; no match leaves them as they were.
 * Itself with no answer, or one already weighed. What the panel's own answer does (#997): it asks
 * only about an Instrumental already on a row, so the mark adds none there ([withLibraryMark] is the
 * run's, #1024).
 */
fun TrackGenres.withLibrary(answer: LibraryLyrics?): TrackGenres {
    val weighed = answer?.takeIf { singing.library == null }?.let { singing.copy(library = it) }
    return when {
        weighed == null -> this
        !weighed.dropsInstrumental -> copy(singing = weighed)
        else -> withoutInstrumental(weighed)
    }
}

/**
 * These genres with the Instrumental LRCLIB's instrumental mark adds (#1024; `design/design.md`,
 * *Genre labels*): where the song's work credits composers alone and nothing says it is sung
 * ([app.sleevesight.core.genres.Singing.composersHint]), which asked LRCLIB with no row showing
 * Instrumental, and LRCLIB marks the track instrumental, [instrumental], the vocabulary's label of it,
 * joins the track's row after its labels, credited to LRCLIB ([TrackLabel.markedBy]: named under *Tag
 * sources*, not linked, as LRCLIB has no page for a track), a source's genre, not weak and not marked
 * AI, while the row has room; a track row made for it where there was none. The paid check of the
 * track's vocals is settled by the mark already. Not added with Instrumental on any row (the mark
 * lifts it there, and it is not added twice), with lyrics MusicBrainz lists (a free sign that drops
 * it wins), or the recording marked instrumental by MusicBrainz, which LRCLIB is not asked about:
 * itself then, and with no [instrumental]. Applied twice, the same. The run applies it
 * ([TrackLabels]), whose genres hold only the tags' rows: an Instrumental an artist's description
 * names (#1000), which only the panel adds, does not stop it, and then sits on the *Described* row
 * beside the mark's on the *Track* row, its chip the mark's (another level's genre stays on a
 * described row; a tag's chip wins).
 */
fun TrackGenres.withLibraryMark(instrumental: GenreLabel?): TrackGenres {
    val track = rows.firstOrNull { it.level == GenreLevel.TRACK }
    val adds =
        singing.library == LibraryLyrics.INSTRUMENTAL &&
            singing.composersHint &&
            !singing.instrumental &&
            !singing.lyrics &&
            everyLabel.none { it.genre.identity == TrackGenres.INSTRUMENTAL } &&
            (track?.labels?.size ?: 0) < GenreVocabulary.MAX_LABELS
    if (instrumental == null || !adds) return this
    val label =
        TrackLabel(
            instrumental.copy(url = null, level = GenreLevel.TRACK, artist = null, votes = null),
            emptyList(),
            votes = null,
            markedBy = SourceId.LRCLIB,
        )
    val row = GenreRow(GenreLevel.TRACK, null, track?.labels.orEmpty() + label)
    return copy(rows = if (track == null) listOf(row) + rows else rows.map { if (it === track) row else it })
}

/** What came of asking LRCLIB for a track (#997, [LibraryLookup.ask]). */
sealed interface LibraryAnswer {
    /** Its [lyrics], from the retrieval cache when [cached]. */
    data class Known(
        val lyrics: LibraryLyrics,
        val cached: Boolean,
    ) : LibraryAnswer

    /** It could not be reached, or did not answer usably, for [reason] (its kind only): not kept. */
    data class Failed(
        val reason: String,
    ) : LibraryAnswer
}

/**
 * LRCLIB asked about a track (#997), its answer kept with the track's findings in [cache], under the
 * whole query ([LibraryQuery]: artist, title, album, length in seconds), so a replay asks nothing: a
 * match's answer, lyrics or instrumental, and no match too, as no answer ([LibraryLyrics.NONE]). A
 * failure is not kept. Asked only for a track [LibraryQuery.of] gives a query for; a cache that fails
 * is told to [emit] and missed.
 */
class LibraryLookup(
    private val library: LyricsLibrary,
    private val cache: PipelineCache,
    private val emit: (PipelineEvent) -> Unit = {},
) {
    /** The answer kept for [track] as [nowPlaying] plays it, if any; asks nothing. */
    suspend fun kept(
        track: NormalizedTrack,
        nowPlaying: NowPlaying,
    ): LibraryLyrics? =
        LibraryQuery.of(track, nowPlaying)?.let { query ->
            guarded(track, "read ${SourceId.LRCLIB.tag}") { libraryLyrics(query) }
        }

    /**
     * The answer for [track] as [nowPlaying] plays it: the one kept, unless [fresh] (a look again,
     * #617), else LRCLIB's, kept; null when it cannot be asked ([LibraryQuery.of]).
     */
    suspend fun ask(
        track: NormalizedTrack,
        nowPlaying: NowPlaying,
        fresh: Boolean = false,
    ): LibraryAnswer? {
        val query = LibraryQuery.of(track, nowPlaying) ?: return null
        val kept = if (fresh) null else guarded(track, "read ${SourceId.LRCLIB.tag}") { libraryLyrics(query) }
        return kept?.let { LibraryAnswer.Known(it, cached = true) } ?: asked(track, query)
    }

    // LRCLIB's answer for [query], kept; a 404 kept as no answer, a failure not kept.
    private suspend fun asked(
        track: NormalizedTrack,
        query: LibraryQuery,
    ): LibraryAnswer {
        val found = library.lookUp(query)
        val answer =
            when (found) {
                is Lookup.Found -> found.value
                Lookup.NotFound -> LibraryLyrics.NONE
                is Lookup.Failed -> null
            }
        if (answer != null) guarded(track, "write ${SourceId.LRCLIB.tag}") { keepLibraryLyrics(query, answer) }
        return answer?.let { LibraryAnswer.Known(it, cached = false) }
            ?: LibraryAnswer.Failed((found as? Lookup.Failed)?.reason.orEmpty())
    }

    private suspend fun <T> guarded(
        track: NormalizedTrack,
        operation: String,
        block: suspend PipelineCache.() -> T,
    ): T? =
        try {
            cache.block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            emit(PipelineEvent.CacheFailed(track.lookupKey, operation, TrackRun.nameOf(e)))
            null
        }
}

/**
 * One run's LRCLIB answer (#997): the one kept, read before the genres are assembled ([readKept], no
 * request), so a replay shows them weighed from the start; else asked once the genres are in, with
 * the labels after the evidence ([ask]), when they call for it ([wantsLibrary]). [lookup] is null with
 * LRCLIB off or not built. A *Retry* that does not name LRCLIB, of a track whose genres before it
 * called for LRCLIB already ([asks] false), asks it nothing, and keeps what the run before ended with
 * ([keptFailed]) while nothing is kept; one whose genres before it did not, so LRCLIB was never asked,
 * asks as any run does (an Instrumental the *Retry* brought); a look again ([fresh])
 * reads nothing kept and asks afresh. [input] is the run's: its track, as played.
 */
internal class LibrarySign(
    private val lookup: LibraryLookup?,
    input: RunInput,
    private val asks: Boolean,
    private val fresh: Boolean,
    keptFailed: Boolean,
    private val emit: (PipelineEvent) -> Unit,
) {
    private val track = input.track
    private val nowPlaying = input.nowPlaying

    /** LRCLIB's answer for the track, once known. */
    @Volatile
    var answer: LibraryLyrics? = null
        private set

    /**
     * Whether asking LRCLIB failed, so a *Retry* may ask it again; shown only while the genres call
     * for it ([unreachable]).
     */
    @Volatile
    var failed: Boolean = keptFailed && lookup != null
        private set

    /** Whether [genres] show LRCLIB as not reached: it [failed], and they still call for it. */
    fun unreachable(genres: TrackGenres): Boolean = failed && genres.wantsLibrary()

    /** The answer kept for the track, if any, before the genres are assembled; asks nothing. */
    suspend fun readKept() {
        if (fresh || lookup == null) return
        lookup.kept(track, nowPlaying)?.let { kept ->
            answer = kept
            failed = false
            emit(PipelineEvent.LibraryAnswered(track.lookupKey, kept, cached = true))
        }
    }

    /**
     * Asks LRCLIB when [genres] call for it and the run may ([asks]); true when its answer, or its
     * failure, changed what the run shows.
     */
    @Suppress("RedundantSuspendModifier") // Detekt does not see `LibraryLookup.ask` as suspending; it is.
    suspend fun ask(genres: TrackGenres): Boolean {
        val asked = lookup?.takeIf { asks && answer == null }
        return when {
            // Genres that no longer call for LRCLIB (a *Retry* brought MusicBrainz's mark or its lyrics
            // links) leave a failure kept from before with nothing to ask: no longer shown, nor retryable.
            !genres.wantsLibrary() -> failed.also { failed = false }
            asked == null -> false
            else -> landed(asked.ask(track, nowPlaying, fresh))
        }
    }

    // What [found] changes: an answer, or a failure, both shown; nothing when it could not be asked.
    private fun landed(found: LibraryAnswer?): Boolean =
        when (found) {
            null -> false
            is LibraryAnswer.Known -> {
                answer = found.lyrics
                failed = false
                emit(PipelineEvent.LibraryAnswered(track.lookupKey, found.lyrics, found.cached))
                true
            }
            is LibraryAnswer.Failed -> {
                failed = true
                emit(PipelineEvent.SourceFailed(track.lookupKey, SourceId.LRCLIB, found.reason))
                true
            }
        }
}
