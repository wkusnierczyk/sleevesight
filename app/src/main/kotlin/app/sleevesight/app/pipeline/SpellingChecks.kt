package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.NoDates
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.FoundAs
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SearchState
import app.sleevesight.core.pipeline.SpellingCheck
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** How the sources answered the spelling the check found (#1293), as *Dates* and *Lyrics* say it. */
sealed interface SpellingOutcome {
    /** MusicBrainz is being asked with it: the panel still says the check runs. */
    data object Asking : SpellingOutcome

    /** MusicBrainz confirmed it: the track is looked up again with it. */
    data object Found : SpellingOutcome

    /** MusicBrainz found nothing that agrees with the player's: "MusicBrainz didn’t find it as “{title}” either.". */
    data object NotFound : SpellingOutcome
}

/**
 * The spelling check (#1293; `design/design.md`, *Spelling check*), after the AI service answered:
 * for the playing track MusicBrainz found nothing for ([follow]), its answer, a spelling found on the
 * web (*Ask AI*'s [SearchKind.SPELLING], [searches], kept or written now), is asked of MusicBrainz
 * once ([SpellingCheck], with the live pipeline's resolver and normalizer, [parts]): confirmed, the
 * track is looked up again with it ([found], the host's); else the panel says MusicBrainz did not
 * find it under it either ([outcome]), and *Ask AI* is offered.
 *
 * What MusicBrainz answered is held in memory, by the track as the player names it, its length and
 * the spelling, so a spelling is asked once per process; a track played again with the answer kept
 * asks MusicBrainz again, free. *Clear history* forgets it ([forget]).
 */
class SpellingChecks(
    private val searches: AppAiSearches,
    private val parts: StateFlow<PipelineParts?>,
    private val names: NameMatcher,
    private val scope: CoroutineScope,
    private val found: (NowPlaying, FoundAs) -> Unit,
) {
    // What MusicBrainz answered for each spelling asked, by [Asked].
    private val answered = MutableStateFlow<Map<Asked, SpellingOutcome>>(emptyMap())

    // Held while a spelling is taken to be asked, so it is asked once.
    private val lock = Any()

    // Each clear counted, so an answer that lands after one is dropped.
    @Volatile
    private var clears = 0L

    /** What MusicBrainz answered, as it changes: what the auto searches wait for. */
    val outcomes: StateFlow<Map<Asked, SpellingOutcome>> = answered

    /** What MusicBrainz answered for [spelling], found for [track]; null before it is asked. */
    fun outcome(
        track: TrackState,
        spelling: SearchAnswer.Spelling,
    ): Flow<SpellingOutcome?> = answered.map { it[Asked.of(track, spelling)] }.distinctUntilChanged()

    /**
     * Whether [track]'s spelling check has run, so the other searches of *Ask AI automatically* may
     * start (the check first, in their place, #1293): it cannot be asked (no AI service ready, *AI
     * web search* off), or was stopped, failed or found no other spelling; or MusicBrainz did not
     * find the one it found.
     */
    fun concluded(track: TrackState): Boolean {
        val ran = searches.ran(track, SearchKind.SPELLING)
        val spelling = ((ran as? SearchState.Answered)?.answer as? SearchAnswer.Spelling)
        return when {
            ran == null -> false
            ran is SearchState.Searching -> false
            spelling != null -> answered.value[Asked.of(track, spelling)] == SpellingOutcome.NotFound
            else -> true
        }
    }

    /** Follows the playing track ([tracks]): each spelling its check found asked of MusicBrainz, once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun follow(tracks: Flow<TrackState?>) {
        tracks
            .map { it?.takeIf(::wanted) }
            .distinctUntilChanged { a, b -> a?.nowPlaying == b?.nowPlaying && a?.played == b?.played }
            .flatMapLatest { track ->
                if (track == null) {
                    flowOf(null)
                } else {
                    combine(searches.answered(track, SearchKind.SPELLING), flowOf(track)) { search, t -> t to search }
                }
            }.collect { pair ->
                val (track, search) = pair ?: return@collect
                val answer = (search as? AiSearch.Answered)?.answer as? SearchAnswer.Spelling ?: return@collect
                ask(track, answer, search.model)
            }
    }

    /** Forgets what MusicBrainz answered (*Clear history*). */
    fun forget() {
        clears++
        answered.value = emptyMap()
    }

    // [spelling], found for [track] by [model], asked of MusicBrainz unless it was.
    private fun ask(
        track: TrackState,
        spelling: SearchAnswer.Spelling,
        model: String,
    ) {
        val asked = Asked.of(track, spelling)
        val made = parts.value ?: return
        val first =
            synchronized(lock) {
                (asked !in answered.value).also { new ->
                    if (new) answered.update { it + (asked to SpellingOutcome.Asking) }
                }
            }
        if (!first) return
        val since = clears
        scope.launch {
            val confirmed =
                try {
                    SpellingCheck(made.resolver, made.normalizer, names)
                        .confirm(track.nowPlaying, track.played, spelling, model)
                } catch (e: CancellationException) {
                    answered.update { it - asked }
                    throw e
                } catch (
                    // A bug, not the network, which the resolver maps to a resolution: not found.
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    null
                }
            if (clears != since) return@launch
            val outcome = if (confirmed != null) SpellingOutcome.Found else SpellingOutcome.NotFound
            answered.update { it + (asked to outcome) }
            confirmed?.let { found(track.nowPlaying, it) }
        }
    }

    /**
     * A spelling asked of MusicBrainz: for the track as the player names it, cleaned for the sources
     * ([played]), with its length ([duration]), the [title] and [artists] found.
     */
    data class Asked(
        val played: NormalizedTrack,
        val duration: Duration?,
        val title: String,
        val artists: List<String>,
    ) {
        companion object {
            fun of(
                track: TrackState,
                spelling: SearchAnswer.Spelling,
            ) = Asked(track.played, track.nowPlaying.duration, spelling.title, spelling.artists)
        }
    }

    companion object {
        /**
         * Whether [track] wants the spelling check (#1293): MusicBrainz found nothing for it as the player
         * names it (none, or a near miss rejected), and it is not already looked up in another spelling.
         */
        fun wanted(track: TrackState): Boolean = track.foundAs == null && NoDates.of(track) == NoDates.NOT_FOUND
    }
}
