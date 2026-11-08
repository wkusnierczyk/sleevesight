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
 * recording is on is shown when [group] holds it not.
 */
data class TracklistKey(
    val group: String,
    val recording: String? = null,
    val others: List<String> = emptyList(),
)

/** Where an album's tracklist is (#984): being looked up, found, known not to exist, or not obtained. */
sealed interface TracklistState {
    /** MusicBrainz is being asked, or will be once lookups are allowed. */
    data object Looking : TracklistState

    data class Found(
        val tracklist: MbTracklist,
    ) : TracklistState

    /** MusicBrainz has no release of the album to show, or its release lists no track. */
    data object NotFound : TracklistState

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

    /** No tracklists: a preview's, or a test's that shows none. */
    object None : TracklistFeed {
        override val states: StateFlow<Map<TracklistKey, TracklistState>> = MutableStateFlow(emptyMap())

        override fun look(key: TracklistKey) = Unit

        override fun retry(key: TracklistKey) = Unit
    }
}

/**
 * The app's [TracklistFeed]: each tracklist asked through [lookup] (`AlbumTracklists`, over the
 * remembered MusicBrainz answers) in [scope], once [allowed] (the pipeline's `lookupsAllowed`: on
 * screen, or *Look up in the background* on; as the art fetch waits for it), and only while
 * [musicBrainzOn] (*Sources* has MusicBrainz on), read when the request would go: off, nothing is
 * asked and nothing is kept, so the album is asked again once it is back on. At most [CAPACITY]
 * albums are kept, the least recently asked dropped first. *Clear history* ([forget]) drops them all;
 * a lookup started before it keeps nothing ([epoch]).
 */
class AlbumTracklistFeed(
    private val lookup: suspend (TracklistKey) -> Lookup<MbTracklist>,
    private val scope: CoroutineScope,
    private val musicBrainzOn: () -> Boolean,
    private val allowed: Flow<Boolean> = flowOf(true),
    private val epoch: ClearEpoch = ClearEpoch(),
) : TracklistFeed {
    private val shown = MutableStateFlow<Map<TracklistKey, TracklistState>>(emptyMap())
    override val states: StateFlow<Map<TracklistKey, TracklistState>> = shown.asStateFlow()

    override fun look(key: TracklistKey) = ask(key, again = false)

    override fun retry(key: TracklistKey) = ask(key, again = true)

    /** Forgets every tracklist, for *Clear history*: the next flip asks again. */
    fun forget() {
        shown.value = emptyMap()
    }

    private fun ask(
        key: TracklistKey,
        again: Boolean,
    ) {
        var asking = false
        shown.update { states ->
            val now = states[key]
            asking = now == null || (again && now == TracklistState.Failed)
            if (asking) with(states - key, key, TracklistState.Looking) else states
        }
        if (!asking) return
        scope.launch(epoch.mark()) {
            allowed.first { it }
            val state =
                if (!musicBrainzOn()) {
                    null
                } else {
                    when (val answer = lookup(key)) {
                        is Lookup.Found -> TracklistState.Found(answer.value)
                        Lookup.NotFound -> TracklistState.NotFound
                        is Lookup.Failed -> TracklistState.Failed
                    }
                }
            epoch.ifCurrent(currentCoroutineContext()) {
                shown.update { states ->
                    // Only over its own ask: a *Clear history* since has dropped it.
                    if (states[key] != TracklistState.Looking) {
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
    private fun with(
        states: Map<TracklistKey, TracklistState>,
        key: TracklistKey,
        state: TracklistState,
    ): Map<TracklistKey, TracklistState> =
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
