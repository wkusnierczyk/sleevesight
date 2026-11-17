package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbTracklist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * An album to show the tracklist of: its release group ([group], as `ReleaseDate.group` has it), and the
 * playing [recording], whose release in it is shown (`AlbumTracklists.of`); null when not known. With
 * the [others] the album's name names besides, when it names several (#1062), of which the one the
 * recording is on is shown when [group] holds it not. With no [group] when none of several is picked
 * (#1078): [others] are those groups, and the one of them the recording is on is shown
 * (`AlbumTracklists.ofOneOf`), or none when that is not clear.
 */
data class TracklistKey(
    val group: String?,
    val recording: String? = null,
    val others: List<String> = emptyList(),
)

/**
 * Where an album's tracklist is (#984): being looked up, found, known not to exist, not clear among
 * several albums of the name, or not obtained.
 */
sealed interface TracklistState {
    /** MusicBrainz is being asked, or will be once lookups are allowed. */
    data object Looking : TracklistState

    data class Found(
        val tracklist: MbTracklist,
    ) : TracklistState

    /** MusicBrainz has no release of the album to show, or its release lists no track. */
    data object NotFound : TracklistState

    /**
     * The album's name names several groups, none picked, and the playing recording tells no one of
     * them (#1078): it is on none of them, on several, or is not known.
     */
    data object Unclear : TracklistState

    /** MusicBrainz could not be asked; [TracklistFeed.retry] asks again. */
    data object Failed : TracklistState
}

/**
 * The albums' tracklists Home's cover shows on its back (#984), by [TracklistKey], the album's release
 * group (`ReleaseDate.group`) with the playing recording, whose release is shown:
 * [states], and the asks. A tracklist is looked up on the first flip of the cover ([look]), not ahead:
 * most covers are never turned, and every request waits its turn under MusicBrainz's one request a
 * second, behind the pipeline's own.
 */
interface TracklistFeed {
    /** Each album asked for, by its key, and where its tracklist is. */
    val states: StateFlow<Map<TracklistKey, TracklistState>>

    /** Looks up [key]'s tracklist, unless it is found, known not to exist, being looked up, or failed. */
    fun look(key: TracklistKey)

    /** Looks up [key]'s tracklist again after it failed (*Retry*). */
    fun retry(key: TracklistKey)

    /** The albums of one name asked for (#1080), by their key, and where their tracklists are. */
    val albums: StateFlow<Map<AlbumsKey, AlbumsState>> get() = NO_ALBUMS

    /** Looks up the tracklist of each album of [key] (#1080), unless they are found, looked up or failed. */
    fun lookAlbums(key: AlbumsKey) = Unit

    /** Looks up the albums of [key] again after it failed (*Retry*). */
    fun retryAlbums(key: AlbumsKey) = Unit

    /**
     * The album the listener chose among several of one name (#1080), by the name's key: its release
     * group.
     */
    val choices: StateFlow<Map<String, String>> get() = NO_CHOICES

    /** Keeps [group] as the album chosen for the name [name], or, null, lets Sleevesight decide again. */
    fun choose(
        name: String,
        group: String?,
    ) = Unit

    /** No tracklists: a preview's, or a test's that shows none. */
    object None : TracklistFeed {
        override val states: StateFlow<Map<TracklistKey, TracklistState>> = MutableStateFlow(emptyMap())

        override fun look(key: TracklistKey) = Unit

        override fun retry(key: TracklistKey) = Unit
    }
}

private val NO_ALBUMS: StateFlow<Map<AlbumsKey, AlbumsState>> = MutableStateFlow(emptyMap())
private val NO_CHOICES: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

/**
 * The app's [TracklistFeed]: each tracklist asked through [lookup] (`AlbumTracklists`, over the
 * remembered MusicBrainz answers; null when it is not clear which album of several, #1078) in
 * [scope], once [allowed] (the pipeline's `lookupsAllowed`: on screen, or *Look up in the background*
 * on; as the art fetch waits for it), and only while [musicBrainzOn] (*Sources* has MusicBrainz on),
 * read when the request would go: off, nothing is asked and nothing is kept, so the album is asked
 * again once it is back on. At most [CAPACITY] albums are kept, the least recently asked dropped
 * first. *Clear history* ([forget]) drops them all; a lookup started before it keeps nothing ([epoch]).
 *
 * The albums of one name (#1080) are asked the same way through [albumsLookup], on the listener's tap
 * on *See the {n} albums* or *Change*, never ahead; and the album the listener chose among them is kept
 * in [choiceStore], read once at the start, until changed or *Clear history* forgets it.
 */
@Suppress("LongParameterList") // The two lookups, the choices kept, and when each may ask.
class AlbumTracklistFeed(
    private val lookup: suspend (TracklistKey) -> Lookup<MbTracklist>?,
    private val scope: CoroutineScope,
    private val musicBrainzOn: () -> Boolean,
    private val allowed: Flow<Boolean> = flowOf(true),
    private val epoch: ClearEpoch = ClearEpoch(),
    private val albumsLookup: suspend (AlbumsKey) -> Lookup<List<NamedAlbum>> = { Lookup.NotFound },
    private val choiceStore: AlbumChoiceStore = AlbumChoiceStore.InMemory(),
) : TracklistFeed {
    private val shown = MutableStateFlow<Map<TracklistKey, TracklistState>>(emptyMap())
    override val states: StateFlow<Map<TracklistKey, TracklistState>> = shown.asStateFlow()

    private val named = MutableStateFlow<Map<AlbumsKey, AlbumsState>>(emptyMap())
    override val albums: StateFlow<Map<AlbumsKey, AlbumsState>> = named.asStateFlow()

    private val chosen = MutableStateFlow<Map<String, String>>(emptyMap())
    override val choices: StateFlow<Map<String, String>> = chosen.asStateFlow()

    init {
        scope.launch(epoch.mark()) {
            val kept = choiceStore.load()
            // A choice made meanwhile stands over the one kept.
            epoch.ifCurrent(currentCoroutineContext()) { chosen.update { kept + it } }
        }
    }

    override fun look(key: TracklistKey) = askTracklist(key, again = false)

    override fun retry(key: TracklistKey) = askTracklist(key, again = true)

    override fun lookAlbums(key: AlbumsKey) = askAlbums(key, again = false)

    override fun retryAlbums(key: AlbumsKey) = askAlbums(key, again = true)

    override fun choose(
        name: String,
        group: String?,
    ) {
        chosen.update { if (group == null) it - name else it + (name to group) }
        choiceStore.keep(name, group)
    }

    /**
     * Forgets every tracklist, the albums of each name, and every album chosen, for *Clear history*:
     * the next flip asks again, and Sleevesight decides among albums of one name again.
     */
    fun forget() {
        shown.value = emptyMap()
        named.value = emptyMap()
        chosen.value = emptyMap()
        choiceStore.clear()
    }

    private fun askTracklist(
        key: TracklistKey,
        again: Boolean,
    ) = ask(shown, key, again, TracklistState.Looking, TracklistState.Failed) {
        when (val answer = lookup(key)) {
            is Lookup.Found -> TracklistState.Found(answer.value)
            Lookup.NotFound -> TracklistState.NotFound
            is Lookup.Failed -> TracklistState.Failed
            null -> TracklistState.Unclear
        }
    }

    private fun askAlbums(
        key: AlbumsKey,
        again: Boolean,
    ) = ask(named, key, again, AlbumsState.Looking, AlbumsState.Failed) {
        when (val answer = albumsLookup(key)) {
            is Lookup.Found -> AlbumsState.Found(answer.value)
            Lookup.NotFound -> AlbumsState.NotFound
            is Lookup.Failed -> AlbumsState.Failed
        }
    }

    // Asks [answer] for [key] into [flow], marked [looking] meanwhile: when [key] has no state, or,
    // [again], when it [failed]. Once lookups are allowed, and only while MusicBrainz is on, read as
    // the request would go: off, nothing is asked and nothing kept.
    private fun <K, S> ask(
        flow: MutableStateFlow<Map<K, S>>,
        key: K,
        again: Boolean,
        looking: S,
        failed: S,
        answer: suspend () -> S,
    ) {
        var asking = false
        flow.update { states ->
            val now = states[key]
            asking = now == null || (again && now == failed)
            if (asking) with(states - key, key, looking) else states
        }
        if (!asking) return
        scope.launch(epoch.mark()) {
            allowed.first { it }
            val state = if (musicBrainzOn()) answer() else null
            epoch.ifCurrent(currentCoroutineContext()) {
                flow.update { states ->
                    // Only over its own ask: a *Clear history* since has dropped it.
                    if (states[key] != looking) {
                        states
                    } else if (state == null) {
                        states - key
                    } else {
                        with(states - key, key, state)
                    }
                }
            }
        }
    }

    // [states] with [key] last, as [state], the least recent dropped past [CAPACITY].
    private fun <K, S> with(
        states: Map<K, S>,
        key: K,
        state: S,
    ): Map<K, S> =
        (states + (key to state))
            .entries
            .toList()
            .takeLast(CAPACITY)
            .associate { it.toPair() }

    companion object {
        /** Albums kept: a listening session's worth, each a few kilobytes. */
        const val CAPACITY: Int = 50
    }
}
