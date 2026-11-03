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
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.settings.NewTrackFold

// The song's, the artist's and the album's insights under *Key insights* (#522): a row each, under
// the key insight and before *Sources*, unfolding in place under *Expandable boxes* or opening a page
// of its own under *Separate pages*.

/** An item's row: the [item], and the [name] it goes by on the track playing. */
internal class ItemRow(
    val item: InsightItem,
    val name: String,
)

/**
 * The rows of [header], the track shown: the song by its title, the artist by the primary artist
 * ([primary], as the normalizer reads the artists), the album by the player's album name; no row for
 * one with no name (an album the player does not name, a blank title).
 */
internal fun itemRows(
    header: NowPlaying,
    primary: String,
): List<ItemRow> =
    listOfNotNull(
        header.title.takeIf { it.isNotBlank() }?.let { ItemRow(InsightItem.SONG, it) },
        primary.takeIf { it.isNotBlank() }?.let { ItemRow(InsightItem.ARTIST, it) },
        header.album
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { ItemRow(InsightItem.ALBUM, it) },
    )

/** An item's kind, as its row and its page's title name it. */
@Composable
internal fun itemKind(item: InsightItem): String =
    stringResource(
        when (item) {
            InsightItem.SONG -> R.string.home_item_song
            InsightItem.ARTIST -> R.string.home_item_artist
            InsightItem.ALBUM -> R.string.home_item_album
        },
    )

/**
 * Which rows are unfolded, kept across recompositions and a recreated screen, as *Sources*' fold is
 * ([FoldMemory]): the items [open] on the track last shown, [track], and those a tap opened on it
 * ([tapped], not saved), which write at once under an automatic mode whatever held the track's own
 * writing back.
 */
@Stable
internal class ItemFoldMemory(
    open: Set<InsightItem> = emptySet(),
    track: NowPlaying? = null,
) {
    var open: Set<InsightItem> by mutableStateOf(open)
    var track: NowPlaying? by mutableStateOf(track)
    var tapped: Set<InsightItem> by mutableStateOf(emptySet())

    companion object {
        /** Saved as plain values: the items open, by name, and the track as [savedTrack] keeps it. */
        val Saver: Saver<ItemFoldMemory, Any> =
            listSaver(
                save = { memory -> listOf<Any?>(memory.open.joinToString(",") { it.name }) + savedTrack(memory.track) },
                restore = { saved ->
                    val open =
                        (saved[0] as String)
                            .split(',')
                            .mapNotNull { name -> InsightItem.entries.firstOrNull { it.name == name } }
                            .toSet()
                    ItemFoldMemory(open, restoredTrack(saved.drop(1)))
                },
            )
    }
}

/** The rows' memory, remembered above whatever comes and goes with the track, as *Sources*' is. */
@Composable
internal fun rememberItemFoldMemory(): ItemFoldMemory =
    rememberSaveable(saver = ItemFoldMemory.Saver) {
        ItemFoldMemory()
    }

/**
 * Which rows are unfolded beside [playing]: as kept while it plays on the track last shown; for a
 * new track, as [start] says (*Song, Artist and Album on a new track*): none, all, or as they were
 * left; none while the settings are not read ([start] null). Worked out as it is read, so a new
 * track never shows the last one's for a frame; [follow] then keeps them as the new track's.
 */
internal class ItemFolds(
    private val memory: ItemFoldMemory,
    private val playing: NowPlaying,
    private val start: NewTrackFold?,
) {
    private val same: Boolean get() = memory.track?.let { TrackIdentity.playsOn(it, playing) } == true

    /** The items unfolded. */
    val open: Set<InsightItem>
        get() =
            when {
                same -> memory.open
                start == NewTrackFold.AS_LEFT -> memory.open
                start == NewTrackFold.UNFOLDED -> InsightItem.entries.toSet()
                else -> emptySet()
            }

    /** Whether [item] was opened by a tap on the track playing. */
    fun tapped(item: InsightItem): Boolean = same && item in memory.tapped

    /** A tap on [item]'s row: unfolded, or folded. */
    fun toggle(item: InsightItem) {
        val now = open
        val opening = item !in now
        val tapped = if (same) memory.tapped else emptySet()
        memory.open = if (opening) now + item else now - item
        memory.tapped = if (opening) tapped + item else tapped - item
        memory.track = playing
    }

    /** On a new track, keeps the rows it shows as its own; not until the settings are read. */
    fun follow() {
        if (start == null || same) return
        memory.open = open
        memory.tapped = emptySet()
        memory.track = playing
    }
}

/**
 * The rows' folds beside [playing], from [memory], a new track's as [start] says; a new track's are
 * kept as its own.
 */
@Composable
internal fun itemFolds(
    memory: ItemFoldMemory,
    playing: NowPlaying,
    start: NewTrackFold?,
): ItemFolds {
    val folds = ItemFolds(memory, playing, start)
    SideEffect { folds.follow() }
    return folds
}
