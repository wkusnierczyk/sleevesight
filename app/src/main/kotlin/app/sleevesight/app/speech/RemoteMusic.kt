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

    /** Calls [changed] at each report of its playback state or volume, and when it ends, until [unwatch]. */
    fun watch(changed: () -> Unit)

    fun unwatch()
}

/**
 * What a reading does to [music], a remote session that plays as it starts, as [lower] (*Reduce
 * volume*) or not (*Pause playback*) says, undone by [end]:
 * - paused through its transport controls with *Pause playback*, and with *Reduce volume* where its
 *   volume is fixed ([pausedInstead]); played again at the end unless the user took it over in
 *   between: it played again after the pause had landed (the user paused it after, or plays it
 *   now), or it no longer plays on another device;
 * - lowered with an absolute volume, to [LOWERED_SHARE] of it, and set back at the end unless the
 *   user set another volume after the lowered one was reported, or it no longer plays remotely;
 * - lowered with a relative volume by as many steps as that takes (or [RELATIVE_STEPS] where it
 *   reports no volume), and raised by as many at the end, unless it no longer plays remotely.
 */
class RemoteHold private constructor(
    val music: RemoteMusic,
    private val way: Way,
) {
    private enum class Way { PAUSED, ABSOLUTE, RELATIVE, NONE }

    // The volume before, and the one set; the steps taken down.
    private var before = 0
    private var lowered = 0
    private var steps = 0

    // Whether the pause or the lowered volume has been reported, and whether the user took over after.
    private var landed = false
    private var takenOver = false

    /** Whether the music was paused though *Reduce volume* is chosen: its volume cannot be set from the phone. */
    var pausedInstead: Boolean = false
        private set

    private fun start(lower: Boolean) {
        when (way) {
            Way.PAUSED -> {
                pausedInstead = lower
                music.pause()
            }
            Way.ABSOLUTE -> {
                before = music.volume
                lowered = lowerOf(before)
                music.setVolumeTo(lowered)
            }
            Way.RELATIVE -> {
                val max = music.maxVolume
                steps = if (max > 0) music.volume - lowerOf(music.volume) else RELATIVE_STEPS
                repeat(steps) { music.adjustVolume(AudioManager.ADJUST_LOWER) }
            }
            Way.NONE -> return
        }
        music.watch(::changed)
    }

    private fun changed() {
        if (!music.remote) {
            takenOver = true
            return
        }
        when (way) {
            Way.PAUSED ->
                if (!landed) {
                    landed = !music.playing
                } else if (music.playing) {
                    takenOver = true
                }
            Way.ABSOLUTE ->
                if (!landed) {
                    landed = music.volume == lowered
                } else if (music.volume != lowered) {
                    takenOver = true
                }
            Way.RELATIVE, Way.NONE -> Unit
        }
    }

    /** Gives the music back, as the reading ends however it ends; once. */
    fun end() {
        if (way == Way.NONE) return
        music.unwatch()
        if (takenOver || !music.remote) return
        when (way) {
            Way.PAUSED -> music.play()
            Way.ABSOLUTE -> music.setVolumeTo(before)
            Way.RELATIVE -> repeat(steps) { music.adjustVolume(AudioManager.ADJUST_RAISE) }
            Way.NONE -> Unit
        }
        takenOver = true
    }

    companion object {
        /** The share of its volume the music is lowered to, as near Android's ducking as a speaker's scale allows. */
        const val LOWERED_SHARE: Float = 0.3f

        /** The steps a relative volume is lowered by where it reports no volume to count from. */
        const val RELATIVE_STEPS: Int = 3

        private fun lowerOf(volume: Int): Int = (volume * LOWERED_SHARE).roundToInt()

        /** Holds [music] for a reading, [lower] or paused, as above. */
        fun start(
            music: RemoteMusic,
            lower: Boolean,
        ): RemoteHold {
            val way =
                when {
                    !lower -> Way.PAUSED
                    music.volumeControl == VolumeProvider.VOLUME_CONTROL_ABSOLUTE ->
                        if (music.volume > 0) Way.ABSOLUTE else Way.NONE
                    music.volumeControl == VolumeProvider.VOLUME_CONTROL_RELATIVE -> Way.RELATIVE
                    else -> Way.PAUSED
                }
            return RemoteHold(music, way).also { it.start(lower) }
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

    override fun watch(changed: () -> Unit) {
        unwatch()
        val watching =
            object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = changed()

                override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = changed()

                override fun onSessionDestroyed() {
                    ended = true
                    changed()
                }
            }
        controller.registerCallback(watching, Handler(Looper.getMainLooper()))
        callback = watching
    }

    override fun unwatch() {
        callback?.let(controller::unregisterCallback)
        callback = null
    }
}

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
        return controllers
            .asSequence()
            .filter { followed(it.packageName) && !ControllerSession(it).marksCall }
            .map(::ControllerRemoteMusic)
            .firstOrNull { it.remote && it.playing }
    }
}
