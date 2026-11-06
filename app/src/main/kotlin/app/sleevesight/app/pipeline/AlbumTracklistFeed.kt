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
 * The albums' tracklists Home's cover shows on its back (#984), by release group (`ReleaseDate.group`):
 * [states], and the asks. A tracklist is looked up on the first flip of the cover ([look]), not ahead:
 * most covers are never turned, and every request waits its turn under MusicBrainz's one request a
 * second, behind the pipeline's own.
 */
interface TracklistFeed {
    /** Each album asked for, by group, and where its tracklist is. */
    val states: StateFlow<Map<String, TracklistState>>

    /** Looks up [group]'s tracklist, unless it is found, known not to exist, being looked up, or failed. */
    fun look(group: String)

    /** Looks up [group]'s tracklist again after it failed (*Retry*). */
    fun retry(group: String)

    /** No tracklists: a preview's, or a test's that shows none. */
    object None : TracklistFeed {
        override val states: StateFlow<Map<String, TracklistState>> = MutableStateFlow(emptyMap())

        override fun look(group: String) = Unit

        override fun retry(group: String) = Unit
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
    private val lookup: suspend (String) -> Lookup<MbTracklist>,
    private val scope: CoroutineScope,
    private val musicBrainzOn: () -> Boolean,
    private val allowed: Flow<Boolean> = flowOf(true),
    private val epoch: ClearEpoch = ClearEpoch(),
) : TracklistFeed {
    private val shown = MutableStateFlow<Map<String, TracklistState>>(emptyMap())
    override val states: StateFlow<Map<String, TracklistState>> = shown.asStateFlow()

    override fun look(group: String) = ask(group, again = false)

    override fun retry(group: String) = ask(group, again = true)

    /** Forgets every tracklist, for *Clear history*: the next flip asks again. */
    fun forget() {
        shown.value = emptyMap()
    }

    private fun ask(
        group: String,
        again: Boolean,
    ) {
        var asking = false
        shown.update { states ->
            val now = states[group]
            asking = now == null || (again && now == TracklistState.Failed)
            if (asking) with(states - group, group, TracklistState.Looking) else states
        }
        if (!asking) return
        scope.launch(epoch.mark()) {
            allowed.first { it }
            val state =
                if (!musicBrainzOn()) {
                    null
                } else {
                    when (val answer = lookup(group)) {
                        is Lookup.Found -> TracklistState.Found(answer.value)
                        Lookup.NotFound -> TracklistState.NotFound
                        is Lookup.Failed -> TracklistState.Failed
                    }
                }
            epoch.ifCurrent(currentCoroutineContext()) {
                shown.update { states ->
                    // Only over its own ask: a *Clear history* since has dropped it.
                    if (states[group] != TracklistState.Looking) {
                        states
                    } else if (state == null) {
                        states - group
                    } else {
                        with(states - group, group, state)
                    }
                }
            }
        }
    }

    // [states] with [group] last, as [state], the least recent dropped past [CAPACITY].
    private fun with(
        states: Map<String, TracklistState>,
        group: String,
        state: TracklistState,
    ): Map<String, TracklistState> =
        (states + (group to state))
            .entries
            .toList()
            .takeLast(CAPACITY)
            .associate { it.toPair() }

    companion object {
        /** Albums kept: a listening session's worth, each a few kilobytes. */
        const val CAPACITY: Int = 50
    }
}
