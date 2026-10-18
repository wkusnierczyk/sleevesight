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
 * - never while *Write insights* is off, or before the user's first action since the app started
 *   (in the app, a cold start: a return to it keeps the first action, until it expires after the
 *   track changes *Pause automatic writing after* names while the app is not on screen, #203);
 * - *On every track change*: every track;
 * - *When I skip*: tracks the user moved to, by a skip in the player or an action in Sleevesight;
 * - *Never*: none.
 */
object AutoWrite {
    fun writes(
        settings: Settings,
        cause: ChangeCause,
        userHasActed: Boolean,
    ): Boolean = userHasActed && cause in causes(settings)

    /**
     * The causes of a change that [settings] write on without a tap, once the user has acted: what
     * a run of the app reports when it asks, so the app's coordinator decides with the cause of the
     * latest report it holds (`PipelineCoordinator`).
     */
    fun causes(settings: Settings): Set<ChangeCause> =
        if (!settings.writeInsights) {
            emptySet()
        } else {
            when (settings.writeAutomatically) {
                WriteAutomatically.ON_EVERY_TRACK_CHANGE -> ChangeCause.entries.toSet()
                WriteAutomatically.WHEN_I_SKIP -> setOf(ChangeCause.USER_ACTION, ChangeCause.SKIP_IN_PLAYER)
                WriteAutomatically.NEVER -> emptySet()
            }
        }
}
