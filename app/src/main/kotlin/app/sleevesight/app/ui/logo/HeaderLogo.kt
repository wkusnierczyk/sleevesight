package app.sleevesight.app.ui.logo

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The header's logo, on one line (#383): "Sleeve" in ink and "sight" in red, followed by the
 * record, in the stacked mark's type (Roboto Flex regular, 88% wide, the optical size 52.64), as
 * outlines, so the app needs no font file for it ([LogoOutlines], made by
 * `assets/logo/src/make_app_logo.py`). As the product page's one-line logo
 * (`_includes/logo-linear.html`): the record runs from the top of the "h" to the foot of the "g",
 * 0.266 em after the "t", so [height] is the record's. In the light or dark colours as the
 * screen's background is light or dark. In a debug build ([LOGO_BUG], #652), the tester report
 * button's bug takes the record's place ([drawRecordPlace]), as in the intro's landed logo, which
 * reads the same [LOGO_BUG].
 *
 * It says nothing itself and takes no taps: its caller names it (Home's header: "Sleevesight, scroll
 * to top"), and puts the record's own button over it ([headerRecordCentre], #1409). If
 * its box is narrower than the logo, the logo shrinks to fit. While the intro plays
 * ([LocalLogoIntro]), it tells the intro where it is, and draws nothing: the intro draws the
 * logo flying onto it and landed, and this one takes over when the intro ends.
 *
 * Its record has the header's larger label ([HEADER_LABEL], #848), and turns while [spin] says so
 * (music playing, *Spin the logo while music plays* on), getting up to speed and coming to rest as a
 * turntable does ([recordMotion]); once the intro has ended, from where the intro landed it. The
 * record is drawn once, into a layer of its own, and a step of the turn only turns that layer
 * (#963, #1326): the words are not drawn again, nor the record, and nothing is laid out. In a
 * debug build the bug in its place never turns: while the record would, it crawls ([bugCrawl]),
 * every few seconds up out of [crawlSpace] (the header's box, in the root's pixels; the logo's own
 * box without one), and back in from its bottom to its place, drawn only inside that box.
 */
@Composable
fun HeaderLogo(
    modifier: Modifier = Modifier,
    height: Dp = HEADER_LOGO_HEIGHT,
    spin: LogoSpin = LogoSpin.STILL,
    crawlSpace: () -> Rect? = { null },
) = HeaderLogo(LOGO_BUG, modifier, height, spin, crawlSpace)

/**
 * [HeaderLogo] with the bug in the record's place or not, whatever the build ([LOGO_BUG]): for
 * tests, which draw both on the one build they run on, and may count the record's compositions
 * and drawings ([counts]). The app's callers use [HeaderLogo] without it, so the header always
 * draws what the intro lands.
 */
@Suppress("LongParameterList") // The public one's, the bug's choice, and the tests' counts.
@Composable
internal fun HeaderLogo(
    bug: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = HEADER_LOGO_HEIGHT,
    spin: LogoSpin = LogoSpin.STILL,
    crawlSpace: () -> Rect? = { null },
    counts: RecordCounts? = null,
) {
    val intro = LocalLogoIntro.current
    val colors = logoColors()
    val bugPath = logoBug(bug)
    val shown by remember(intro) { derivedStateOf { intro?.finished ?: true } }
    val motion = recordMotion(spin, running = shown && bugPath == null)
    val crawl = bugCrawl(spin, running = shown && bugPath != null)
    // Where the logo is in the root, for the crawl's box; read as it draws.
    val origin = remember { mutableStateOf(Offset.Zero) }
    val place =
        modifier.onGloballyPositioned {
            origin.value = it.positionInRoot()
            intro?.target = Rect(it.positionInRoot(), it.size.toSize())
        }
    Box(place.size(height * (LineBox.width / LineBox.height), height)) {
        Canvas(Modifier.matchParentSize()) {
            if (!shown) return@Canvas
            // As the intro's landing draws it ([headerPlace]), so the two meet exactly.
            val line = linePlace(size)
            at(line) {
                drawSleeve(colors)
                translate(LineShift.x, LineShift.y) { drawSight(colors) }
            }
            if (bugPath != null) {
                val box = crawlSpace()?.translate(-origin.value) ?: Rect(Offset.Zero, size)
                drawCrawlingBug(colors, bugPath, line, box, crawl.value.trip)
            }
        }
        if (bugPath == null) TurningRecord(colors, { shown }, motion, counts)
    }
}

/**
 * The header logo's record, in a box of the logo's size ([Modifier.matchParentSize]), placed as
 * the logo draws it, turned [motion]'s way, and drawn only once [shown] (#963). Its own box is just
 * its size, with a layer of its own that never moves, and draws the record's layer, into which the
 * record is drawn upright once (again only for a new size, place or colours, or once it shows).
 * A step of the turn (#1326) sets only the record's layer's rotation about its centre, a property of
 * the platform's drawing that no composition, layout or drawing reads, and asks for a frame as
 * Compose does for its own layers' properties ([redraw]): nothing is composed, laid out or drawn
 * again, the app's view keeps its drawing, and as the box does not move, accessibility and content
 * capture hear of no change of layout.
 */
@Composable
private fun BoxScope.TurningRecord(
    colors: LogoColors,
    shown: () -> Boolean,
    motion: State<RecordMotion>,
    counts: RecordCounts?,
) {
    if (counts != null) SideEffect { counts.composed++ }
    val record = rememberGraphicsLayer()
    val view = LocalView.current
    LaunchedEffect(record, view, motion) {
        snapshotFlow { motion.value.degrees }.collect {
            record.rotationZ = it
            redraw(view)
        }
    }
    // The record's box in the logo's, and the logo's place, from the last layout.
    val recordAt = remember { mutableStateOf<RecordBox?>(null) }
    Spacer(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val box = RecordBox.of(linePlace(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())))
                // Compared unobserved, so its own write asks for no second measure.
                if (Snapshot.withoutReadObservation { recordAt.value } != box) recordAt.value = box
                val placeable = measurable.measure(Constraints.fixed(box.width, box.height))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(box.topLeft) }
            }
            // A layer of its own, whose properties never change: its drawing, the record's layer, is
            // kept apart from the nearest layer above it (Home's whole screen).
            .graphicsLayer()
            .drawWithCache {
                val box = recordAt.value
                if (box != null && size.minDimension > 0f) {
                    record.pivotOffset = box.centre
                    // Where the record is now, unobserved (the steps set it after), so a record
                    // composed afresh, as Home is shown again, never shows upright for a frame.
                    record.rotationZ = Snapshot.withoutReadObservation { motion.value.degrees }
                    record.record {
                        // The logo's own drawing, moved by the box's place: the same pixels.
                        translate(-box.topLeft.x.toFloat(), -box.topLeft.y.toFloat()) {
                            at(box.line) {
                                translate(LineShift.x, LineShift.y) {
                                    drawRecordPlace(colors, null, RecordPose(RecordCentre))
                                }
                            }
                        }
                    }
                }
                onDrawBehind {
                    if (box == null || !shown() || size.minDimension <= 0f) return@onDrawBehind
                    counts?.let { it.drawn++ }
                    drawLayer(record)
                }
            },
    )
}

/**
 * Asks for a frame after a change of a layer's property alone, as Compose does for its own layers
 * (`GraphicsLayerOwnerLayer`): [view]'s parents draw again, and [view] keeps its drawing, which holds
 * the layer, so the frame only takes up the property. A plain `invalidate()` would have [view] draw
 * all of itself again. That is what it does where [view] is drawn in software (not hardware
 * accelerated, or a software layer of its own): its drawing is then a bitmap kept until [view] is
 * invalidated, which the property alone would leave as it was, the record standing still.
 */
internal fun redraw(view: View) {
    if (view.isHardwareAccelerated && view.layerType != View.LAYER_TYPE_SOFTWARE) {
        view.parent?.onDescendantInvalidated(view, view)
    } else {
        view.invalidate()
    }
}

/**
 * For tests (#1326): how many times the header logo's record has been composed ([composed]) and
 * drawn ([drawn]), to show that its turning does neither.
 */
internal class RecordCounts {
    var composed: Int = 0
    var drawn: Int = 0
}

/**
 * The record's box in the header logo drawn at [line]: the whole pixels round the record and one
 * more each way, for its edge's smoothing, at [topLeft] in the logo's box; the record's [centre]
 * in the box's own pixels.
 */
private data class RecordBox(
    val line: Place,
    val topLeft: IntOffset,
    val width: Int,
    val height: Int,
    val centre: Offset,
) {
    companion object {
        fun of(line: Place): RecordBox {
            val centre = line.at + (RecordCentre + LineShift) * line.scale
            val r = LogoOutlines.RECORD_R * line.scale
            val left = floor(centre.x - r).toInt() - 1
            val top = floor(centre.y - r).toInt() - 1
            val right = ceil(centre.x + r).toInt() + 1
            val bottom = ceil(centre.y + r).toInt() + 1
            return RecordBox(
                line,
                IntOffset(left, top),
                (right - left).coerceAtLeast(0),
                (bottom - top).coerceAtLeast(0),
                centre - Offset(left.toFloat(), top.toFloat()),
            )
        }
    }
}

/**
 * Draws the bug in the record's place, [trip] of the way through a crawl (none: in its place), the
 * logo drawn at [line], out of and back into [box] (in the canvas's pixels), and only inside it.
 */
private fun DrawScope.drawCrawlingBug(
    colors: LogoColors,
    bug: Path,
    line: Place,
    box: Rect,
    trip: Float?,
) {
    val centre = RecordCentre + LineShift
    val offset =
        trip?.let {
            val y = line.at.y + centre.y * line.scale
            crawlOffset(it, (y - box.top) / line.scale, (box.bottom - y) / line.scale, LogoOutlines.RECORD_R)
        } ?: Offset.Zero
    clipRect(box.left, box.top, box.right, box.bottom) {
        at(line) { drawRecordPlace(colors, bug, RecordPose(centre + offset)) }
    }
}

/**
 * The centre of the record, or of the debug build's bug in its place, in a header logo drawn in a box
 * of [size] ([HeaderLogo]), in that box's pixels: where Home's header puts the record's own button
 * (#1409), as the logo shrinks to fit too.
 */
internal fun headerRecordCentre(size: Size): Offset {
    val line = linePlace(size)
    return line.at + (RecordCentre + LineShift) * line.scale
}

/**
 * The header logo's height, the record's: its capitals are then about as tall as a 24 sp
 * headline's, the app's name as the header showed it before.
 */
val HEADER_LOGO_HEIGHT: Dp = 24.dp

/** The logo's colours for the theme's background: dark where it is dark. */
@Composable
internal fun logoColors(): LogoColors =
    if (MaterialTheme.colorScheme.surface.luminance() < HALF) LogoColors.Dark else LogoColors.Light

private const val HALF = .5f
