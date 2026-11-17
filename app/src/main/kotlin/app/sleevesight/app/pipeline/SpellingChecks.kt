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
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.pastKeepHistory
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** How the sources answered the spelling the check found (#1293), as *Dates* and *Lyrics* say it. */
sealed interface SpellingOutcome {
    /** MusicBrainz is being asked with it: the panel still says the check runs, with *Stop*. */
    data object Asking : SpellingOutcome

    /** MusicBrainz confirmed it: the track is looked up again with it, the panel saying the check runs. */
    data object Found : SpellingOutcome

    /** MusicBrainz found nothing that agrees with the player's: "MusicBrainz didn’t find it as “{title}” either.". */
    data object NotFound : SpellingOutcome

    /** *Stop* while MusicBrainz was asked, or the track looked up again: the spelling is not used. */
    data object Stopped : SpellingOutcome
}

/** Where a confirmed spelling goes, on the host (#1293): [found], [dropped] by *Stop*, all [forgotten]. */
class SpellingTargets(
    val found: (NowPlaying, FoundAs) -> Unit,
    val dropped: (NowPlaying) -> Unit = {},
    val forgotten: () -> Unit = {},
)

/**
 * The spelling check (#1293; `design/design.md`, *Spelling check*), after the AI service answered:
 * for the playing track MusicBrainz found nothing for ([follow]), its answer, a spelling found on the
 * web (*Ask AI*'s [SearchKind.SPELLING], [searches], kept or written now), is asked of MusicBrainz
 * once ([SpellingCheck], with the live pipeline's resolver and normalizer, [parts]): confirmed, the
 * track is looked up again with it ([targets]' `found`, the host's); else the panel says MusicBrainz
 * did not find it under it either ([outcome]), and *Ask AI* is offered. *Stop* ([stop]) cancels the
 * question to MusicBrainz, or, confirmed, the spelling's use ([targets]' `dropped`).
 *
 * What MusicBrainz answered is held in memory, by the track as the player names it, its length and
 * the spelling, no longer than *Keep history for* ([settings], by [clock]) after the answer was
 * written, so a spelling is asked once per process; a track played again with the answer kept asks
 * MusicBrainz again, free. *Clear history* ([forget]) cancels the questions under way and forgets
 * every answer and spelling found ([targets]' `forgotten`), under the lock a confirmation is applied
 * under, so none applied once the clear has started survives it.
 */
@Suppress("LongParameterList") // What it asks with, where it runs, its time, and where its answers go.
class SpellingChecks(
    private val searches: AppAiSearches,
    private val parts: StateFlow<PipelineParts?>,
    private val names: NameMatcher,
    private val scope: CoroutineScope,
    private val targets: SpellingTargets,
    private val settings: StateFlow<Settings?> = MutableStateFlow(null),
    private val clock: Clock = Clock.System,
) {
    // What MusicBrainz answered for each spelling asked, by [Asked], with when the answer was written.
    private val answered = MutableStateFlow<Map<Asked, Answer>>(emptyMap())

    // The questions to MusicBrainz under way, by [Asked]; under [lock].
    private val asking = HashMap<Asked, Job>()

    // Held while a spelling is taken to be asked, while what MusicBrainz answered is applied, and while
    // a clear or a stop runs: so each comes wholly before or after the other.
    private val lock = Any()

    // Each clear counted, under [lock]: an answer of a question started before one is dropped.
    private var clears = 0L

    /** What MusicBrainz answered, as it changes: what the auto searches wait for. */
    val outcomes: StateFlow<Map<Asked, Answer>> = answered

    /**
     * What MusicBrainz answered for [spelling], found for [track]; null before it is asked, or once past
     * *Keep history for*.
     */
    fun outcome(
        track: TrackState,
        spelling: SearchAnswer.Spelling,
    ): Flow<SpellingOutcome?> =
        answered.map { it[Asked.of(track, spelling)]?.takeUnless(::expired)?.outcome }.distinctUntilChanged()

    /**
     * Whether [track]'s spelling check has run, so the other searches of *Ask AI automatically* may
     * start (the check first, in their place, #1293): it cannot be asked (no AI service ready, *AI
     * web search* off), or was stopped, failed or found no other spelling; or MusicBrainz did not
     * find the one it found, or it was stopped while MusicBrainz was asked.
     */
    fun concluded(track: TrackState): Boolean {
        val ran = searches.ran(track, SearchKind.SPELLING)
        val spelling = ((ran as? SearchState.Answered)?.answer as? SearchAnswer.Spelling)
        return when {
            ran == null -> false
            ran is SearchState.Searching -> false
            spelling != null ->
                answered.value[Asked.of(track, spelling)]?.outcome.let {
                    it == SpellingOutcome.NotFound || it == SpellingOutcome.Stopped
                }
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
                ask(track, answer, search.model, search.writtenAt)
            }
    }

    /**
     * *Stop* beside the spelling check while MusicBrainz is asked with [spelling], found for [track],
     * or while the track is looked up again with it: the question cancelled, the spelling not used
     * (the host forgets it, and looks the track up again without it if its run took it).
     */
    fun stop(
        track: TrackState,
        spelling: SearchAnswer.Spelling,
    ) = synchronized(lock) {
        val asked = Asked.of(track, spelling)
        asking.remove(asked)?.cancel()
        val now = answered.value[asked] ?: return@synchronized
        if (now.outcome == SpellingOutcome.Asking || now.outcome == SpellingOutcome.Found) {
            answered.update { it + (asked to now.copy(outcome = SpellingOutcome.Stopped)) }
            targets.dropped(track.nowPlaying)
        }
    }

    /**
     * Forgets what MusicBrainz answered and every spelling found (*Clear history*), and cancels the
     * questions under way: none of them applies anything after this.
     */
    fun forget() =
        synchronized(lock) {
            clears++
            asking.values.forEach { it.cancel() }
            asking.clear()
            answered.value = emptyMap()
            targets.forgotten()
        }

    /**
     * [spelling], found for [track] by [model] at [writtenAt], asked of MusicBrainz unless it was (an
     * answer past *Keep history for* is dropped first). Internal for the tests.
     */
    internal fun ask(
        track: TrackState,
        spelling: SearchAnswer.Spelling,
        model: String,
        writtenAt: Instant?,
    ) {
        val asked = Asked.of(track, spelling)
        val made = parts.value ?: return
        synchronized(lock) {
            answered.update { all -> all.filterValues { !expired(it) } }
            if (asked in answered.value) return
            answered.update { it + (asked to Answer(SpellingOutcome.Asking, writtenAt)) }
            val since = clears
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    val confirmed = confirmed(made, track, spelling, model, writtenAt)
                    applied(asked, since, track, confirmed)
                }
            asking[asked] = job
            job.invokeOnCompletion { synchronized(lock) { asking.remove(asked, job) } }
            job.start()
        }
    }

    // What MusicBrainz answers for [spelling]; a bug, not the network (which the resolver maps to a
    // resolution), as not found.
    private suspend fun confirmed(
        made: PipelineParts,
        track: TrackState,
        spelling: SearchAnswer.Spelling,
        model: String,
        writtenAt: Instant?,
    ): FoundAs? =
        try {
            SpellingCheck(made.resolver, made.normalizer, names)
                .confirm(track.nowPlaying, track.played, spelling, model, writtenAt)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    // What MusicBrainz answered for [asked] ([confirmed]), applied unless a clear came since [since] or
    // it was stopped meanwhile: under the lock, so a clear or a stop comes wholly before or after it.
    private fun applied(
        asked: Asked,
        since: Long,
        track: TrackState,
        confirmed: FoundAs?,
    ) = synchronized(lock) {
        val now = answered.value[asked]
        if (clears != since || now?.outcome != SpellingOutcome.Asking) return@synchronized
        val outcome = if (confirmed != null) SpellingOutcome.Found else SpellingOutcome.NotFound
        answered.update { it + (asked to now.copy(outcome = outcome)) }
        confirmed?.let { targets.found(track.nowPlaying, it) }
    }

    // Whether [answer]'s check was written past *Keep history for*.
    private fun expired(answer: Answer): Boolean =
        pastKeepHistory(answer.writtenAt, settings.value?.historyLifetime, clock.now())

    /** What MusicBrainz answered for a spelling ([outcome]), and when the check's answer was written. */
    data class Answer(
        val outcome: SpellingOutcome,
        val writtenAt: Instant?,
    )

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
