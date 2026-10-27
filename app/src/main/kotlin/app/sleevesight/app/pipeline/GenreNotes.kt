package app.sleevesight.app.pipeline

import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreDefinitions
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.CardKey
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** *Why is this …?* in a genre's panel (#523), as the panel shows it. */
sealed interface GenreReason {
    /** Not offered: see [GenreReasons.key]. */
    data object None : GenreReason

    /** Offered: a tap writes it, a paid call. */
    data object Offered : GenreReason

    /**
     * Asked for, or kept from before: [state] is [InsightState.Writing], [InsightState.Written],
     * [InsightState.NoEvidence], or [InsightState.Failed], beside which it is offered again.
     */
    data class Shown(
        val state: InsightState,
    ) : GenreReason
}

/**
 * What a genre's panel shows besides its table (#523): what the genre is ([definition]), and why
 * the track has it ([reason], written on a tap, [explain]).
 */
interface GenreNotes {
    /** The definition of the genre [genre] stands for (`GenreLabel.identity`), or null for none. */
    suspend fun definition(genre: String): GenreDefinition?

    /** [track]'s reason for [genre], as it changes. */
    fun reason(
        track: TrackState,
        genre: String,
    ): Flow<GenreReason>

    /** A tap on *Why is this …?*: [track]'s reason for [genre] is written, unless it is under way. */
    fun explain(
        track: TrackState,
        genre: String,
    )

    /** Neither: the panel shows its table alone (previews, tests). */
    object None : GenreNotes {
        override suspend fun definition(genre: String): GenreDefinition? = null

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(GenreReason.None)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }
}

/**
 * The app's [GenreNotes]: definitions from [definitions] while Wikipedia is switched on under
 * *Sources* (its lookups behind the same switch), off the main thread ([io]); reasons written with
 * the live pipeline's [parts] (its AI service, synthesis cache and hasher, [GenreReasons]) under
 * the [settings] as last read.
 *
 * A reason is kept with the track, as its insight is: in memory, by its card's key, so it shows
 * again with no call when the panel is closed and opened, another genre is opened in between, or a
 * page is left and opened again; and in the synthesis cache, read the first time the panel asks for
 * a reason ([reason]), so it shows again when the track plays again later. A settings change that
 * changes its key (another AI service, model or effort, new evidence) offers it again. The writing
 * runs in [scope], the process's, so leaving the panel does not stop a call paid for; it is marked
 * with [epoch], and after *Clear history* ([forget]) nothing it writes is shown or kept.
 */
@Suppress("LongParameterList") // Where definitions and reasons come from, and where they run.
class AppGenreNotes(
    private val definitions: GenreDefinitions,
    private val parts: StateFlow<PipelineParts?>,
    private val settings: StateFlow<Settings?>,
    private val scope: CoroutineScope,
    private val epoch: ClearEpoch = ClearEpoch(),
    private val io: CoroutineContext = EmptyCoroutineContext,
) : GenreNotes {
    // The reasons shown, by key, oldest first, at most [KEPT].
    private val shown = MutableStateFlow<Map<CardKey, InsightState>>(emptyMap())

    // The keys whose cached reason was looked for, and those being written.
    private val looked: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()
    private val writing: MutableSet<CardKey> = ConcurrentHashMap.newKeySet()

    override suspend fun definition(genre: String): GenreDefinition? {
        val now = settings.value ?: return null
        if (!now.showGenres || !now.isEnabled(SourceId.WIKIPEDIA)) return null
        return withContext(io) { definitions.of(genre) }
    }

    override fun reason(
        track: TrackState,
        genre: String,
    ): Flow<GenreReason> =
        combine(parts, settings) { made, now -> keyOf(made, now, track, genre) }
            .distinctUntilChanged()
            .onEach { key -> key?.let { look(it, track) } }
            .combine(shown) { key, reasons ->
                when (key) {
                    null -> GenreReason.None
                    else -> reasons[key]?.let { GenreReason.Shown(it) } ?: GenreReason.Offered
                }
            }

    override fun explain(
        track: TrackState,
        genre: String,
    ) {
        val reasons = parts.value?.let { GenreReasons(it.ai, it.cache, it.hasher) } ?: return
        val key = settings.value?.let { reasons.key(track, genre, it) } ?: return
        if ((shown.value[key] as? InsightState.Failed) == null && key in shown.value) return
        if (!writing.add(key)) return
        val label =
            track.genres.all
                .firstOrNull { it.genre.identity == genre }
                ?.genre
                ?.label ?: genre
        scope.launch(epoch.mark()) {
            val work = coroutineContext
            val show: (InsightState) -> Unit = { if (epoch.isCurrent(work)) put(key, it) }
            try {
                // One kept meanwhile (another panel's) is shown with no call.
                reasons.kept(key, track)?.let(show) ?: reasons.write(key, track, label, show)
            } catch (e: CancellationException) {
                throw e
            } catch (
                // A bug, not the network, which the writer maps to a state: shown as failed, offered again.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                show(InsightState.Failed(e::class.simpleName ?: FAILED))
            } finally {
                writing.remove(key)
            }
        }
    }

    /** Forgets the reasons shown, for *Clear history* (#547); the cache's are deleted with it. */
    fun forget() {
        shown.value = emptyMap()
        looked.clear()
    }

    // The reason's key, or null when none is offered.
    private fun keyOf(
        made: PipelineParts?,
        now: Settings?,
        track: TrackState,
        genre: String,
    ): CardKey? {
        if (made == null || now == null) return null
        return GenreReasons(made.ai, made.cache, made.hasher).key(track, genre, now)
    }

    // The reason kept in the synthesis cache for [key], read once, unless one is shown already.
    private fun look(
        key: CardKey,
        track: TrackState,
    ) {
        if (key in shown.value || !looked.add(key)) return
        val made = parts.value ?: return
        scope.launch(epoch.mark()) {
            val work = coroutineContext
            val kept = GenreReasons(made.ai, made.cache, made.hasher).kept(key, track)
            if (kept != null && epoch.isCurrent(work)) shown.update { if (key in it) it else it.with(key, kept) }
        }
    }

    private fun put(
        key: CardKey,
        state: InsightState,
    ) = shown.update { it.with(key, state) }

    // [this] with [state] for [key], last, the oldest dropped past [KEPT].
    private fun Map<CardKey, InsightState>.with(
        key: CardKey,
        state: InsightState,
    ): Map<CardKey, InsightState> {
        val next = LinkedHashMap(this)
        next.remove(key)
        next[key] = state
        while (next.size > KEPT) next.remove(next.keys.first())
        return next
    }

    private companion object {
        /** Enough for the genres of the last tracks played; the cache keeps the rest. */
        const val KEPT = 64

        const val FAILED = "failed"
    }
}
