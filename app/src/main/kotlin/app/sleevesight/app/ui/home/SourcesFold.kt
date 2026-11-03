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
import app.sleevesight.core.settings.NewTrackFold

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
         * it is never far. Not a setting: *Show all* is a tap away for whoever wants every line.
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
         * Saved as plain values: the fold, and of its track what [TrackIdentity.playsOn] reads by
         * its fields (the player, the media id, the title, the artists and the album).
         */
        val Saver: Saver<FoldMemory, Any> =
            listSaver(
                save = { memory ->
                    val fold = memory.fold
                    listOf(
                        when (fold) {
                            Fold.Folded -> FOLDED
                            is Fold.Pages -> fold.count
                            Fold.All -> ALL
                        },
                    ) + savedTrack(memory.track)
                },
                restore = { saved ->
                    val fold =
                        when (val count = saved[0] as Int) {
                            FOLDED -> Fold.Folded
                            ALL -> Fold.All
                            else -> Fold.Pages(count)
                        }
                    FoldMemory(fold, restoredTrack(saved.drop(1)))
                },
            )

        private const val FOLDED = 0
        private const val ALL = -1
    }
}

/**
 * Of [track], what [TrackIdentity.playsOn] reads by its fields (the player, the media id, the
 * title, the artists and the album), as plain values a saver keeps; [restoredTrack] reads them
 * back. Not its play ([NowPlaying.play]): the session layer counts plays afresh in each process, so
 * a number saved before the process died could name another play; restored untagged, the track is
 * matched by its fields.
 */
internal fun savedTrack(track: NowPlaying?): List<Any?> =
    listOf(track?.player, track?.mediaId, track?.title, track?.artists, track?.album)

/**
 * The track [savedTrack] kept in [saved], or null when it kept none. A state saved by an earlier
 * version (`[player, media id, queue item, title, artists]`, its third value a queue item, not a
 * title) is read too, with no album.
 */
internal fun restoredTrack(saved: List<Any?>): NowPlaying? =
    (saved[0] as String?)?.let { player ->
        val current = saved[2] is String
        NowPlaying(
            title = (if (current) saved[2] else saved[3]) as String,
            artists = (if (current) saved[3] else saved[4]) as String,
            player = player,
            album = if (current) saved[4] as String? else null,
            mediaId = saved[1] as String?,
        )
    }

/**
 * The fold of the list shown beside [playing]: as it is kept while [playing] plays on the track
 * last shown ([TrackIdentity.playsOn]); for a new track, as [onNewTrack] says (*Sources on a new
 * track*, #522): [start] (folded on Home) for *Folded*, its first screenful for *Unfolded*, and as
 * it was left ([Fold.forNextTrack]) for *As you left them*; [onNewTrack] is null until the settings
 * are read, and a new track shows [start] until then. Worked out as it is read, so a new
 * track never shows the last one's fold for a frame; [follow] then keeps it as the new track's, so
 * a track played again after others starts afresh too. On a page of its own (#447) the list starts
 * at its first screenful, and is never kept.
 */
internal class SourcesFold(
    private val memory: FoldMemory,
    private val playing: NowPlaying,
    private val onNewTrack: NewTrackFold?,
    private val start: Fold = Fold.Folded,
) {
    val fold: Fold
        get() {
            val set = memory.fold
            val track = memory.track
            return when {
                track != null && TrackIdentity.playsOn(track, playing) -> set
                onNewTrack == NewTrackFold.AS_LEFT -> set.forNextTrack()
                onNewTrack == NewTrackFold.UNFOLDED -> Fold.Pages(1)
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
     * Unfolds far enough to show the line at [index], not by the listener's own action: a citation
     * followed as its line moves (#522), which changes nothing that is announced.
     */
    fun follow(index: Int) {
        memory.fold = fold.reaching(index)
        memory.track = playing
    }

    /**
     * On a new track, keeps the fold it shows as its own, untouched; not until the settings are
     * read ([onNewTrack] null), so a fold kept for the next track is not lost to the folded default.
     */
    fun follow() {
        val track = memory.track
        if (onNewTrack == null || (track != null && TrackIdentity.playsOn(track, playing))) return
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
 * The fold of the *Sources* list beside [playing], from [memory], a new track's as [onNewTrack]
 * says; a new track's is kept as its own.
 */
@Composable
internal fun sourcesFold(
    memory: FoldMemory,
    playing: NowPlaying,
    onNewTrack: NewTrackFold?,
    start: Fold = Fold.Folded,
): SourcesFold {
    val fold = SourcesFold(memory, playing, onNewTrack, start)
    SideEffect { fold.follow() }
    return fold
}
