package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbGroupFirst
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
 * The release groups' first releases *Dates* reads (#1099), by release group (`ReleaseDate.group`):
 * whether the album played is a later edition of its group, which the track's releases alone cannot
 * tell (the group's first release may hold no recording of the track, or be past the releases a
 * search lists). A group is looked up when *Dates* opens on it ([look]), not ahead, as a tracklist is
 * on a flip ([TracklistFeed]): every request waits its turn under MusicBrainz's one a second.
 */
interface GroupFeed {
    /** The groups found so far, by id, with their own title and first-release date. */
    val firsts: StateFlow<Map<String, MbGroupFirst>>

    /** Looks up [group], unless it is found, being looked up, or known not to exist. */
    fun look(group: String)

    /** No groups: a preview's, or a test's, where *Dates* says what the track's releases say. */
    object None : GroupFeed {
        override val firsts: StateFlow<Map<String, MbGroupFirst>> = MutableStateFlow(emptyMap())

        override fun look(group: String) = Unit
    }
}

/**
 * The app's [GroupFeed]: each group asked through [lookup] (the wiring's remembered MusicBrainz
 * answers) in [scope], once [allowed] (the pipeline's `lookupsAllowed`), and only while
 * [musicBrainzOn], read when the request would go, as [AlbumTracklistFeed] asks. A group that could
 * not be asked is not kept, so the next [look] asks again; one MusicBrainz has not is not asked again.
 * At most [CAPACITY] groups are kept, the least recently found dropped first. *Clear history*
 * ([forget]) drops them all; a lookup started before it keeps nothing ([epoch]).
 */
class ReleaseGroupFeed(
    private val lookup: suspend (String) -> Lookup<MbGroupFirst>,
    private val scope: CoroutineScope,
    private val musicBrainzOn: () -> Boolean,
    private val allowed: Flow<Boolean> = flowOf(true),
    private val epoch: ClearEpoch = ClearEpoch(),
) : GroupFeed {
    private val found = MutableStateFlow<Map<String, MbGroupFirst>>(emptyMap())
    override val firsts: StateFlow<Map<String, MbGroupFirst>> = found.asStateFlow()

    // The groups being looked up, and those MusicBrainz has not.
    private val asked = MutableStateFlow<Set<String>>(emptySet())

    override fun look(group: String) {
        var asking = false
        asked.update { now ->
            asking = group !in now && group !in found.value
            if (asking) now + group else now
        }
        if (!asking) return
        scope.launch(epoch.mark()) {
            allowed.first { it }
            val answer = if (musicBrainzOn()) lookup(group) else null
            epoch.ifCurrent(currentCoroutineContext()) {
                when (answer) {
                    is Lookup.Found -> {
                        found.update { now ->
                            val kept = (now - group + (group to answer.value)).entries.toList()
                            kept.takeLast(CAPACITY).associate { it.toPair() }
                        }
                        asked.update { it - group }
                    }
                    Lookup.NotFound -> Unit
                    is Lookup.Failed, null -> asked.update { it - group }
                }
            }
        }
    }

    /** Forgets every group, for *Clear history*: the next *Dates* asks again. */
    fun forget() {
        asked.value = emptySet()
        found.value = emptyMap()
    }

    companion object {
        /** Groups kept: a listening session's worth, each a few bytes. */
        const val CAPACITY: Int = 200
    }
}
