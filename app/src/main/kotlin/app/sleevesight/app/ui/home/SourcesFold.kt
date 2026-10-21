package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
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
    /** None of the list shows: the heading, the chips and *show* only. */
    data object Folded : Fold

    /** The first [count] screenfuls of the list, at least one. */
    data class Pages(
        val count: Int,
    ) : Fold {
        init {
            require(count >= 1) { "At least one screenful, was $count" }
        }
    }

    /** Every line. */
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

    /** As it is kept for the next track: a list unfolded past its first screenful at its first. */
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
 * What the fold is kept as across recompositions and a recreated activity (a rotation): the
 * [fold] last set, and the track it was set for ([track]), so a new track can start folded.
 */
@Stable
internal class FoldMemory(
    fold: Fold = Fold.Folded,
    track: NowPlaying? = null,
) {
    var fold: Fold by mutableStateOf(fold)
    var track: NowPlaying? by mutableStateOf(track)

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
 * The fold of the list shown beside [playing]: as it was set while [playing] plays on the track
 * it was set for ([TrackIdentity.playsOn]); for a new track, folded, or with [keep] (*Keep Sources
 * as you left them*) as it was left ([Fold.forNextTrack]). Worked out as it is read, so a new
 * track never shows the last one's fold for a frame.
 */
internal class SourcesFold(
    private val memory: FoldMemory,
    private val playing: NowPlaying,
    private val keep: Boolean,
) {
    val fold: Fold
        get() {
            val set = memory.fold
            val track = memory.track
            return when {
                track != null && TrackIdentity.playsOn(track, playing) -> set
                keep -> set.forNextTrack()
                else -> Fold.Folded
            }
        }

    /** Sets the fold for the track playing. */
    fun set(fold: Fold) {
        memory.fold = fold
        memory.track = playing
    }

    /** Unfolds far enough to show the line at [index] of the list as it shows. */
    fun reach(index: Int) = set(fold.reaching(index))
}

/** The fold of the *Sources* list beside [playing], kept as [FoldMemory] is. */
@Composable
internal fun rememberSourcesFold(
    playing: NowPlaying,
    keep: Boolean,
): SourcesFold {
    val memory = rememberSaveable(saver = FoldMemory.Saver) { FoldMemory() }
    return SourcesFold(memory, playing, keep)
}
