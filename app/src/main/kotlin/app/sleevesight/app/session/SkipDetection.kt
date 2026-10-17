package app.sleevesight.app.session

import android.database.SQLException
import android.util.Log
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackChangeRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What skip detection reads of a track change (D5; `design/design.md`, *Track changes*).
 *
 * @property player the player whose track changed.
 * @property timeLeft the time the track it left had left at the change ([SessionChange.timeLeft]):
 *   negative past its end; null when its length or position is unknown, or on a switch to another
 *   player's track.
 * @property lateStates how many `PLAYING` states the player reported in the
 *   [SkipDetection.LATE_WINDOW] before the change, equal ones included.
 */
data class ChangeFeatures(
    val player: String,
    val timeLeft: Duration?,
    val lateStates: Int,
) {
    companion object {
        /** The features of [change], as the session layer saw it. */
        fun of(change: SessionChange): ChangeFeatures =
            ChangeFeatures(
                player = change.track.player,
                timeLeft = change.timeLeft,
                lateStates =
                    change.states.count {
                        it.state.status == PlaybackStatus.PLAYING &&
                            (change.changedAt - it.at).milliseconds in Duration.ZERO..SkipDetection.LATE_WINDOW
                    },
            )

        /** The features of [record], a change recorded in the session history. */
        fun of(record: TrackChangeRecord): ChangeFeatures =
            ChangeFeatures(
                player = record.player,
                timeLeft = record.timeLeft,
                lateStates =
                    record.states.count {
                        it.status == PlaybackStatus.PLAYING.name &&
                            it.receivedBefore in Duration.ZERO..SkipDetection.LATE_WINDOW
                    },
            )
    }
}

/**
 * D5's rules for telling a skip in the player from a natural end, as measured in Phase 0
 * (`design/design.md`, *Track changes*; `design/architecture.md`, *Writing insights
 * automatically*). Pure: a change's features, the user's *Crossfade*, and the crossfade learned for
 * the player decide.
 *
 * - **No time left known** (a switch to another player's track, a track with no length or no
 *   position): a natural end. Detection errs towards "ended on its own", which costs a tap; the other
 *   mistake costs a paid call nobody asked for.
 * - **A fixed crossfade *c*:** a natural end with at most *c* + [MARGIN] left (past the end
 *   included); for a player whose late cluster is measured (Spotify), also with the late cluster
 *   and at most [WINDOW] left, so a value set below the player's real crossfade does not read its
 *   natural ends as skips.
 * - **Autodetect, Spotify:** a natural end with at most [WINDOW] left and the late cluster (at
 *   least [LATE_CLUSTER] `PLAYING` states in the [LATE_WINDOW] before the change), or a time left
 *   of at most [MARGIN] (past the end included), or within [MARGIN] of the crossfade learned from
 *   its natural ends ([CrossfadeLearning]).
 * - **Autodetect, any other player:** timing alone, a natural end with at most [WINDOW] left.
 *
 * Anything else is a skip. The one accepted exception to erring towards a natural end: under
 * Autodetect, a Spotify natural end without the late cluster (the session layer started late, a
 * restart), with a crossfade but none learned yet, reads as a skip.
 */
object SkipDetection {
    /** The players whose late cluster of states before a natural end has been measured: Spotify. */
    val CLUSTER_PLAYERS: Set<String> = setOf(Settings.SPOTIFY)

    /** The most time left at which a change may be a natural end: the longest crossfade, 15 s. */
    val WINDOW: Duration = Crossfade.Fixed.MAX

    /** How far the time left may be from 0 or the crossfade, for a natural end. */
    val MARGIN: Duration = 500.milliseconds

    /** How long before a change the late cluster is counted. */
    val LATE_WINDOW: Duration = 1.5.seconds

    /** How many `PLAYING` states in the [LATE_WINDOW] make the late cluster (about six measured). */
    const val LATE_CLUSTER: Int = 5

    /** Whether [features] show the late cluster, for a player whose cluster is measured. */
    fun hasLateCluster(features: ChangeFeatures): Boolean =
        features.player in CLUSTER_PLAYERS && features.lateStates >= LATE_CLUSTER

    /**
     * The time left at a natural end the late cluster vouches for, with at most [WINDOW] left; null
     * for any other change. What the crossfade is learned from: no setting and no learning moves it,
     * so learning never feeds on its own judgements.
     */
    fun clusteredEnd(features: ChangeFeatures): Duration? =
        features.timeLeft?.takeIf { it <= WINDOW && hasLateCluster(features) }

    /** Why the track changed: [ChangeCause.SKIP_IN_PLAYER] or [ChangeCause.NATURAL_END], by the rules above. */
    fun cause(
        features: ChangeFeatures,
        crossfade: Crossfade,
        learned: Duration?,
    ): ChangeCause {
        val left = features.timeLeft ?: return ChangeCause.NATURAL_END
        val clustered = clusteredEnd(features) != null
        val natural =
            when (crossfade) {
                is Crossfade.Fixed -> left <= crossfade.value + MARGIN || clustered
                Crossfade.Autodetect ->
                    when {
                        left > WINDOW -> false
                        features.player !in CLUSTER_PLAYERS -> true
                        else ->
                            clustered ||
                                left <= MARGIN ||
                                learned?.let { (left - it).absoluteValue <= MARGIN } == true
                    }
            }
        return if (natural) ChangeCause.NATURAL_END else ChangeCause.SKIP_IN_PLAYER
    }
}

/**
 * The crossfade learned for one player from its natural ends (D5): [value] once two in a row agree
 * within [AGREEMENT] (their mean), replaced when two later ones in a row agree on another; a sample
 * that agrees with neither waits as the [candidate]. So a crossfade the user changes in the player is
 * followed after two natural ends. Only the natural ends the late cluster vouches for are samples
 * ([SkipDetection.clusteredEnd]); the value only adds natural ends that lack the cluster, and never
 * turns a change into a skip.
 */
data class CrossfadeLearning(
    val value: Duration? = null,
    val candidate: Duration? = null,
) {
    /** What is learned once [timeLeft], the time left at a natural end, is added. */
    fun learn(timeLeft: Duration): CrossfadeLearning {
        val agreed = value
        val waiting = candidate
        return when {
            agreed != null && agree(timeLeft, agreed) -> copy(candidate = null)
            waiting != null && agree(timeLeft, waiting) -> CrossfadeLearning(value = (waiting + timeLeft) / 2)
            else -> copy(candidate = timeLeft)
        }
    }

    private fun agree(
        a: Duration,
        b: Duration,
    ) = (a - b).absoluteValue <= AGREEMENT

    companion object {
        /** How close two natural ends' times left must be to agree (60 ms apart, measured). */
        val AGREEMENT: Duration = 100.milliseconds
    }
}

/**
 * Skip detection for the session layer (D5; `design/architecture.md`, *Session layer*): judges each
 * track change as [SessionRepository] publishes it ([classify]), with the user's *Crossfade* as
 * [crossfade] reads it then, and learns each player's crossfade from its natural ends
 * ([CrossfadeLearning]). Thread-safe; the repository calls it on the main thread, once per change,
 * so each change is judged and learned from once, in the order the changes were published.
 *
 * What it has learned lasts across restarts through the record of track changes (D15): before the
 * session layer reads any session, [restoreFrom] relearns from the latest [RESTORED] changes
 * recorded, oldest first, and the session layer waits for it ([restored]). So it is lost with them,
 * on *Clear history* ([reset]), or when they are older than *Keep history for*.
 */
class SkipDetector(
    private val crossfade: () -> Crossfade,
) {
    private val lock = Any()
    private var learning: Map<String, CrossfadeLearning> = emptyMap()
    private val learnedState = MutableStateFlow<Map<String, Duration>>(emptyMap())
    private val restoredState = MutableStateFlow(false)

    /**
     * The crossfade learned for each player, by package name, a player with none learned absent:
     * for the diagnostics screen (#54).
     */
    val learned: StateFlow<Map<String, Duration>> = learnedState.asStateFlow()

    /** Whether the recorded changes have been relearned from ([restoreFrom]); the session layer waits for it. */
    val restored: StateFlow<Boolean> = restoredState.asStateFlow()

    /**
     * [change] with its [SessionChange.cause] and the [SessionChange.learnedCrossfade] that judged
     * it; what it teaches is learned after the judgement.
     */
    fun classify(change: SessionChange): SessionChange =
        synchronized(lock) {
            val features = ChangeFeatures.of(change)
            val before = learning[features.player]?.value
            val cause = SkipDetection.cause(features, crossfade(), before)
            learnFrom(features)
            change.copy(cause = cause, learnedCrossfade = before)
        }

    /** Learns from [newestFirst], recorded changes as the store pages them, oldest first; then [restored]. */
    fun restore(newestFirst: List<TrackChangeRecord>) {
        synchronized(lock) { newestFirst.asReversed().forEach { learnFrom(ChangeFeatures.of(it)) } }
        restoredState.value = true
    }

    /**
     * Relearns from the latest [RESTORED] changes in [history], read on [io]; a read that fails is
     * logged by its type and learns nothing. [restored] however the read ends. Called once, at the start of the
     * process, before the session layer reads any session, so no change of this process is among them.
     */
    suspend fun restoreFrom(
        history: SessionHistory,
        io: CoroutineContext,
    ) {
        var records = emptyList<TrackChangeRecord>()
        try {
            records = withContext(io) { history.changes(RESTORED).changes }
        } catch (e: SQLException) {
            Log.w(TAG, "Skip detection could not read the track changes: ${e::class.simpleName}")
        } finally {
            // Whatever ends the read, the session layer, which waits for this, is not held up.
            restore(records)
        }
    }

    /** Forgets what was learned: *Clear history* deleted the changes it was learned from. */
    fun reset() {
        synchronized(lock) {
            learning = emptyMap()
            learnedState.value = emptyMap()
        }
    }

    private fun learnFrom(features: ChangeFeatures) {
        val left = SkipDetection.clusteredEnd(features) ?: return
        learning = learning + (features.player to (learning[features.player] ?: CrossfadeLearning()).learn(left))
        learnedState.value = learning.mapNotNull { (player, learnt) -> learnt.value?.let { player to it } }.toMap()
    }

    companion object {
        /**
         * How many recorded changes are relearned from at start: the learning rests on the last few
         * natural ends of each player, and 200 changes are a day or two of listening.
         */
        const val RESTORED: Int = 200

        private const val TAG = "Sleevesight"
    }
}
