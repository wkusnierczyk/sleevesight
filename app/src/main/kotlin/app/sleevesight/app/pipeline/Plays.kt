package app.sleevesight.app.pipeline

import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.TrackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A play of a track (#851): its [track] as last reported, and when the play [began], published
 * together, so an AI answer's panel can tell whether the answer was written before it, kept from
 * before.
 */
data class Play(
    val track: NowPlaying,
    val began: Instant,
) {
    /** When [track]'s play began, if it is this play's track playing on ([TrackIdentity.playsOn]); else null. */
    fun beganFor(track: TrackState): Instant? = began.takeIf { TrackIdentity.playsOn(this.track, track.nowPlaying) }
}

/**
 * Follows the plays of the track the session layer shows (#851), by [clock], in whole milliseconds
 * as the caches keep times, from its reports as they come (`SessionRepository.nowPlaying`), not the
 * pipeline's debounced runs: a track skipped within the debounce still ends the play before it. A
 * report that plays on the one before ([TrackIdentity.playsOn], the session history's and the
 * insight's rule: a new length, album or artists of the same track) continues the play; any other
 * begins one; no track changes nothing. [current] publishes each play, which the answers' feeds
 * observe, so what they say is kept is worked out again when the play changes.
 */
class Plays(
    private val clock: Clock = Clock.System,
) {
    private val play = MutableStateFlow<Play?>(null)

    /** The play now, null before the first track. */
    val current: StateFlow<Play?> = play

    /** The session layer's [report] of the track shown. */
    fun follow(report: NowPlaying?) {
        if (report == null) return
        val now = play.value
        play.value =
            if (now != null && TrackIdentity.playsOn(now.track, report)) {
                now.copy(track = report)
            } else {
                Play(report, Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()))
            }
    }

    /** Follows [reports], the session layer's track shown, until cancelled. */
    suspend fun run(reports: Flow<NowPlaying?>) = reports.collect(::follow)
}
