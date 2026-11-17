package app.sleevesight.app.pipeline

import android.content.Context
import androidx.core.content.edit
import app.sleevesight.core.resolution.MbTracklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The albums of one name the listener can choose among on the back of Home's cover (#1080).

/**
 * The albums of one name to show (#1080): their release [groups] (`ReleaseDate.group`), and the
 * playing [recording], whose release in each is shown when it is on one there; null when not known.
 */
data class AlbumsKey(
    val groups: List<String>,
    val recording: String?,
)

/** One of the albums of a name: its release [group], and the [tracklist] of its release shown. */
data class NamedAlbum(
    val group: String,
    val tracklist: MbTracklist,
)

/** Where the albums of one name are (#1080): being looked up, found, none with a tracklist, or failed. */
sealed interface AlbumsState {
    /** MusicBrainz is being asked, one album after another, or will be once lookups are allowed. */
    data object Looking : AlbumsState

    /** The [albums] with a tracklist, in the order asked. */
    data class Found(
        val albums: List<NamedAlbum>,
    ) : AlbumsState

    /** None of the albums has a release with tracks to show. */
    data object NotFound : AlbumsState

    /** MusicBrainz could not be asked for one of them; *Retry* asks again. */
    data object Failed : AlbumsState
}

/**
 * The albums the listener chose among several of one name (#1080), by the name's key (the album's name
 * and its artist, as Home keys them): the release group chosen. Kept on this phone until changed, and
 * forgotten by *Clear history*; never sent anywhere.
 */
interface AlbumChoiceStore {
    /** Every choice kept. */
    suspend fun load(): Map<String, String>

    /** Keeps [group] as the choice for [name], or forgets it when null. */
    fun keep(
        name: String,
        group: String?,
    )

    /** Forgets every choice, for *Clear history*. */
    fun clear()

    /** Choices held in memory only: a preview's, or a test's. */
    class InMemory(
        initial: Map<String, String> = emptyMap(),
    ) : AlbumChoiceStore {
        val kept = initial.toMutableMap()

        override suspend fun load(): Map<String, String> = kept.toMap()

        override fun keep(
            name: String,
            group: String?,
        ) {
            if (group == null) kept -= name else kept[name] = group
        }

        override fun clear() = kept.clear()
    }
}

/**
 * The listener's choices of album, kept in `SharedPreferences` (#1080): by the name's key, the release
 * group chosen. Like the settings, not backed up.
 */
class KeptAlbumChoices(
    context: Context,
) : AlbumChoiceStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun load(): Map<String, String> =
        withContext(Dispatchers.IO) {
            prefs.all.mapNotNull { (name, group) -> (group as? String)?.let { name to it } }.toMap()
        }

    override fun keep(
        name: String,
        group: String?,
    ) = prefs.edit { if (group == null) remove(name) else putString(name, group) }

    override fun clear() = prefs.edit { clear() }

    private companion object {
        const val PREFS = "album_choices"
    }
}
