package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.instrumental
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.BusyWaits
import app.sleevesight.core.sources.FreshLookups
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * What one run is about: the track as the player reports it and normalized, and its settings;
 * [again], what an earlier run of the track gathered, when it asks again the sources that were
 * unreachable then (#192); [lookAgain], when it looks every source up afresh (#617); [items], the
 * lines the song's, the artist's and the album's evidence holds beyond the track's own (#1131,
 * [linesBeyond]), which its evidence takes after its own.
 */
@Suppress("LongParameterList") // What the run is about, and what it starts with.
internal class RunInput(
    val settings: Settings,
    val nowPlaying: NowPlaying,
    val track: NormalizedTrack,
    val again: Gathered? = null,
    val lookAgain: LookAgain? = null,
    val before: LabelsBefore = LabelsBefore(),
    val items: List<EvidenceDraft> = emptyList(),
)

/**
 * What a run that asks again starts with of the genres shown for its track (#772): the [genres],
 * until its own are assembled; and for a *Retry*, what the run before's label requests after the
 * evidence gave ([kept]), for the sources it does not ask again.
 */
internal class LabelsBefore(
    val genres: TrackGenres = TrackGenres(),
    val kept: TrackLabels.LaterKept? = null,
)

/** Where a run reports: diagnostics events, and changes to its state. */
internal interface RunSink {
    fun emit(event: PipelineEvent)

    fun update(change: (TrackState) -> TrackState)
}

/**
 * One track's run through the pipeline (see [Pipeline]), under one set of settings. [publish]
 * forwards each new state to the pipeline's, while this run is the current one; [onEvent] sends a
 * diagnostics event.
 */
internal class TrackRun(
    private val parts: PipelineParts,
    private val time: PipelineTime,
    private val input: RunInput,
    private val control: RunControl,
    private val onEvent: (PipelineEvent) -> Unit,
    private val publish: (TrackState) -> Unit,
) : RunSink {
    private val settings = input.settings
    private val nowPlaying = input.nowPlaying
    private val track = input.track
    private val key = track.lookupKey
    private val enabled = parts.adapters.filter { settings.isEnabled(it.id) }

    // Whether the run looks every source up afresh (#617).
    private val looksAgain = input.lookAgain != null

    // What an earlier run of the track found (#192), kept as it landed: every state of a source
    // still switched on but those it asks again ([Gathered.asked]): the `Unreachable` ones, or those a
    // *Retry* names (#724), a provisional one among them (#728), the other `Unreachable` ones kept so.
    // An adapter that threw (a bug, not the network) is not asked again; a source that was off then
    // is looked up as usual. When MusicBrainz, unreachable then, is asked again, so is a source that
    // rejected a match for want of what it resolves (#1069, `Rejection.withoutMusicBrainz`): its
    // verdict was reached against the failed resolution, and the one made now may confirm the match.
    private val keptBefore: Map<SourceId, SourceState> =
        input.again
            ?.let { again ->
                val musicBrainzAgain = SourceId.MUSICBRAINZ in again.asked && SourceId.MUSICBRAINZ in again.unreachable
                again.sources.filter { (source, before) ->
                    enabled.any { it.id == source } &&
                        source !in again.asked &&
                        (keeps(before) || source in again.unreachable) &&
                        !(musicBrainzAgain && waitedOnMusicBrainz(before))
                }
            }.orEmpty()

    // A resolution an earlier run of the track made, used again rather than asked for (#192); not
    // one a failed lookup left provisional, when MusicBrainz is asked again (a *Retry*, #728).
    private val resolvedBefore =
        input.again?.let { again ->
            again.resolution?.takeIf {
                it.outcome != Outcome.Unreachable &&
                    !(it.provisional && SourceId.MUSICBRAINZ in again.asked) &&
                    settings.isEnabled(SourceId.MUSICBRAINZ)
            }
        }
    private val state =
        MutableStateFlow(
            TrackState(
                nowPlaying = nowPlaying,
                normalized = track,
                sources =
                    parts.adapters.associate { adapter ->
                        val fresh = if (adapter in enabled) SourceState.Pending else SOURCE_OFF
                        adapter.id to (keptBefore[adapter.id] ?: fresh)
                    },
                resolution = resolvedBefore,
                // A run that asks again shows the genres the run before it had (#772), until its own
                // are assembled, which replace them.
                genres = input.before.genres,
            ),
        )

    private val guard = CacheGuard(parts.cache, key, onEvent)

    // How long the run's MusicBrainz requests may wait it out when it is busy, all of them together
    // (*Wait when busy*, #1120): in the run's context, for the client's retries.
    private val busyWaits = BusyWaits(settings.musicBrainzWait, time.timeSource)

    // The gather's deadline (#983), shared with the search in the track's language; longer by the
    // time MusicBrainz was waited out (#1120).
    private val budget = GatherBudget(time.timeSource, busy = busyWaits)

    // LRCLIB, asked whether the track has lyrics when a genre is Instrumental (#997): with its switch
    // on; by a *Retry* when it names LRCLIB or the genres before it never called for LRCLIB (an
    // Instrumental the *Retry* brought), and by any other run that asks again.
    private val library =
        LibrarySign(
            parts.lyricsLibrary
                ?.takeIf { settings.isEnabled(SourceId.LRCLIB) }
                ?.let { LibraryLookup(it, parts.cache, onEvent) },
            input,
            asks = input.again?.only?.let { SourceId.LRCLIB in it || !input.before.genres.wantsLibrary() } ?: true,
            fresh = looksAgain,
            keptFailed = input.before.kept?.libraryFailed == true,
            emit = onEvent,
        )
    private val labels =
        TrackLabels(
            parts.musicBrainzLabels,
            parts.lastFmLabels,
            settings,
            key,
            this,
            parts.normalizer::foldForKey,
            input.before.kept,
            // A *Retry* asks the label requests of the sources it asks again only (#772).
            asking = input.again?.takeIf { it.only != null }?.let { it.asked + it.labelsAsked },
            library = library,
            instrumental = parts.vocabulary?.instrumental(),
        )

    /**
     * Gathers the evidence, then the insight: written at once when [control] decides so; else,
     * when it waits for a tap, once the user taps Write; else not at all. The genre labels that take
     * requests (MusicBrainz's artists', Last.fm's track's and album's) are asked for beside the
     * insight, after the evidence, so it does not wait for them; the run
     * ends when both have. Each step that sends requests starts once [control] grants it. A run that
     * looks again (#617) does all of it marked [FreshLookups], so no cache answers its lookups. All
     * of it shares one allowance for waiting out a busy MusicBrainz ([BusyWaits], #1120).
     */
    suspend fun run(): TrackState {
        try {
            emit(PipelineEvent.Received(nowPlaying))
            emit(PipelineEvent.Normalized(track))
            publish(state.value)
            withContext((if (looksAgain) FreshLookups else EmptyCoroutineContext) + busyWaits) {
                gather()
                // In the track's language (#902), before the evidence is assembled: the insight waits for it.
                val interrupted =
                    InLanguage(parts, input, control, guard, budget, this@TrackRun) { state.value }.search()
                control.gathered(
                    Gathered(
                        state.value.sources,
                        state.value.resolution,
                        inLanguage = state.value.inLanguage,
                        languageInterrupted = interrupted,
                    ),
                )
                // LRCLIB's answer kept from before (#997), no request: the genres are weighed with it
                // as they are assembled.
                library.readKept()
                // Published as asked for with the assembled genres, before they may start (#772): a
                // panel says it is still looking.
                val later = labels.later(state.value)
                val evidence = assemble()
                control.assembled(evidence)
                launch {
                    control.await(RunStep.LABELS)
                    try {
                        labels.addLater(later)
                        // Once every label is in, LRCLIB, when a genre is Instrumental (#997).
                        if (library.ask(state.value.genres)) {
                            update {
                                val genres = labels.of(it)
                                it.copy(genres = genres, libraryUnreachable = library.unreachable(genres))
                            }
                        }
                        // Those that failed, a *Retry* may ask again (#772).
                        labels.failedSources.takeIf { it.isNotEmpty() }?.let(control::labelsFailed)
                    } finally {
                        control.ended(RunStep.LABELS)
                    }
                }
                InsightWriter(parts, time, input, evidence, guard, this@TrackRun).write(control)
            }
            return state.value
        } catch (e: CancellationException) {
            emit(PipelineEvent.Superseded(key))
            throw e
        } finally {
            // Whatever ends the run, before its label requests start or while they run: none stays
            // asked for (#772).
            labels.settle()
        }
    }

    override fun emit(event: PipelineEvent) = onEvent(event)

    override fun update(change: (TrackState) -> TrackState) {
        state.update(change)
        publish(state.value)
    }

    // What an earlier run kept (#192); then the cache, but for a look again (#617); then the misses
    // by name in parallel, with resolution alongside (D4), within the gather's deadline (#983).
    private suspend fun gather() {
        input.again?.let { again ->
            val asked = again.asked.filter { source -> enabled.any { it.id == source } }
            if (asked.isNotEmpty()) emit(PipelineEvent.AskedAgain(key, asked.sortedBy { it.ordinal }))
        }
        if (looksAgain) emit(PipelineEvent.LookedAgain(key, enabled.map { it.id }))
        val misses = enabled.filter { adapter -> adapter.id !in keptBefore && (looksAgain || !fromCache(adapter)) }
        if (misses.isEmpty()) return
        control.await(RunStep.LOOKUPS)
        val resolution = CompletableDeferred<Resolution>()
        val made = CompletableDeferred<Boolean>()
        try {
            var resolving: Job? = null
            budget
                .within {
                    resolving = resolve(resolution, made)
                    for (adapter in misses) launch { lookup(adapter, resolution, made) }
                }?.let { after -> cut(misses, resolving?.isCancelled == true, after) }
        } finally {
            control.ended(RunStep.LOOKUPS)
        }
    }

    // What the gather's deadline cut (#983), [after] it ran: the resolution, when it was still
    // [resolving], lands unreachable, and so does each source still pending, so a *Retry* (#724) or back online
    // (#192) asks it again; none of it is kept in the cache. A look again (#617) shows what was found
    // before, as for one that could not be reached.
    private fun cut(
        misses: List<SourceAdapter>,
        resolving: Boolean,
        after: Duration,
    ) {
        val pending = misses.map { it.id }.filter { state.value.sources[it] == SourceState.Pending }
        if (pending.isEmpty() && !resolving) return
        emit(PipelineEvent.GatherCut(key, pending, after, resolution = resolving))
        if (resolving) {
            val resolved = Resolution.unreachable(DEADLINE_REACHED).let { input.lookAgain?.resolution(it) ?: it }
            emit(PipelineEvent.Resolved(key, resolved.outcome, after, resolved.failure))
            update { it.copy(resolution = resolved) }
        }
        for (source in pending) {
            val landed = SourceState.Done(SourceResult(Outcome.Unreachable, emptyList(), after), cached = false)
            land(source, input.lookAgain?.kept(source, landed) ?: landed)
        }
    }

    private suspend fun fromCache(adapter: SourceAdapter): Boolean {
        val hit =
            guard.attempt("read ${adapter.id.tag}") { parts.cache.result(adapter, track, nowPlaying.duration) }
                ?: return false
        land(adapter.id, SourceState.Done(hit, cached = true))
        return true
    }

    // The resolution's request, when it makes one: none when MusicBrainz is off (no resolution, so
    // none made), or the run before's resolution is used again (only with MusicBrainz on).
    private fun CoroutineScope.resolve(
        resolution: CompletableDeferred<Resolution>,
        made: CompletableDeferred<Boolean>,
    ): Job? {
        if (!settings.isEnabled(SourceId.MUSICBRAINZ) || resolvedBefore != null) {
            resolution.complete(resolvedBefore ?: Resolution.NONE)
            made.complete(resolvedBefore != null)
            return null
        }
        return launch {
            val started = time.timeSource.markNow()
            var resolved: Resolution? = null
            try {
                resolved =
                    parts.resolver.resolve(track, nowPlaying.duration).let { input.lookAgain?.resolution(it) ?: it }
                resolution.complete(resolved)
                emit(PipelineEvent.Resolved(key, resolved.outcome, started.elapsedNow(), resolved.failure))
                update { it.copy(resolution = resolved) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // The contract: the adapters' Deferred completes normally, whatever happens here.
                emit(PipelineEvent.SourceFailed(key, SourceId.MUSICBRAINZ, nameOf(e)))
            } finally {
                resolution.complete(Resolution.NONE)
                made.complete(resolved != null && resolved.outcome != Outcome.Unreachable)
            }
        }
    }

    private suspend fun lookup(
        adapter: SourceAdapter,
        resolution: CompletableDeferred<Resolution>,
        made: CompletableDeferred<Boolean>,
    ) {
        val landed =
            try {
                SourceState.Done(adapter.lookup(track, resolution), cached = false)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A bug in one adapter must not take the others down; its type is logged.
                SourceState.Failed(nameOf(e))
            }
        // A look again (#617) shows what it found, or what was found before when it found nothing new.
        val shown = input.lookAgain?.kept(adapter.id, landed) ?: landed
        land(adapter.id, shown)
        if (landed is SourceState.Done && landed.result.outcome != Outcome.Unreachable) {
            when {
                !made.await() -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, NOT_RESOLVED))
                }

                landed.result.provisional -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, PROVISIONAL))
                }

                // A look again (#617) that shows what was found before, in whole or in part: the
                // entry kept stays, and the lines carried keep their own lifetime.
                shown !== landed -> {
                    emit(PipelineEvent.NotCached(key, adapter.id, KEPT_BEFORE))
                }

                else -> {
                    guard.attempt(
                        "write ${adapter.id.tag}",
                    ) { parts.cache.keep(adapter, track, nowPlaying.duration, landed.result) }
                }
            }
        }
    }

    private fun land(
        source: SourceId,
        landed: SourceState,
    ) {
        when (landed) {
            is SourceState.Done -> {
                val result = landed.result
                emit(
                    PipelineEvent.SourceLanded(
                        key = key,
                        source = source,
                        outcome = result.outcome,
                        evidence = result.evidence.size,
                        unverified = result.unverified.size,
                        rejections = result.rejections.size,
                        latency = result.latency,
                        cached = landed.cached,
                    ),
                )
            }

            is SourceState.Failed -> emit(PipelineEvent.SourceFailed(key, source, landed.reason))
            SourceState.Pending -> Unit
        }
        update { it.copy(sources = it.sources + (source to landed)) }
    }

    // The cited lines: the player's release, then each enabled source's evidence lines (verified,
    // or web search's kept ones), in the adapters' order, so the same retrieval gives the same ids
    // whatever order the sources land in; then the lines the song's, the artist's and the album's
    // evidence brought (#1131), those the run has not already, numbered on from its own, as *Sources*
    // lists them.
    // The genre labels: those of the label sources switched on, combined; never evidence.
    private fun assemble(): EvidenceSet {
        val results =
            parts.adapters.mapNotNull { (state.value.sources[it.id] as? SourceState.Done)?.result }
        // The lines found in the track's language after the English ones (#902), so the English ids stay.
        val own =
            playerLines(nowPlaying, time.clock.now()) +
                (results.flatMap { it.evidence } + state.value.languageDrafts()).filter {
                    settings.isEnabled(
                        it.source,
                    )
                }
        val seen = own.mapTo(mutableSetOf(), ::triple)
        val items = input.items.filter { settings.isEnabled(it.source) && seen.add(triple(it)) }
        val evidence = EvidenceSet.of(own + items, parts.hasher, state.value.knownLanguage())
        val brought = items.mapTo(mutableSetOf(), ::triple)
        val itemIds = evidence.lines.filter { triple(it.draft()) in brought }.mapTo(mutableSetOf()) { it.id }
        // The genres and how their later requests stand, in one update with the evidence (#772).
        update {
            val genres = labels.of(it)
            it.copy(
                evidence = evidence,
                itemIds = itemIds,
                genres = genres,
                laterLabels = labels.states(),
                laterFound = labels.found,
                laterAnswered = labels.answered,
                genresAssembled = true,
                libraryUnreachable = library.unreachable(genres),
            )
        }
        emit(
            PipelineEvent.EvidenceAssembled(
                key = key,
                lines = evidence.lines.size,
                sources =
                    evidence.lines
                        .map { it.source }
                        .distinct()
                        .size,
                fingerprint = evidence.fingerprint,
            ),
        )
        return evidence
    }

    internal companion object {
        val SOURCE_OFF: SourceState =
            SourceState.Done(SourceResult(Outcome.SourceOff, emptyList(), Duration.ZERO), cached = false)

        const val NOT_RESOLVED: String = "the MusicBrainz resolution it was checked against could not be made"

        const val PROVISIONAL: String = "a request of its verification failed, so a later lookup may verify it"

        const val KEPT_BEFORE: String = "a look again shows lines found before, which keep their own lifetime (#617)"

        /** Why a resolution the gather's deadline cut could not be made (#983, [Resolution.failure]). */
        const val DEADLINE_REACHED: String = "GatherDeadline"

        fun nameOf(e: Throwable): String = e::class.simpleName ?: "Exception"

        // What tells one line from another for the run's evidence: its source, scope and words.
        fun triple(draft: EvidenceDraft) = Triple(draft.source, draft.scope, draft.text)

        // Whether [before] rejected a match only for want of what MusicBrainz resolves (#1069).
        fun waitedOnMusicBrainz(before: SourceState): Boolean =
            before is SourceState.Done && before.result.rejections.any { it.withoutMusicBrainz }

        // Whether a source's state from an earlier run is kept rather than asked again (#192).
        fun keeps(before: SourceState): Boolean =
            when (before) {
                is SourceState.Done -> before.result.outcome !in setOf(Outcome.Unreachable, Outcome.SourceOff)
                is SourceState.Failed -> true
                SourceState.Pending -> false
            }
    }
}

/** Cache calls that log and carry on when the cache fails, as if it had missed. */
internal class CacheGuard(
    private val cache: PipelineCache,
    private val key: String,
    private val emit: (PipelineEvent) -> Unit,
) {
    suspend fun <T> attempt(
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
            emit(PipelineEvent.CacheFailed(key, operation, TrackRun.nameOf(e)))
            null
        }
}

/**
 * The release [nowPlaying]'s player reports, as the simulator words it (`P` evidence), retrieved
 * [at]: what a run assembles first (`TrackRun`), and what the song's, the artist's and the album's
 * insights rebuild the run's evidence with (`ItemInsights.ownEvidence`, #522).
 */
internal fun playerLines(
    nowPlaying: NowPlaying,
    at: Instant,
): List<EvidenceDraft> =
    listOfNotNull(nowPlaying.album?.trim()?.takeIf { it.isNotEmpty() }).map { album ->
        EvidenceDraft(
            source = SourceId.PLAYER,
            scope = Scope.RELEASE,
            text = "This recording is on the release \"$album\".",
            url = null,
            retrievedAt = at,
        )
    }
