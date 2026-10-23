// The product page's timings, easings and geometry, as its keyframes and splashes give them.
@file:Suppress("MagicNumber")

package app.sleevesight.app.ui.logo

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.cos
import kotlin.math.sin

/**
 * The intros, as the product page plays them (`index.html`, `.sl-intro`, whose comment describes
 * each; the brand sheet's reveals for R1 and R2), in the stacked mark's units ([LogoOutlines]).
 * Each plays large in the middle of the screen, in its own box ([box], the page's splash viewBox),
 * and ends on the stacked mark, which then flies onto the header's one-line logo from [fly] to
 * [land]; the screen behind arrives over [ARRIVE] ms from [land].
 *
 * - C: the mark fades in (0-0.7 s), turns twice about the record's centre (0.9-2.9 s).
 * - D: the tile, on C's timings, turning about the dot of the i; it fades as it flies, so the words
 *   land on the header, and the record then turns in beside "sight", growing as it fades in
 *   (4.2-4.9 s).
 * - F: the mark fades in, and the record circles the words once about the dot of the i
 *   (0.9-2.9 s), the words staying still.
 * - R1: "Sleeve" fades in, and the record rolls in under it from the left, two and a quarter turns
 *   over its own circumference, uncovering "sight" (0.7-3.15 s), fading in as it starts.
 * - R2: the tile slides left off the record behind it (0.56-2.1 s); the record shrinks into its
 *   place beside "sight" and the tile fades, leaving the words (2.52-3.92 s).
 *
 * The page's G, H and I (typed letters, turning e's, sliding words) came after this port was
 * specified (#383 names C, D, F, R1 and R2) and are not played here.
 */
internal enum class IntroVariant(
    val fly: Long,
    val land: Long,
    val box: Rect,
) {
    C(3300, 3950, Rect(-54.9f, -113.84f, -54.9f + 256f, -113.84f + 256f)),
    D(3300, 3950, Rect(-72f, -72f, 72f, 72f)),
    F(3300, 3950, Rect(-94f, -94f, 94f, 94f)),
    R1(3400, 4050, Rect(-56.4f, -45.76f, -56.4f + 166f, -45.76f + 86f)),
    R2(4100, 4750, Rect(-119.5f, -82.76f, -119.5f + 292f, -82.76f + 160f)),
    ;

    /**
     * When the intro is over: the screen has arrived, and for D, the record has turned in beside
     * "sight" (only where there is a header logo to turn in on, [toHeader]).
     */
    fun end(toHeader: Boolean): Long = if (this == D && toHeader) land + D_RECORD_AT + D_RECORD_MS else land + ARRIVE
}

/** How long the screen takes to arrive once the logo has landed (the page's `sl-arrive`). */
internal const val ARRIVE: Long = 450

/** D's record turns in this long after landing, over [D_RECORD_MS] (the page's `d-rec`). */
internal const val D_RECORD_AT: Long = 250
internal const val D_RECORD_MS: Long = 700

/** The record's place and turn, in units, and how opaque it is. */
internal data class RecordPose(
    val centre: Offset,
    val radius: Float = LogoOutlines.RECORD_R,
    val degrees: Float = 0f,
    val alpha: Float = 1f,
)

/**
 * One moment of an intro before its flight, in units: the whole splash's opacity ([alpha]) and turn
 * ([degrees] about [pivot]), where each word is drawn from ([sleeveAt], [sightAt]), how far right
 * "sight" shows ([sightClip], R1's, null for all of it), the record (null for none: D's), and the
 * tile's centre and opacity (null for none). The tile is drawn first, then the words; the record
 * last, except in R2, where the tile slides off it ([recordUnder]).
 */
internal data class SplashPose(
    val alpha: Float = 1f,
    val degrees: Float = 0f,
    val pivot: Offset = Offset.Zero,
    val sleeveAt: Offset = Offset.Zero,
    val sightAt: Offset = Offset.Zero,
    val sightClip: Float? = null,
    val record: RecordPose? = RecordPose(RecordCentre),
    val tile: Offset? = null,
    val tileAlpha: Float = 1f,
    val recordUnder: Boolean = false,
)

// CSS's timing functions, and the page's own.
private val EASE = CubicBezierEasing(.25f, .1f, .25f, 1f)
internal val EASE_OUT: Easing = CubicBezierEasing(0f, 0f, .58f, 1f)
private val TURN = CubicBezierEasing(.45f, 0f, .55f, 1f)
private val ROLL = CubicBezierEasing(.45f, 0f, .3f, 1f)
private val SLIDE = CubicBezierEasing(.45f, 0f, .2f, 1f)

/** The flight's easing (the page's `sl-fly`). */
internal val FLY: Easing = CubicBezierEasing(.3f, 0f, .2f, 1f)

/** D's record turning in (the page's `d-rec`). */
internal val TURN_IN: Easing = CubicBezierEasing(.2f, 0f, .2f, 1f)

/** [easing] of where [t] is from [from] over [ms], held at 0 before and 1 after. */
internal fun eased(
    t: Float,
    from: Long,
    ms: Long,
    easing: Easing,
): Float = easing.transform(((t - from) / ms).coerceIn(0f, 1f))

private const val FADE_IN_MS = 700L
private const val SPIN_AT = 900L
private const val SPIN_MS = 2000L
private const val TWO_TURNS = 720f
private const val ONE_TURN = 360f

// R1: the 7 s keyframes' moments, in ms.
private const val R1_SHOW_MS = 350L
private const val R1_ROLL_AT = 700L
private const val R1_ROLL_MS = 2450L
private const val R1_RECORD_MS = 700L
private const val R1_ROLL = -248.53f
private const val R1_TURN = -810f

// R2's.
private const val R2_SLIDE_AT = 560L
private const val R2_SLIDE_MS = 1540L
private const val R2_SLIDE = 140f
private const val R2_SHRINK_AT = 2520L
private const val R2_SHRINK_MS = 1400L
private const val R2_BARE_AT = 2660L
private const val R2_BARE_MS = 1120L
private const val R2_PAN = -39.5f
private const val R2_FROM_X = -126.12f
private const val R2_FROM_Y = -51.55f
private const val R2_FROM_SCALE = 3.6405f

/** [variant] at [t] ms, before its flight. */
internal fun splashPose(
    variant: IntroVariant,
    t: Float,
): SplashPose =
    when (variant) {
        IntroVariant.C ->
            SplashPose(
                alpha = eased(t, 0, FADE_IN_MS, EASE_OUT),
                degrees = TWO_TURNS * eased(t, SPIN_AT, SPIN_MS, TURN),
                pivot = RecordCentre,
            )
        IntroVariant.D ->
            SplashPose(
                alpha = eased(t, 0, FADE_IN_MS, EASE_OUT),
                degrees = TWO_TURNS * eased(t, SPIN_AT, SPIN_MS, TURN),
                record = null,
                tile = Offset.Zero,
            )
        IntroVariant.F -> {
            val degrees = ONE_TURN * eased(t, SPIN_AT, SPIN_MS, TURN)
            SplashPose(
                alpha = eased(t, 0, FADE_IN_MS, EASE_OUT),
                record = RecordPose(RecordCentre.turned(degrees), degrees = degrees),
            )
        }
        IntroVariant.R1 -> {
            val p = eased(t, R1_ROLL_AT, R1_ROLL_MS, ROLL)
            val x = R1_ROLL * (1 - p)
            SplashPose(
                alpha = eased(t, 0, R1_SHOW_MS, EASE),
                sightClip = RecordCentre.x + x,
                record =
                    RecordPose(
                        RecordCentre + Offset(x, 0f),
                        degrees = R1_TURN * (1 - p),
                        alpha = eased(t, R1_ROLL_AT, R1_RECORD_MS, EASE),
                    ),
            )
        }
        IntroVariant.R2 -> {
            val slide = Offset(R2_SLIDE * (1 - eased(t, R2_SLIDE_AT, R2_SLIDE_MS, SLIDE)), 0f)
            val p = eased(t, R2_SHRINK_AT, R2_SHRINK_MS, ROLL)
            val pan = Offset(R2_PAN * (1 - p), 0f)
            val scale = R2_FROM_SCALE + (1 - R2_FROM_SCALE) * p
            val from = Offset(R2_FROM_X, R2_FROM_Y) * (1 - p)
            SplashPose(
                alpha = eased(t, 0, R1_SHOW_MS, EASE),
                sleeveAt = pan + slide,
                sightAt = pan + slide,
                record = RecordPose(pan + from + RecordCentre * scale, radius = LogoOutlines.RECORD_R * scale),
                tile = pan + slide,
                tileAlpha = 1 - eased(t, R2_BARE_AT, R2_BARE_MS, EASE),
                recordUnder = true,
            )
        }
    }

/** This point turned by [degrees] about the origin, clockwise on screen (y down). */
private fun Offset.turned(degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}
