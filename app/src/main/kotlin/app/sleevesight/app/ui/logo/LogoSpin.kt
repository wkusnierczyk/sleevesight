package app.sleevesight.app.ui.logo

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.ui.home.playing
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.delay
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
         * [Settings.logoSpeed]. Before the settings' first read, still: the setting may be off.
         */
        fun of(
            nowPlaying: NowPlaying?,
            playback: PlaybackInfo,
            settings: Settings?,
        ): LogoSpin {
            val chosen = settings ?: return STILL
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
 * over, so the record the intro landed, upright, is where it starts), while the app is on screen
 * ([whileOnScreen]: back on screen, it goes on from where it was, the time away not counted), and while
 * Home is composed. It moves in steps of [RECORD_FRAME_MS], 30 a second, not at the screen's rate
 * (#963): at 33⅓ rpm, 6.7° a step, and a quarter of the frames of a 120 Hz screen. Back after an
 * absence (off screen, or Home shown again) with nothing to turn it, it stands still at once where
 * it is; a stop while it shows coasts to rest. Under *Remove animations* ([animatorDurationScale]
 * 0) it stands still where it is, and starts again from there once animations are back.
 */
@Composable
internal fun recordMotion(
    spin: LogoSpin,
    running: Boolean,
): State<RecordMotion> {
    var made = false
    val motion = rememberSaveable(saver = RecordMotion.Saver) { mutableStateOf(RecordMotion().also { made = true }) }
    // Back after an absence: off screen, or restored (Home shown again, or the app's state restored).
    val away = remember { Away(back = !made) }
    val still = animatorDurationScale() == 0f
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) away.back = true }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val top = spin.speed.rpm * DEGREES_PER_RPM
    val target = if (spin.turning) top else 0f
    LaunchedEffect(running, still, lifecycle, target, top) {
        if (still) motion.value = motion.value.copy(speed = 0f)
        if (still || !running) return@LaunchedEffect
        whileOnScreen(lifecycle) {
            // Back after an absence with nothing to turn it, it stands where it is at once, rather
            // than coasting beside a line that already says it has stopped.
            if (away.back && target == 0f) motion.value = motion.value.copy(speed = 0f)
            away.back = false
            if (target > 0f || motion.value.speed > 0f) {
                eachFrame(every = RECORD_FRAME_MS) { seconds ->
                    motion.value = motion.value.after(seconds, target, top)
                    target > 0f || motion.value.speed > 0f
                }
            }
        }
    }
    return motion
}

/** Whether the record is back after an absence since it last moved ([recordMotion]). */
private class Away(
    var back: Boolean,
)

/**
 * Runs [block] while the app is on screen ([lifecycle] at least started), afresh each time it comes
 * back: off screen, [block] ends at once, so it asks for no frames, and back, it starts with a new
 * first frame, so the time away is not counted.
 */
internal suspend fun whileOnScreen(
    lifecycle: Lifecycle,
    block: suspend () -> Unit,
) = lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { block() }

/**
 * Calls [step] with the seconds since the frame before, frame after frame, from the frame after the
 * first, for as long as it returns true. Frames as an infinite animation's, which a test's clock
 * holds back while it waits for idle; a frame long in coming moves things only [MAX_STEP_SECONDS].
 * With [every], a frame about that many milliseconds after the one before, rather than the next:
 * it waits [every] less [FRAME_SLACK_MS] before it asks for one, so on a screen of 60, 90 or 120 Hz
 * the frame it gets is the one due [every] later, and between the two the app draws nothing.
 */
internal suspend fun eachFrame(
    every: Long = 0L,
    step: (Float) -> Boolean,
) {
    var last = withInfiniteAnimationFrameNanos { it }
    do {
        if (every > 0L) delay(every - FRAME_SLACK_MS)
        val now = withInfiniteAnimationFrameNanos { it }
        val seconds = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_STEP_SECONDS)
        last = now
    } while (step(seconds))
}

private fun wrap(degrees: Float): Float = degrees % FULL_TURN

/** The time the record takes to get up to speed, and to come to rest from it, as a turntable's. */
internal const val START_SECONDS: Float = 1f
internal const val STOP_SECONDS: Float = 2f

/** The time between two steps of the header logo's record's turn (#963): 30 a second. */
internal const val RECORD_FRAME_MS: Long = 33L

/** How much sooner [eachFrame] asks for a frame than [eachFrame]'s `every` says, for a late wake. */
private const val FRAME_SLACK_MS = 5L

/** Degrees a second for each revolution a minute. */
internal const val DEGREES_PER_RPM: Float = 6f

private const val FULL_TURN = 360f
private const val NANOS_PER_SECOND = 1e9f
private const val MAX_STEP_SECONDS = .1f
