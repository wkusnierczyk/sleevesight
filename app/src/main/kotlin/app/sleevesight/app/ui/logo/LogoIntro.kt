package app.sleevesight.app.ui.logo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The logo's intro, played over the app's first screen at a cold start (#383): which one
 * ([variant]), how far it has got ([elapsed], in the intro's own ms), and where the header's logo
 * is ([target], in the root's pixels, set by [HeaderLogo]), which the mark flies onto. [timeScale]
 * is the phone's animator duration scale, which stretches or shortens it as it does Android's own
 * animations.
 *
 * While it plays, [HeaderLogo] draws nothing: the intro draws the logo, flying and then landed on
 * the header's place, over its background, which fades as the screen arrives; the header's own
 * logo takes over, in the same place, in the frame the intro ends.
 */
@Stable
class LogoIntro internal constructor(
    internal val variant: IntroVariant,
    internal val timeScale: Float = 1f,
) {
    internal var elapsed: Float by mutableFloatStateOf(0f)

    /** Where the header's logo is, in the root's pixels; null while there is none (first run). */
    internal var target: Rect? by mutableStateOf(null)

    /** Whether the intro is over, played to its end or skipped. */
    var finished: Boolean by mutableStateOf(false)
        private set

    /** Ends the intro at once: a tap, Back, or a key. */
    fun skip() {
        finished = true
    }

    internal fun advance(ms: Float) {
        elapsed = ms
        if (ms >= variant.end(toHeader = target != null)) finished = true
    }

    companion object {
        /** One of the intros, picked at random, as the product page picks one on each visit. */
        fun random(
            timeScale: Float = 1f,
            random: Random = Random.Default,
        ): LogoIntro = LogoIntro(IntroVariant.entries.random(random), timeScale)
    }
}

/** The intro playing, for [HeaderLogo]; null where none plays (after it, or never). */
val LocalLogoIntro = staticCompositionLocalOf<LogoIntro?> { null }

/** The tag of the intro's layer, for tests. */
const val LOGO_INTRO_TAG: String = "logo-intro"

/**
 * Plays [intro] over the whole screen, on the screen's background ([MaterialTheme]'s surface),
 * until it ends, or a tap, Back or any key ends it ([endsOnKeys], which `MainActivity` also sets
 * above the screen, so a key reaches the intro whatever holds the focus). It is hidden
 * from screen readers: it says nothing, and is not played while touch exploration is on; while it
 * plays, `MainActivity` hides the screen beneath from accessibility services, so none can reach
 * it behind the intro.
 */
@Composable
fun LogoIntroOverlay(
    intro: LogoIntro,
    modifier: Modifier = Modifier,
) {
    if (intro.finished) return
    val background = MaterialTheme.colorScheme.surface
    val colors = logoColors()
    BackHandler { intro.skip() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    LaunchedEffect(intro) {
        val start = withFrameNanos { it }
        while (!intro.finished) {
            withFrameNanos { now -> intro.advance((now - start) / NANOS_PER_MS / intro.timeScale) }
        }
    }
    Canvas(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { testTag = LOGO_INTRO_TAG }
            // Holds the focus while nothing beneath does, so a key comes here.
            .endsOnKeys(intro)
            .focusRequester(focus)
            .focusable()
            .pointerInput(intro) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    intro.skip()
                }
            },
    ) {
        drawIntro(intro.variant, intro.elapsed, intro.target, background, colors)
    }
}

private const val NANOS_PER_MS = 1_000_000f

/**
 * While [intro] plays, any key but Back (which the intro's own Back handler takes) ends it. A key
 * for the screen goes no further, since the screen it would act on is behind the intro; a system
 * key (volume, a headset's or the phone's media keys) goes on to do its own work, so turning the
 * music down during the intro still does. On an ancestor of the screen and of the intro, it sees
 * every key before whatever holds the focus.
 */
fun Modifier.endsOnKeys(intro: LogoIntro?): Modifier =
    if (intro == null) {
        this
    } else {
        onPreviewKeyEvent { event ->
            val ends = !intro.finished && event.key != Key.Back
            if (ends) intro.skip()
            ends && !event.nativeKeyEvent.isSystem
        }
    }

/** The largest a splash is drawn: the page's 300 px for D's tile, 144 units across. */
private val MAX_SPLASH = 300.dp
private const val TILE_UNITS = 144f
private const val WIDTH = .95f
private const val HEIGHT = .9f
private const val D_WIDTH = .62f
private const val D_HEIGHT = .45f

/**
 * Where [variant]'s splash is drawn on a screen of [size]: its box's top left in pixels, and its
 * scale in pixels to the unit. As on the page: centred, at most 300/144 dp to the unit, and less
 * where the screen is too small for its box (95% of the width, 90% of the height); D's tile at
 * most 62% of the width and 45% of the height.
 */
internal fun Density.splashPlace(
    variant: IntroVariant,
    size: Size,
): Pair<Offset, Float> {
    val box = variant.box
    val most = MAX_SPLASH.toPx()
    val k =
        if (variant == IntroVariant.D) {
            minOf(most, size.width * D_WIDTH, size.height * D_HEIGHT) / TILE_UNITS
        } else {
            minOf(most / TILE_UNITS, size.width * WIDTH / box.width, size.height * HEIGHT / box.height)
        }
    return Offset((size.width - box.width * k) / 2, (size.height - box.height * k) / 2) to k
}

/** A part's mapping from units to pixels: [at] plus [scale] times the point. */
internal data class Place(
    val at: Offset,
    val scale: Float,
) {
    fun lerp(
        to: Place,
        e: Float,
    ) = Place(at + (to.at - at) * e, scale + (to.scale - scale) * e)
}

/**
 * Where [target], the header's logo, draws the stacked mark's units, for a part moved by [shift]
 * on its line (zero for "Sleeve", [LineShift] for "sight" and the record).
 */
internal fun headerPlace(
    target: Rect,
    shift: Offset,
): Place {
    val line = linePlace(target.size)
    return Place(target.topLeft + line.at + shift * line.scale, line.scale)
}

/**
 * Where the one-line logo draws the stacked mark's units in a box of [size] ("Sleeve"'s place;
 * "sight" and the record are [LineShift] further): as large as fits, so its full height unless the
 * box is narrower than the logo (a narrow screen at a large font), at the left, centred
 * vertically.
 */
internal fun linePlace(size: Size): Place {
    val k = minOf(size.height / LineBox.height, size.width / LineBox.width)
    val top = (size.height - LineBox.height * k) / 2
    return Place(Offset(0f, top) - Offset(LineBox.LEFT, LineBox.TOP) * k, k)
}

internal inline fun DrawScope.at(
    place: Place,
    crossinline block: DrawScope.() -> Unit,
) = translate(place.at.x, place.at.y) { scale(place.scale, place.scale, pivot = Offset.Zero) { block() } }

private const val D_GROW = .6f
private const val ONE_TURN_DEG = 360f

/**
 * Draws [variant] at [t] ms: the background, opaque until the logo lands and then fading as the
 * screen arrives; the splash; its flight onto [target], each part ("Sleeve"; "sight"; the record)
 * onto its own place in the one-line logo, so the stacked mark comes apart into the line as it
 * shrinks, or, with no header logo to land on (the first-run screen), fading where it is; and D's
 * record turning in on the header.
 */
internal fun DrawScope.drawIntro(
    variant: IntroVariant,
    t: Float,
    target: Rect?,
    background: Color,
    colors: LogoColors,
) {
    drawRect(background, alpha = 1 - eased(t, variant.land, ARRIVE, EASE_OUT))
    val (origin, k) = splashPlace(variant, size)
    val splash = Place(origin - variant.box.topLeft * k, k)
    when {
        t < variant.fly -> drawSplash(splashPose(variant, t), splash, colors)
        t < variant.land -> {
            val e = eased(t, variant.fly, variant.land - variant.fly, FLY)
            val pose = splashPose(variant, variant.fly.toFloat())
            if (target == null) {
                drawSplash(pose.copy(alpha = 1 - e), splash, colors)
            } else {
                val sleeve = splash.lerp(headerPlace(target, Offset.Zero), e)
                val sight = splash.lerp(headerPlace(target, LineShift), e)
                // D's tile fades as it flies, so the words land on their own; R2's, already gone, stays gone.
                pose.tile?.let { at(sleeve) { drawTile(colors, it, pose.tileAlpha * (1 - e), 1f / sleeve.scale) } }
                at(sleeve) { drawSleeve(colors) }
                at(sight) {
                    drawSight(colors)
                    if (pose.record != null) drawRecord(colors, RecordCentre)
                }
            }
        }
        target != null -> {
            // Landed: the logo where the header's draws it, until the intro ends.
            at(headerPlace(target, Offset.Zero)) { drawSleeve(colors) }
            at(headerPlace(target, LineShift)) {
                drawSight(colors)
                if (variant == IntroVariant.D) {
                    val p = eased(t, variant.land + D_RECORD_AT, D_RECORD_MS, TURN_IN)
                    val radius = LogoOutlines.RECORD_R * (D_GROW + (1 - D_GROW) * p)
                    drawRecord(colors, RecordCentre, radius, -ONE_TURN_DEG * (1 - p), p)
                } else {
                    drawRecord(colors, RecordCentre)
                }
            }
        }
    }
}

/** Draws [pose] where [place] puts the splash's units. */
private fun DrawScope.drawSplash(
    pose: SplashPose,
    place: Place,
    colors: LogoColors,
) {
    if (pose.alpha <= 0f) return
    at(place) {
        rotate(pose.degrees, pivot = pose.pivot) {
            val record = pose.record
            if (record != null && pose.recordUnder) drawPosed(record, colors, pose.alpha)
            pose.tile?.let { drawTile(colors, it, pose.tileAlpha * pose.alpha, 1f / place.scale) }
            translate(pose.sleeveAt.x, pose.sleeveAt.y) { drawSleeve(colors, pose.alpha) }
            translate(pose.sightAt.x, pose.sightAt.y) {
                val clip = pose.sightClip
                if (clip == null) {
                    drawSight(colors, pose.alpha)
                } else {
                    clipRect(-CLIP_FAR, -CLIP_FAR, clip, CLIP_FAR, ClipOp.Intersect) {
                        drawSight(colors, pose.alpha)
                    }
                }
            }
            if (record != null && !pose.recordUnder) drawPosed(record, colors, pose.alpha)
        }
    }
}

private const val CLIP_FAR = 1000f

private fun DrawScope.drawPosed(
    record: RecordPose,
    colors: LogoColors,
    alpha: Float,
) = drawRecord(colors, record.centre, record.radius, record.degrees, record.alpha * alpha)
