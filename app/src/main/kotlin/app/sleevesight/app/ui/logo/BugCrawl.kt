package app.sleevesight.app.ui.logo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.sleevesight.core.settings.LogoSpeed
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin

// A debug build's bug in the record's place (#652) crawling while music plays, where a release
// build's record turns (#848).

/**
 * Where the bug is in its rounds (#848): resting in its place for [CRAWL_REST_SECONDS] ([rested], the
 * seconds of it so far), then on a trip ([trip], 0 to 1, null while it rests): straight up out of
 * the header, and back in from its bottom, up to its place.
 */
@Immutable
internal data class BugCrawl(
    val rested: Float = 0f,
    val trip: Float? = null,
) {
    /** Whether the bug is on a trip, out of its place. */
    val moving: Boolean get() = trip != null

    /**
     * This crawl [seconds] later, a trip taking [tripSeconds]: while [active] (the record would turn),
     * a rest and a trip in turn; not, a trip under way goes on to its end, where the bug stays.
     */
    fun after(
        seconds: Float,
        active: Boolean,
        tripSeconds: Float,
    ): BugCrawl {
        var left = seconds
        var now = this
        while (left > 0f && (active || now.moving)) {
            val trip = now.trip
            if (trip == null) {
                val wait = CRAWL_REST_SECONDS - now.rested
                now = if (left < wait) BugCrawl(now.rested + left) else BugCrawl(trip = 0f)
                left -= wait
            } else {
                val rest = (1 - trip) * tripSeconds
                now = if (left < rest) BugCrawl(trip = trip + left / tripSeconds) else BugCrawl()
                left -= rest
            }
        }
        return if (active || now.moving) now else BugCrawl()
    }
}

/**
 * The trip's seconds at [speed]: [CRAWL_SECONDS] at 33⅓ rpm, and as much faster as the record would
 * turn faster, so at 45 rpm 1.35 times as fast.
 */
internal fun tripSeconds(speed: LogoSpeed): Float = CRAWL_SECONDS * LogoSpeed.RPM_33.rpm / speed.rpm

/**
 * Where the bug is, [trip] of the way, against its place, in the logo's units: up from its place by
 * as far as its foot passes the header's top ([above], from its centre; [half], its half height),
 * then, the rest of the way, up from where its head is at the header's bottom ([below]) to its place,
 * at one steady speed; swaying side to side a little as it goes, not at all at the trip's ends.
 */
internal fun crawlOffset(
    trip: Float,
    above: Float,
    below: Float,
    half: Float,
): Offset {
    val out = above + half
    val back = below + half
    val gone = trip * (out + back)
    val y = if (gone < out) -gone else back - (gone - out)
    val x = sin(2 * PI.toFloat() * gone / (half * STRIDE)) * half * SWAY * sin(PI.toFloat() * trip)
    return Offset(x, y)
}

/**
 * The bug's crawl in a debug build's header (#848): rounds of a rest and a trip while [spin] says
 * the record would turn, at its speed; a trip under way when it stops goes on to its end. It moves
 * only while [running] (the intro over) and on screen ([whileOnScreen]); under *Remove animations* it
 * stays in its place.
 */
@Composable
internal fun bugCrawl(
    spin: LogoSpin,
    running: Boolean,
): State<BugCrawl> {
    val crawl = remember { mutableStateOf(BugCrawl()) }
    // The rest so far, kept out of the drawn state, so resting draws nothing, and kept across the
    // effect's restarts, so the rest is as long after one.
    val rest = remember { Rest() }
    val still = animatorDurationScale() == 0f
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val trip = tripSeconds(spin.speed)
    val active = spin.turning
    LaunchedEffect(running, still, lifecycle, active, trip) {
        if (still || !(active || crawl.value.moving)) {
            crawl.value = BugCrawl()
            rest.seconds = 0f
        }
        if (still || !running) return@LaunchedEffect
        whileOnScreen(lifecycle) {
            while (active || crawl.value.moving) {
                if (!crawl.value.moving) {
                    rest.wait()
                    crawl.value = BugCrawl(trip = 0f)
                }
                // Frames only for the trip, to its end, even if the music stops on the way.
                eachFrame { seconds ->
                    crawl.value = crawl.value.after(seconds, active = false, trip)
                    crawl.value.moving
                }
            }
        }
    }
    return crawl
}

/**
 * The bug's rest between trips, counted with no frames, by waits of at most [REST_STEP_SECONDS]
 * ([seconds], the rest so far): a wait cut short, by the effect starting again or the app leaving
 * the screen, loses only part of a step.
 */
private class Rest {
    var seconds = 0f

    /** Waits out the rest still to go, and starts the next one from nothing. */
    suspend fun wait() {
        while (seconds < CRAWL_REST_SECONDS) {
            val step = minOf(REST_STEP_SECONDS, CRAWL_REST_SECONDS - seconds)
            delay((step * MILLIS_PER_SECOND).roundToLong())
            seconds += step
        }
        seconds = 0f
    }
}

/** The rest between trips, the same at either speed. */
internal const val CRAWL_REST_SECONDS: Float = 4f

/** A trip's seconds at 33⅓ rpm ([tripSeconds]). */
internal const val CRAWL_SECONDS: Float = 1.5f

private const val REST_STEP_SECONDS = .25f
private const val MILLIS_PER_SECOND = 1000f

// One sway to each side for each 0.8 of the bug's half height crawled, by 6% of it.
private const val STRIDE = .8f
private const val SWAY = .06f
