package app.sleevesight.app.ui.logo

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.ui.home.playing
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import kotlin.math.abs
import kotlin.math.sign

// The header logo's record turning while music plays (#848).

/**
 * What turns the record in Home's header logo (#848): whether it turns ([turning]: the player line
 * says *Playing* and *Spin the logo while music plays* is on), and how fast ([speed], *Logo speed*).
 */
@Immutable
data class LogoSpin(
    val turning: Boolean,
    val speed: LogoSpeed = LogoSpeed.RPM_33,
) {
    companion object {
        /** The record standing still: nothing playing, or the setting off. */
        val STILL: LogoSpin = LogoSpin(turning = false)

        /**
         * The spin for what Home shows: [turning] while the player line says *Playing* ([playing],
         * the same test the line's state goes by) and [Settings.spinLogo] is on; at
         * [Settings.logoSpeed]. Before the settings' first read, as their defaults.
         */
        fun of(
            nowPlaying: NowPlaying?,
            playback: PlaybackInfo,
            settings: Settings?,
        ): LogoSpin {
            val chosen = settings ?: Settings()
            return LogoSpin(chosen.spinLogo && playing(nowPlaying, playback), chosen.logoSpeed)
        }
    }
}

/**
 * The record's motion (#848): how far it has turned ([degrees], clockwise, from 0 up to a turn) and
 * how fast it turns now ([speed], degrees a second).
 */
@Immutable
internal data class RecordMotion(
    val degrees: Float = 0f,
    val speed: Float = 0f,
) {
    /**
     * This motion [seconds] later, its speed going to [target] as a turntable's does: up from rest
     * to [top] in [START_SECONDS], down from [top] to rest in [STOP_SECONDS], evenly; the turn over
     * the time exact, however the time is cut, so the record rests wherever its speed reaches 0.
     */
    fun after(
        seconds: Float,
        target: Float,
        top: Float,
    ): RecordMotion {
        val rate = if (target > speed) top / START_SECONDS else top / STOP_SECONDS
        // How long the speed takes to reach the target; until then it changes evenly, so the record
        // turns as far as at the mean of its speeds.
        val reach = if (rate > 0f) abs(target - speed) / rate else 0f
        val (end, turned) =
            when {
                seconds <= 0f -> speed to 0f
                seconds < reach -> {
                    val now = speed + sign(target - speed) * rate * seconds
                    now to (speed + now) / 2 * seconds
                }
                else -> target to (speed + target) / 2 * reach + target * (seconds - reach)
            }
        return RecordMotion(wrap(degrees + turned), end)
    }

    companion object {
        /** Kept across a configuration change and a return to Home. */
        val Saver =
            listSaver<MutableState<RecordMotion>, Float>(
                save = { listOf(it.value.degrees, it.value.speed) },
                restore = { mutableStateOf(RecordMotion(it[0], it[1])) },
            )
    }
}

/**
 * The header logo's record's motion (#848), read as it draws: turning at [spin]'s speed while [spin]
 * says it turns, getting up to speed in [START_SECONDS] and coming to rest in [STOP_SECONDS],
 * resting where it stops and starting again from there. It moves only while [running] (the intro
 * over, so the record the intro landed, upright, is where it starts) and while frames come: not
 * while the app is off screen, nor while Home is not composed. Under *Remove animations*
 * ([animatorDurationScale] 0) it stands still, upright, as the in-progress record does (#785).
 */
@Composable
internal fun recordMotion(
    spin: LogoSpin,
    running: Boolean,
): State<RecordMotion> {
    val motion = rememberSaveable(saver = RecordMotion.Saver) { mutableStateOf(RecordMotion()) }
    val still = animatorDurationScale() == 0f
    val top = spin.speed.rpm * DEGREES_PER_RPM
    val target = if (spin.turning) top else 0f
    LaunchedEffect(running, still, target, top) {
        if (still) motion.value = RecordMotion()
        if (still || !running) return@LaunchedEffect
        // Frames as an infinite animation's, which a test's clock holds back while it waits for idle.
        var last = withInfiniteAnimationFrameNanos { it }
        while (target > 0f || motion.value.speed > 0f) {
            val now = withInfiniteAnimationFrameNanos { it }
            // A frame long in coming (the app back on screen) moves the record only a frame's worth.
            val seconds = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_STEP_SECONDS)
            last = now
            motion.value = motion.value.after(seconds, target, top)
        }
    }
    return motion
}

private fun wrap(degrees: Float): Float = degrees % FULL_TURN

/** The time the record takes to get up to speed, and to come to rest from it, as a turntable's. */
internal const val START_SECONDS: Float = 1f
internal const val STOP_SECONDS: Float = 2f

/** Degrees a second for each revolution a minute. */
internal const val DEGREES_PER_RPM: Float = 6f

private const val FULL_TURN = 360f
private const val NANOS_PER_SECOND = 1e9f
private const val MAX_STEP_SECONDS = .1f
