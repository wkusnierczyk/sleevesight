package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

// What the cover's back keeps while it is turned over (#984), held where Home keeps its other
// memories, above the art's colours and above where the cover is drawn (#1097): the art's colours
// arriving, and the phone turning across 600 dp (the cover moving between Home's column and a side
// of its own), keep it turned over, on the disc shown, its list where it was scrolled.

/**
 * The cover's back as it was left: [backOf], the album whose back shows (null for the cover), and
 * [tracklist], where its tracklist was left.
 */
@Stable
internal class CoverMemory(
    val backOf: MutableState<String?>,
    val tracklist: TracklistMemory,
)

/**
 * Where a disc's list was left: its first row shown, how far into that row, and the playing row it
 * last brought into view.
 */
internal class ListPlace(
    val index: Int,
    val offset: Int,
    val followed: Int?,
)

/**
 * Where the tracklist on the back of [album]'s cover was left: the disc's page shown ([page]), the
 * playing song's disc last followed ([followedDisc]), and each disc's list ([place]). Plain values,
 * read only when the back is drawn, to open it where it was; another album, or the cover turned back
 * to its front ([clear]), starts afresh, on the playing song.
 */
internal class TracklistMemory(
    album: String? = null,
    var page: Int? = null,
    var followedDisc: Int? = null,
    places: Map<Int, ListPlace> = emptyMap(),
) {
    var album: String? = album
        private set
    private val places = places.toMutableMap()

    /** This memory for [album]: emptied first when it held another's. */
    fun of(album: String?): TracklistMemory {
        if (album != this.album) {
            clear()
            this.album = album
        }
        return this
    }

    /** Forgets where the tracklist was left. */
    fun clear() {
        page = null
        followedDisc = null
        places.clear()
    }

    /** Where [disc]'s list was left; null when it has not been shown. */
    fun place(disc: Int): ListPlace? = places[disc]

    /** Keeps where [disc]'s list is: its first row shown, how far into it, and the playing row it [followed]. */
    fun keep(
        disc: Int,
        index: Int,
        offset: Int,
        followed: Int?,
    ) {
        places[disc] = ListPlace(index, offset, followed)
    }

    companion object {
        /** Saved as plain values: the album, the page and the disc followed, then four per disc's list. */
        val Saver =
            listSaver<TracklistMemory, Any?>(
                save = { memory ->
                    listOf(memory.album, memory.page, memory.followedDisc) +
                        memory.places.flatMap { (disc, place) ->
                            listOf(disc, place.index, place.offset, place.followed)
                        }
                },
                restore = { saved ->
                    val places =
                        saved.drop(PLACES_FROM).chunked(PLACE_SIZE).associate { place ->
                            place[0] as Int to
                                ListPlace(place[1] as Int, place[2] as Int, place[PLACE_SIZE - 1] as Int?)
                        }
                    TracklistMemory(saved[0] as String?, saved[1] as Int?, saved[2] as Int?, places)
                },
            )

        private const val PLACES_FROM = 3
        private const val PLACE_SIZE = 4
    }
}

/** The cover's back as it was left, saved with the screen; remembered where Home keeps its memories (#1097). */
@Composable
internal fun rememberCoverMemory(): CoverMemory {
    val backOf = rememberSaveable { mutableStateOf<String?>(null) }
    val tracklist = rememberSaveable(saver = TracklistMemory.Saver) { TracklistMemory() }
    return remember(backOf, tracklist) { CoverMemory(backOf, tracklist) }
}

/** The memory of [turn]'s cover: Home's ([CoverTurn.memory]), else the cover's own. */
@Composable
internal fun coverMemoryOf(turn: CoverTurn): CoverMemory {
    val own = rememberCoverMemory()
    return turn.memory ?: own
}
