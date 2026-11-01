package app.sleevesight.app.pipeline

import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

// Genres' definitions from Wikipedia (#523), fetched ahead for the playing track (#786).

/** A genre's definition from Wikipedia (#523), as its panel shows it (#786). */
sealed interface DefinitionState {
    /** Being looked up: the turning record and "Looking on Wikipedia…". */
    data object Looking : DefinitionState

    /** Found: the definition, with its credit. */
    data class Found(
        val definition: GenreDefinition,
    ) : DefinitionState

    /** The genre has none (#663): the panel says so, and offers *Search with AI*. */
    data object NotFound : DefinitionState

    /** The lookup failed: "Wikipedia could not be reached.", with *Retry*. */
    data object Failed : DefinitionState
}

/** What a genre is, as its panel shows it (#523, #786): its definition, and *Retry* under a failed lookup. */
interface DefinitionFeed {
    /**
     * The definition of the genre [genre] stands for (`GenreLabel.identity`), as it changes (#786):
     * looking, found, not found when it has none, which the panel says (#663), or failed; null while
     * lookups are off (*Show genres* or Wikipedia off), looked up again when they come on.
     */
    fun definition(genre: String): Flow<DefinitionState?>

    /** A tap on *Retry* under a failed lookup (#786): [genre]'s definition is asked for again. */
    fun retryDefinition(genre: String) = Unit
}

/**
 * The app's definitions (#523, #786; `design/architecture.md`, *Genre definitions*): from
 * [definitions] while *Show genres* and Wikipedia under *Sources* are on in [settings] (its lookups
 * behind the same switch), off the main thread ([io]), each lookup in a coroutine of its own in
 * [scope], marked with [epoch].
 *
 * They are fetched ahead: as soon as the playing track's genres are known, and each time it gains
 * some, every genre of its table is looked up at once, whether any panel is open or not
 * ([fetchAhead], which the app graph runs on the pipeline's track). Each genre has one state
 * ([lookedUp]), shared by every panel and page that shows it: looking, found, not found or failed.
 * Each play of a track asks [definitions] again for every genre of its table, once, whatever its
 * state: it serves what it keeps (also after a restart, with a store) and asks Wikipedia again only
 * past Wikipedia's lifetime, so a definition, or a genre's having none, expires as the source's
 * findings do. Meanwhile a genre found or not found keeps showing as it is, replaced only by an
 * answer that differs (a check that fails leaves it); one being looked up is not asked twice; a
 * failed one is also asked again on *Retry* ([retryDefinition]). A panel showing a genre that no
 * track fetched ahead (one *Search with AI* named) asks for it itself, as does one whose state
 * *Clear history* forgot ([forget]). A
 * state is shown only under the lock [forget] clears under, after its check that no *Clear history*
 * came since its lookup started, as `ShownSearches.publish` does.
 */
class AppGenreDefinitions(
    private val definitions: GenreDefinitions,
    private val settings: StateFlow<Settings?>,
    private val scope: CoroutineScope,
    private val epoch: ClearEpoch = ClearEpoch(),
    private val io: CoroutineContext = EmptyCoroutineContext,
) : DefinitionFeed {
    // The states, by genre, and their lookups under way; the play fetched ahead (the track's
    // identity) and the genres asked on it. Changed under [lock].
    private val lock = Any()
    private val states = MutableStateFlow<Map<String, DefinitionState>>(emptyMap())
    private val lookups = mutableMapOf<String, Job>()
    private var playing: Any? = null
    private val ahead = mutableSetOf<String>()

    /** Each genre's definition as looked up (#786), by its identity, whatever the settings say now. */
    val lookedUp: StateFlow<Map<String, DefinitionState>> = states

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun definition(genre: String): Flow<DefinitionState?> =
        settings
            .map(::lookingUp)
            .distinctUntilChanged()
            .flatMapLatest { on ->
                if (!on) {
                    flowOf(null)
                } else {
                    // One no track fetched ahead, or forgotten by *Clear history*, is asked for here.
                    states
                        .map { it[genre] }
                        .distinctUntilChanged()
                        .onEach { if (it == null) lookUp(genre) }
                        .map { it ?: DefinitionState.Looking }
                }
            }

    override fun retryDefinition(genre: String) = lookUp(genre, Ask.RETRY)

    /**
     * Fetches ahead (#786) the definitions of [track]'s genres, every genre of its table, under the
     * settings as last read: each once a play of the track, whatever its state, so [GenreDefinitions]
     * serves what it keeps (no request) and asks Wikipedia again past its lifetime; a genre found or
     * not found keeps showing as it is meanwhile. Nothing while lookups are off.
     */
    fun fetchAhead(track: TrackState?) {
        if (track == null || !lookingUp(settings.value)) return
        val identity = track.normalized to track.nowPlaying.duration
        val genres = track.genres.all.map { it.genre.identity }
        val fresh =
            synchronized(lock) {
                if (identity != playing) {
                    playing = identity
                    ahead.clear()
                }
                genres.filter(ahead::add)
            }
        for (genre in fresh) lookUp(genre, Ask.PLAY)
    }

    /**
     * Fetches ahead ([fetchAhead]) the definitions of each of [tracks] (the pipeline's track), and
     * again as the settings change (lookups coming on), until cancelled.
     */
    suspend fun fetchAhead(tracks: Flow<TrackState?>) {
        combine(tracks, settings) { track, _ -> track }.collect(::fetchAhead)
    }

    // Who asks for a definition: a panel showing a genre with no state ([PANEL]), *Retry* under a
    // failed lookup ([RETRY]), or the fetch ahead, once a play, whatever the state ([PLAY]).
    private enum class Ask { PANEL, RETRY, PLAY }

    // Looks [genre]'s definition up, unless lookups are off, it is being looked up, or [ask] does not
    // reach its state. Looking shows only where there is nothing better: no state, or a failed one; a
    // definition found or not found stays shown while it is checked again.
    private fun lookUp(
        genre: String,
        ask: Ask = Ask.PANEL,
    ) {
        if (!lookingUp(settings.value)) return
        synchronized(lock) {
            val state = states.value[genre]
            val held = state is DefinitionState.Found || state == DefinitionState.NotFound
            val open =
                when (ask) {
                    Ask.PANEL -> state == null
                    Ask.RETRY -> state == null || state == DefinitionState.Failed
                    Ask.PLAY -> true
                }
            if (!open || genre in lookups) return
            val job = scope.launch(epoch.mark(), start = CoroutineStart.LAZY) { define(genre) }
            lookups[genre] = job
            if (!held) states.update { it.with(genre, DefinitionState.Looking) }
            job.invokeOnCompletion { synchronized(lock) { if (lookups[genre] === job) lookups -= genre } }
            job.start()
        }
    }

    // [genre]'s definition from Wikipedia, shown unless *Clear history* came since it started.
    private suspend fun define(genre: String) {
        val work = currentCoroutineContext()
        val state =
            try {
                when (val found = withContext(io) { definitions.lookup(genre) }) {
                    is Lookup.Found -> DefinitionState.Found(found.value)
                    Lookup.NotFound -> DefinitionState.NotFound
                    is Lookup.Failed -> DefinitionState.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                // A bug, not the network, which the lookup maps to failed: shown as failed, offered again.
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                DefinitionState.Failed
            }
        synchronized(lock) {
            if (epoch.isCurrent(work) && work.isActive) {
                states.update { now ->
                    val before = now[genre]
                    // A check that failed leaves a definition found or not found as it was; an answer
                    // that differs replaces it (past Wikipedia's lifetime, the article changed).
                    val keep =
                        before == state ||
                            state == DefinitionState.Failed &&
                            (before is DefinitionState.Found || before == DefinitionState.NotFound)
                    if (keep) now else now.with(genre, state)
                }
            }
        }
    }

    // Whether definitions are looked up under [now]: *Show genres* and Wikipedia on.
    private fun lookingUp(now: Settings?): Boolean = now != null && now.showGenres && now.isEnabled(SourceId.WIKIPEDIA)

    /**
     * Forgets the states, their lookups under way cancelled, for *Clear history* (#547); the cache's
     * are deleted with it. After the epoch's advance: a lookup that checked it before shows its state
     * before this clears it, never after.
     */
    fun forget() =
        synchronized(lock) {
            lookups.values.forEach { it.cancel() }
            lookups.clear()
            states.value = emptyMap()
            playing = null
            ahead.clear()
        }

    // [this] with [state] for [genre], last, the oldest dropped past [DEFINITIONS_KEPT].
    private fun Map<String, DefinitionState>.with(
        genre: String,
        state: DefinitionState,
    ): Map<String, DefinitionState> {
        val next = LinkedHashMap(this)
        next.remove(genre)
        next[genre] = state
        while (next.size > DEFINITIONS_KEPT) next.remove(next.keys.first())
        return next
    }

    private companion object {
        /** Every genre of the vocabulary, as many as [GenreDefinitions] keeps in memory. */
        const val DEFINITIONS_KEPT = GenreDefinitions.DEFAULT_CAPACITY
    }
}

/**
 * The genres of [track]'s table whose definition, as looked up ([states], `AppGenreNotes.lookedUp`),
 * Wikipedia does not have (#786), while definitions are looked up under [settings] (*Show genres*
 * and Wikipedia on): those *Search with AI*, the setting, searches by itself (`AutoSearches`).
 */
internal fun genresWithNoDefinition(
    track: TrackState,
    settings: Settings,
    states: Map<String, DefinitionState>,
): Set<String> {
    if (!settings.showGenres || !settings.isEnabled(SourceId.WIKIPEDIA)) return emptySet()
    return track.genres.all
        .map { it.genre.identity }
        .filterTo(LinkedHashSet()) { states[it] == DefinitionState.NotFound }
}
