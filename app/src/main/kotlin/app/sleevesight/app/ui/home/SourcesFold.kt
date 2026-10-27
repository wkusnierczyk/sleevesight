package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NowPlaying

/**
 * How far the *Sources* list under an insight is unfolded (#269): [Folded], some screenfuls of
 * it ([Pages]), or [All] of it. A screenful is a fixed [PAGE] lines, not a height measured from
 * the screen: lines differ in height, and a fixed count reads the same on every phone and in a
 * test.
 */
internal sealed interface Fold {
    /** None of the list shows: *Sources*' chip only (#440, #623). */
    data object Folded : Fold

    /** The first [count] screenfuls of the list, at least one. */
    data class Pages(
        val count: Int,
    ) : Fold {
        init {
            require(count >= 1) { "At least one screenful, was $count" }
        }
    }

    /** Every entry. */
    data object All : Fold

    /** How many of [total] lines show. */
    fun shown(total: Int): Int =
        when (this) {
            Folded -> 0
            is Pages -> minOf(total, count * PAGE)
            All -> total
        }

    /** One screenful more, or all once that reaches the end of [total] lines. */
    fun more(total: Int): Fold =
        when (this) {
            Folded -> Pages(1)
            is Pages -> if ((count + 1) * PAGE >= total) All else Pages(count + 1)
            All -> All
        }

    /**
     * Unfolded far enough to show the line at [index] (from 0, in the list as it shows): by whole
     * screenfuls, so *more* goes on from there; a fold that shows it already stays.
     */
    fun reaching(index: Int): Fold {
        val needed = index / PAGE + 1
        return when (this) {
            Folded -> Pages(needed)
            is Pages -> if (count >= needed) this else Pages(needed)
            All -> All
        }
    }

    /** As it is kept for the next track: a list shown past its first screenful, not in full, at its first. */
    fun forNextTrack(): Fold = if (this is Pages) Pages(1) else this

    companion object {
        /**
         * A screenful: 8 lines. At the width and density of a common phone (411 by 891 dp, 420
         * dpi), an evidence line of one or two lines of text with its scope and link under it is
         * about 50 to 70 dp, so 8 come to some 450 to 550 dp: with the controls above and below,
         * about the screen left once the list is scrolled to, and short enough that what follows
         * it is never far. Not a setting: *show all* is a tap away for whoever wants every line.
         */
        const val PAGE: Int = 8
    }
}

/**
 * What the fold is kept as across recompositions and a recreated screen (a rotation, or Android
 * restoring the screen it had closed): the [fold] of the track last shown, [track], so a new
 * track can start folded. [touched] is whether the chip or *Close*, a paging control, a citation or a
 * chip changed the fold on that track, so changes in how much shows are announced only once the listener has
 * acted on the list; it is not saved.
 */
@Stable
internal class FoldMemory(
    fold: Fold = Fold.Folded,
    track: NowPlaying? = null,
) {
    var fold: Fold by mutableStateOf(fold)
    var track: NowPlaying? by mutableStateOf(track)
    var touched: Boolean by mutableStateOf(false)

    companion object {
        /**
         * Saved as plain values: the fold, and of its track what [TrackIdentity.playsOn] reads (the
         * player, the media id, the queue item, the title and the artists).
         */
        val Saver: Saver<FoldMemory, Any> =
            listSaver(
                save = { memory ->
                    val fold = memory.fold
                    val track = memory.track
                    listOf(
                        when (fold) {
                            Fold.Folded -> FOLDED
                            is Fold.Pages -> fold.count
                            Fold.All -> ALL
                        },
                        track?.player,
                        track?.mediaId,
                        track?.queueItemId,
                        track?.title,
                        track?.artists,
                    )
                },
                restore = { saved ->
                    val fold =
                        when (val count = saved[0] as Int) {
                            FOLDED -> Fold.Folded
                            ALL -> Fold.All
                            else -> Fold.Pages(count)
                        }
                    val player = saved[1] as String?
                    val track =
                        player?.let {
                            NowPlaying(
                                title = saved[4] as String,
                                artists = saved[5] as String,
                                player = it,
                                mediaId = saved[2] as String?,
                                queueItemId = saved[3] as Long?,
                            )
                        }
                    FoldMemory(fold, track)
                },
            )

        private const val FOLDED = 0
        private const val ALL = -1
    }
}

/**
 * The fold of the list shown beside [playing]: as it is kept while [playing] plays on the track
 * last shown ([TrackIdentity.playsOn]); for a new track, [start] (folded on Home), or with [keep]
 * (*Keep Sources as you left them*) as it was left ([Fold.forNextTrack]); [keep] is null until the
 * settings are read, and a new track shows [start] until then. Worked out as it is read, so a new
 * track never shows the last one's fold for a frame; [follow] then keeps it as the new track's, so
 * a track played again after others starts afresh too. On a page of its own (#447) the list starts
 * at its first screenful, and is never kept.
 */
internal class SourcesFold(
    private val memory: FoldMemory,
    private val playing: NowPlaying,
    private val keep: Boolean?,
    private val start: Fold = Fold.Folded,
) {
    val fold: Fold
        get() {
            val set = memory.fold
            val track = memory.track
            return when {
                track != null && TrackIdentity.playsOn(track, playing) -> set
                keep == true -> set.forNextTrack()
                else -> start
            }
        }

    /** Whether how much shows is announced: once the fold was changed on the track playing. */
    val announced: Boolean
        get() = memory.touched && memory.track?.let { TrackIdentity.playsOn(it, playing) } == true

    /** Sets the fold for the track playing, by the listener's action. */
    fun set(fold: Fold) {
        memory.fold = fold
        memory.track = playing
        memory.touched = true
    }

    /** Unfolds far enough to show the line at [index] of the list as it shows. */
    fun reach(index: Int) = set(fold.reaching(index))

    /**
     * On a new track, keeps the fold it shows as its own, untouched; not until the settings are
     * read ([keep] null), so a fold kept for the next track is not lost to the folded default.
     */
    fun follow() {
        val track = memory.track
        if (keep == null || (track != null && TrackIdentity.playsOn(track, playing))) return
        memory.fold = fold
        memory.track = playing
        memory.touched = false
    }
}

/**
 * The fold's memory, kept as [FoldMemory] is; remembered above whatever comes and goes with the
 * track (nothing playing for a while), so the fold outlives it.
 */
@Composable
internal fun rememberFoldMemory(): FoldMemory = rememberSaveable(saver = FoldMemory.Saver) { FoldMemory() }

/**
 * The fold of the *Sources* list beside [playing], from [memory], a new track's [start] unless
 * [keep]; a new track's is kept as its own.
 */
@Composable
internal fun sourcesFold(
    memory: FoldMemory,
    playing: NowPlaying,
    keep: Boolean?,
    start: Fold = Fold.Folded,
): SourcesFold {
    val fold = SourcesFold(memory, playing, keep, start)
    SideEffect { fold.follow() }
    return fold
}
