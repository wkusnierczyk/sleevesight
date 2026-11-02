package app.sleevesight.app.pipeline

import app.sleevesight.core.pipeline.TrackState
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * When the play of the track the pipeline shows began (#851), by [clock], in whole milliseconds as
 * the caches keep times: a track is played anew when another one came between, by its identity as
 * the sources name it and its length (as `AutoSearches` tells plays apart). An AI answer written
 * before it is one kept from before, which its panel says.
 */
class Plays(
    private val clock: Clock = Clock.System,
) {
    // The track playing, by its identity, and when its play began.
    @Volatile
    private var current: Pair<Any, Instant>? = null

    /** [track] as the pipeline publishes it: a play begins when it is another track. Null changes nothing. */
    fun follow(track: TrackState?) {
        if (track == null) return
        val identity = identityOf(track)
        if (current?.first != identity) {
            current = identity to Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        }
    }

    /** Follows [tracks], the pipeline's track, until cancelled. */
    suspend fun run(tracks: Flow<TrackState?>) = tracks.collect(::follow)

    /** When [track]'s play began, if it is the track playing; else null. */
    fun began(track: TrackState): Instant? = current?.takeIf { it.first == identityOf(track) }?.second

    private fun identityOf(track: TrackState): Any = track.normalized to track.nowPlaying.duration
}
