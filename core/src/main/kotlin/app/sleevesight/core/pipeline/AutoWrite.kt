package app.sleevesight.core.pipeline

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

/**
 * Why the track changed, as the session layer tells it (`design/architecture.md`, *Writing insights
 * automatically*). Skip detection itself (the time left, Spotify's late cluster, the crossfade)
 * belongs to the session layer in `:app`; the pipeline only acts on its verdict.
 */
enum class ChangeCause {
    /** An action in Sleevesight: previous, next, play, selecting a track. Always user-driven. */
    USER_ACTION,

    /** The session layer judged it a skip in the player. */
    SKIP_IN_PLAYER,

    /** The track ended on its own, or the session layer could not tell, which it treats the same. */
    NATURAL_END,

    /**
     * Not a change: the track shown, run again by a pipeline built anew (in the app, after a key is
     * saved, say). D5 is about track changes, so it is never written without a tap.
     */
    REPLAY,
}

/** A track change: what the player reports, and why it changed. */
data class TrackChange(
    val nowPlaying: NowPlaying,
    val cause: ChangeCause = ChangeCause.NATURAL_END,
)

/**
 * Whether an insight is written without a tap (D5; `design/architecture.md`, *Writing insights
 * automatically*):
 *
 * - never while *Write insights* is off, or before the user's first action after the app opens;
 * - never for a [ChangeCause.REPLAY], which is not a track change;
 * - *On every track change*: every track;
 * - *When I skip*: tracks the user moved to, by a skip in the player or an action in Sleevesight;
 * - *Never*: none.
 */
object AutoWrite {
    fun writes(
        settings: Settings,
        cause: ChangeCause,
        userHasActed: Boolean,
    ): Boolean =
        settings.writeInsights &&
            userHasActed &&
            cause != ChangeCause.REPLAY &&
            when (settings.writeAutomatically) {
                WriteAutomatically.ON_EVERY_TRACK_CHANGE -> true
                WriteAutomatically.WHEN_I_SKIP -> cause in MOVED_TO
                WriteAutomatically.NEVER -> false
            }

    // The changes the user made: an action in Sleevesight, or a skip in the player.
    private val MOVED_TO = setOf(ChangeCause.USER_ACTION, ChangeCause.SKIP_IN_PLAYER)
}
