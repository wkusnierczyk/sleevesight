package app.sleevesight.app.speech

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.sleevesight.app.session.ControllerSession
import app.sleevesight.app.session.SessionMapping
import app.sleevesight.app.session.SleevesightListenerService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A followed player's session playing on another device (#803), as Spotify Connect's does on a
 * Sonos speaker: the phone plays nothing, so audio focus and ducking do not reach the music, and a
 * reading acts on it through the session instead ([RemoteHold]). A seam over `MediaController`, so
 * the hold is tested with plain values. Called on the main thread.
 */
interface RemoteMusic {
    /**
     * Whether it still plays on another device: false once playback moved to the phone or the
     * session ended.
     */
    val remote: Boolean

    /** Whether it plays now, as the session layer reads a remote session's state ([SessionMapping.effectiveState]). */
    val playing: Boolean

    /** How the phone can set its volume: `VolumeProvider`'s `VOLUME_CONTROL_FIXED`, `_RELATIVE` or `_ABSOLUTE`. */
    val volumeControl: Int

    /** Its volume now, and the most it can be; 0 for a volume the phone cannot set. */
    val volume: Int
    val maxVolume: Int

    fun pause()

    fun play()

    /** Sets the volume, with an absolute control. */
    fun setVolumeTo(volume: Int)

    /** Turns the volume a step up or down (`AudioManager.ADJUST_RAISE`, `ADJUST_LOWER`), with a relative control. */
    fun adjustVolume(direction: Int)

    /**
     * Calls [changed] with each report of its playback state or volume, and when it ends, as the
     * report gave it (not as the session is when it is delivered, which may be a later state),
     * until [unwatch].
     */
    fun watch(changed: (RemoteReport) -> Unit)

    fun unwatch()
}

/**
 * One report of a [RemoteMusic]'s session, as its callback gave it: whether it plays ([playing],
 * from a playback state), whether it plays on another device and at what [volume] (from its audio
 * info), or that it ended ([remote] false); null for what the report does not say.
 */
class RemoteReport(
    val playing: Boolean? = null,
    val remote: Boolean? = null,
    val volume: Int? = null,
)

/**
 * What readings do to [music], a remote session that plays as the first starts, as [lower]
 * (*Reduce volume*) or not (*Pause playback*) says, undone by [end] once the last ends; readings
 * that replace one another go on under one hold, its session and its way fixed when it starts:
 * - paused through its transport controls with *Pause playback*, and with *Reduce volume* where its
 *   volume is fixed (told to [pausedInstead] while it stays paused); played again at the end
 *   unless the user took it over in between: a report said it played after one had said the pause
 *   landed (the user paused it after, or plays it now), or it no longer plays on another device;
 * - lowered with an absolute volume, to [LOWERED_SHARE] of it (at least 1), and set back at the
 *   end unless the user changed it, or it no longer plays remotely. The first volume reported away
 *   from the volume before (or near the one asked) is the speaker's answer, whatever it is, since
 *   a speaker may round it; once it is in, a report more than a step from it ([tolerance]: 1, or
 *   2% of the most the volume can be) is the user's change;
 * - lowered with a relative volume by as many steps as that takes, followed as an absolute volume
 *   is once a report near the volume aimed at is in, and at the end raised by as many steps as
 *   bring it back to the volume before, no more than it was lowered; where it reports no volume,
 *   lowered by [RELATIVE_STEPS] and raised by as many, blind.
 *
 * A volume hold only ever sets the volume: a pause or play the user makes meanwhile is theirs, and
 * the volume set back at the end does not play the music again.
 *
 * The session is watched before anything is asked of it, so no report of what is asked is missed,
 * and checked once watched: music that no longer plays then, or no longer remotely, is left alone.
 * Reports are read one by one, each as it was given. A process killed while it holds the music
 * gives nothing back: the speaker stays paused or lowered.
 */
class RemoteHold private constructor(
    private val music: RemoteMusic,
    private val way: Way,
    private val pausedInstead: (Boolean) -> Unit,
) {
    private enum class Way { PAUSED, ABSOLUTE, RELATIVE, NONE }

    // The volume before, the one aimed at, and the one the speaker answered with; the steps taken
    // down; whether volume reports are followed, and how far one may be from another and count as it.
    private var before = 0
    private var target = 0
    private var landedAt = 0
    private var steps = 0
    private var counted = false
    private var tolerance = 1

    // Whether the pause or the lowered volume has been reported, and whether the user took over after.
    private var landed = false
    private var takenOver = false

    // Whether the music was paused though *Reduce volume* is chosen, and is still.
    private var instead = false

    private fun start(lower: Boolean) {
        if (way == Way.NONE) return
        music.watch(::changed)
        // Paused, or moved to the phone, between being found and being watched: nothing to hold.
        if (!music.remote || !music.playing) {
            music.unwatch()
            takenOver = true
            return
        }
        when (way) {
            Way.PAUSED -> {
                instead = lower
                pausedInstead(instead)
                music.pause()
            }
            Way.ABSOLUTE -> {
                countFrom(music.volume)
                music.setVolumeTo(target)
            }
            Way.RELATIVE -> {
                if (music.maxVolume > 0) {
                    countFrom(music.volume)
                    steps = before - target
                } else {
                    steps = RELATIVE_STEPS
                }
                repeat(steps) { music.adjustVolume(AudioManager.ADJUST_LOWER) }
            }
            Way.NONE -> Unit
        }
    }

    private fun changed(report: RemoteReport) {
        if (report.remote == false) takeOver()
        if (takenOver) return
        when (way) {
            Way.PAUSED -> report.playing?.let { playing -> follow(asked = !playing) }
            Way.ABSOLUTE, Way.RELATIVE -> report.volume?.takeIf { counted }?.let(::followVolume)
            Way.NONE -> Unit
        }
    }

    // Volume reports followed from [volume], the volume before.
    private fun countFrom(volume: Int) {
        before = volume
        target = lowerOf(volume)
        tolerance = maxOf(1, (music.maxVolume * TOLERANCE_SHARE).roundToInt())
        counted = true
    }

    // The speaker's answer to what was asked, then any change of it the user makes. An absolute
    // volume answers with the first report away from the volume before; a relative one, stepped
    // down a report at a time, once it nears the volume aimed at.
    private fun followVolume(volume: Int) {
        if (!landed) {
            val answer = near(volume, target) || (way == Way.ABSOLUTE && !near(volume, before))
            if (answer) {
                landed = true
                landedAt = volume
            }
        } else if (!near(volume, landedAt)) {
            takeOver()
        }
    }

    private fun near(
        volume: Int,
        to: Int,
    ): Boolean = abs(volume - to) <= tolerance

    // A report that the music is as asked ([asked]) lands what was asked; once landed, one that it
    // is not is the user's change.
    private fun follow(asked: Boolean) {
        if (!landed) {
            landed = asked
        } else if (!asked) {
            takeOver()
        }
    }

    // The user's now: nothing is given back, and the music is no longer said to be paused.
    private fun takeOver() {
        takenOver = true
        if (instead) {
            instead = false
            pausedInstead(false)
        }
    }

    /** Gives the music back, as the last reading ends however it ends; once. */
    fun end() {
        if (way == Way.NONE) return
        music.unwatch()
        if (takenOver || !music.remote) return
        when (way) {
            Way.PAUSED -> music.play()
            Way.ABSOLUTE -> music.setVolumeTo(before)
            Way.RELATIVE -> repeat(raises()) { music.adjustVolume(AudioManager.ADJUST_RAISE) }
            Way.NONE -> Unit
        }
        takenOver = true
    }

    // The steps up that bring a relative volume back to the one before, no more than it was lowered.
    private fun raises(): Int = if (counted) (before - music.volume).coerceIn(0, steps) else steps

    companion object {
        /** The share of its volume the music is lowered to, as near Android's ducking as a speaker's scale allows. */
        const val LOWERED_SHARE: Float = 0.3f

        /** The share of the most a volume can be by which a report may differ and still count as the same volume. */
        const val TOLERANCE_SHARE: Float = 0.02f

        /** The steps a relative volume is lowered by where it reports no volume to count from. */
        const val RELATIVE_STEPS: Int = 3

        // Never to nothing: lowered, not muted.
        private fun lowerOf(volume: Int): Int =
            (volume * LOWERED_SHARE).roundToInt().let { if (volume > 0) it.coerceAtLeast(1) else it }

        /**
         * Holds [music] for the readings, [lower] or paused, as above, telling [pausedInstead]
         * whether it is paused though *Reduce volume* is chosen, as that changes.
         */
        fun start(
            music: RemoteMusic,
            lower: Boolean,
            pausedInstead: (Boolean) -> Unit = {},
        ): RemoteHold {
            val way =
                when {
                    !lower -> Way.PAUSED
                    music.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE ->
                        if (music.volume > 0) Way.ABSOLUTE else Way.NONE
                    music.volumeControl == VolumeProvider.VOLUME_CONTROL_RELATIVE -> Way.RELATIVE
                    else -> Way.PAUSED
                }
            return RemoteHold(music, way, pausedInstead).also { it.start(lower) }
        }
    }
}

/** A [RemoteMusic] over a `MediaController`; its player's remote state read as the session layer reads it. */
class ControllerRemoteMusic(
    private val controller: MediaController,
) : RemoteMusic {
    private var ended = false
    private var callback: MediaController.Callback? = null

    // Annotated non-null, yet null from a Robolectric controller: read as possibly absent.
    private val info: MediaController.PlaybackInfo? get() = controller.playbackInfo

    override val remote: Boolean get() = !ended && ControllerSession(controller).remote

    override val playing: Boolean
        get() {
            val state: PlaybackState? = controller.playbackState
            return SessionMapping.isActive(SessionMapping.effectiveState(controller.packageName, state, remote))
        }

    override val volumeControl: Int get() = info?.volumeControl ?: VolumeProvider.VOLUME_CONTROL_FIXED
    override val volume: Int get() = info?.currentVolume ?: 0
    override val maxVolume: Int get() = info?.maxVolume ?: 0

    override fun pause() = controller.transportControls.pause()

    override fun play() = controller.transportControls.play()

    override fun setVolumeTo(volume: Int) = controller.setVolumeTo(volume, 0)

    override fun adjustVolume(direction: Int) = controller.adjustVolume(direction, 0)

    override fun watch(changed: (RemoteReport) -> Unit) {
        unwatch()
        val watching = ReportingCallback(controller.packageName, remote, { ended = true }, changed)
        controller.registerCallback(watching, Handler(Looper.getMainLooper()))
        callback = watching
    }

    override fun unwatch() {
        callback?.let(controller::unregisterCallback)
        callback = null
    }
}

/**
 * A session's callback as [RemoteMusic.watch] hands its reports to [changed]: each playback state
 * read as the session layer reads it ([SessionMapping.effectiveState] for [player], by whether it
 * played remotely as last reported, [remote] at first), each audio info with whether it plays
 * remotely and its volume, and the session's end as no longer remote, after [ended].
 */
internal class ReportingCallback(
    private val player: String,
    private var remote: Boolean,
    private val ended: () -> Unit,
    private val changed: (RemoteReport) -> Unit,
) : MediaController.Callback() {
    override fun onPlaybackStateChanged(state: PlaybackState?) {
        val shown = SessionMapping.effectiveState(player, state, remote)
        changed(RemoteReport(playing = SessionMapping.isActive(shown)))
    }

    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
        remote = info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        changed(RemoteReport(remote = remote, volume = info.currentVolume))
    }

    override fun onSessionDestroyed() {
        ended()
        changed(RemoteReport(remote = false))
    }
}

/**
 * One active session as [remoteToHold] weighs it: its [player], then, each read only when it must
 * be, whether it is a [call] and its [music].
 */
internal class SessionCandidate(
    val player: String,
    val call: () -> Boolean,
    val music: () -> RemoteMusic,
)

/**
 * The music a reading holds through its session (#803), of the active [sessions], highest priority
 * first: the first of a player [followed] says is followed, not a call, that plays on another
 * device and plays now; null when none does. Nothing of another player's session is read.
 */
internal fun remoteToHold(
    sessions: List<SessionCandidate>,
    followed: (String) -> Boolean,
): RemoteMusic? =
    sessions
        .asSequence()
        .filter { followed(it.player) && !it.call() }
        .map { it.music() }
        .firstOrNull { it.remote && it.playing }

/**
 * The music a reading acts on through its session (#803): the first active session of a player
 * [followed] says is followed, not a call, that plays on another device; null when none does, or
 * without notification access. Nothing of another player's session is read.
 */
class SystemRemoteMusic(
    context: Context,
    private val followed: (String) -> Boolean,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, SleevesightListenerService::class.java)

    /** The remote music playing now, or null. */
    fun playing(): RemoteMusic? {
        val controllers =
            try {
                manager?.getActiveSessions(listener).orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        val sessions =
            controllers.map { controller ->
                SessionCandidate(
                    controller.packageName,
                    call = { ControllerSession(controller).marksCall },
                    music = { ControllerRemoteMusic(controller) },
                )
            }
        return remoteToHold(sessions, followed)
    }
}
