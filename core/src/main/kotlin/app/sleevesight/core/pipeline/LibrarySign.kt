package app.sleevesight.core.pipeline

import app.sleevesight.core.genres.Singing
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.combineGenres
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
 * Instrumental ([TrackGenres.INSTRUMENTAL]: a source's tag, or a genre the AI's list added), and
 * nothing settles it already: no answer from LRCLIB yet, and the track not marked instrumental by
 * MusicBrainz, where Instrumental stands whatever LRCLIB says. Lyrics MusicBrainz lists leave no row
 * with Instrumental, so they call for none either.
 */
fun TrackGenres.wantsLibrary(): Boolean =
    singing.library == null &&
        !singing.instrumental &&
        rows.any { row -> row.labels.any { it.genre.identity == TrackGenres.INSTRUMENTAL } }

/**
 * These genres weighed with LRCLIB's [answer] (#997, [app.sleevesight.core.genres.Singing.library]):
 * lyrics drop Instrumental from every row, combined again without it, as MusicBrainz's lyrics links
 * do; its instrumental mark lifts what left Instrumental weak; no match leaves them as they were.
 * Itself with no answer, or one already weighed.
 */
fun TrackGenres.withLibrary(answer: LibraryLyrics?): TrackGenres {
    val weighed = answer?.takeIf { singing.library == null }?.let { singing.copy(library = it) }
    return when {
        weighed == null -> this
        !weighed.dropsInstrumental -> copy(singing = weighed)
        else -> withoutInstrumental(weighed)
    }
}

// These genres without Instrumental, combined again with [weighed], and the AI's list after them.
private fun TrackGenres.withoutInstrumental(weighed: Singing): TrackGenres {
    val combined =
        if (given.isEmpty()) {
            copy(rows = TrackGenres.withoutInstrumental(rows))
        } else {
            combineGenres(given, credited, weighed)
        }
    val found = combined.copy(singing = weighed, descriptions = descriptions)
    return aiModel?.let { found.withAi(aiListed, it, aiSources) } ?: found
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
