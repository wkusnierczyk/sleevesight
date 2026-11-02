package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.TrackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The play of the track the pipeline shows (#851): the track, by its identity ([playIdentity]), and
 * when its play [began], published together, so an AI answer's panel can tell whether the answer
 * was written before it, kept from before.
 */
data class Play(
    val track: Any,
    val began: Instant,
) {
    /** When [track]'s play began, if this is its play; else null. */
    fun beganFor(track: TrackState): Instant? = began.takeIf { this.track == playIdentity(track) }
}

/**
 * Follows the plays of the track the pipeline shows (#851), by [clock], in whole milliseconds as the
 * caches keep times: a track is played anew when another one came between, by its identity as the
 * sources name it and its length (as `AutoSearches` tells plays apart). [current] publishes each
 * play, which the answers' feeds observe, so what they say is kept is worked out again when the
 * play changes.
 */
class Plays(
    private val clock: Clock = Clock.System,
) {
    private val play = MutableStateFlow<Play?>(null)

    /** The play now, null before the first track. */
    val current: StateFlow<Play?> = play

    /** [track] as the pipeline publishes it: a play begins when it is another track. Null changes nothing. */
    fun follow(track: TrackState?) {
        if (track == null) return
        val identity = playIdentity(track)
        if (play.value?.track != identity) {
            play.value = Play(identity, Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()))
        }
    }

    /** Follows [tracks], the pipeline's track, until cancelled. */
    suspend fun run(tracks: Flow<TrackState?>) = tracks.collect(::follow)
}

/** [track]'s identity for its plays: as the sources name it, and its length. */
internal fun playIdentity(track: TrackState): Any = track.normalized to track.nowPlaying.duration
