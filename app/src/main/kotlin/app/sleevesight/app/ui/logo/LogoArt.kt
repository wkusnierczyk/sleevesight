// The product page's colours, as its style sheet and `#lp` give them.
@file:Suppress("MagicNumber")

package app.sleevesight.app.ui.logo

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The logo's colours, the product page's (`index.html`): the words' ink and the red of "sight"
 * (`--tile-ink`, `--sight`), the record's (`#lp`: its rim, and on dark a black core in place of the
 * groove, so the grey rim meets black), and the tile the intros D and R2 show (`--tile-bg`,
 * `--tile-edge`). The white tile has a faint edge, as the black one has on dark, since the light
 * surface it is drawn on is near white.
 */
@Immutable
internal data class LogoColors(
    val ink: Color,
    val sight: Color,
    val rim: Color,
    val dark: Boolean,
    val tile: Color,
    val tileEdge: Color,
) {
    companion object {
        val Light =
            LogoColors(
                ink = Color(0xFF141A22),
                sight = Color(0xFFCF222E),
                rim = Color(0xFF1F2328),
                dark = false,
                tile = Color.White,
                tileEdge = Color(0x26000000),
            )
        val Dark =
            LogoColors(
                ink = Color(0xFFE5E8EE),
                sight = Color(0xFFCF222E),
                rim = Color(0xFF8B949E),
                dark = true,
                tile = Color.Black,
                tileEdge = Color(0x26FFFFFF),
            )
    }
}

/** The words' outlines, parsed once ([LogoOutlines]). */
internal object LogoPaths {
    val sleeve: Path by lazy { PathParser().parsePathString(LogoOutlines.SLEEVE).toPath() }
    val sight: Path by lazy { PathParser().parsePathString(LogoOutlines.SIGHT).toPath() }
}

/** The record's centre in the stacked mark's units. */
internal val RecordCentre = Offset(LogoOutlines.RECORD_X, LogoOutlines.RECORD_Y)

/** How far "sight" and the record move in the one-line logo. */
internal val LineShift = Offset(LogoOutlines.LINE_DX, LogoOutlines.LINE_DY)

/**
 * The one-line logo's ink box in the stacked mark's units: from the left of the "S" and the top of
 * the "l" (and of the "h", level with it) to the right of the record and its foot (and the "g"'s).
 */
internal object LineBox {
    const val LEFT: Float = -37.59f
    const val TOP: Float = -37.26f
    val right: Float = LogoOutlines.RECORD_X + LogoOutlines.RECORD_R + LogoOutlines.LINE_DX
    val bottom: Float = LogoOutlines.RECORD_Y + LogoOutlines.RECORD_R + LogoOutlines.LINE_DY
    val width: Float get() = right - LEFT
    val height: Float get() = bottom - TOP
}

/** Draws "Sleeve" in ink, in units. */
internal fun DrawScope.drawSleeve(
    colors: LogoColors,
    alpha: Float = 1f,
) {
    if (alpha > 0f) drawPath(LogoPaths.sleeve, colors.ink, alpha)
}

/** Draws "sight" in red, in units. */
internal fun DrawScope.drawSight(
    colors: LogoColors,
    alpha: Float = 1f,
) {
    if (alpha > 0f) drawPath(LogoPaths.sight, colors.sight, alpha)
}

/**
 * Draws the record (the page's `#lp`) centred at [centre], [radius] units to its rim, turned by
 * [degrees] about its centre: the rim, the groove (on light) or the black core (on dark), the red
 * label, and its white face, an arc and a line about the centre hole, which show it turning.
 */
internal fun DrawScope.drawRecord(
    colors: LogoColors,
    centre: Offset,
    radius: Float = LogoOutlines.RECORD_R,
    degrees: Float = 0f,
    alpha: Float = 1f,
) {
    if (alpha <= 0f || radius <= 0f) return
    // The symbol's own units: 16 across, the rim at 7.5 from its centre (16, 16).
    val k = radius / RIM
    translate(centre.x, centre.y) {
        rotate(degrees, pivot = Offset.Zero) {
            scale(k, k, pivot = Offset.Zero) {
                drawCircle(colors.rim, RIM, Offset.Zero, alpha)
                if (colors.dark) {
                    drawCircle(CORE_COLOR, CORE, Offset.Zero, alpha)
                } else {
                    drawCircle(GROOVE_COLOR, GROOVE, Offset.Zero, alpha, Stroke(GROOVE_WIDTH))
                }
                drawCircle(LABEL_COLOR, LABEL, Offset.Zero, alpha)
                drawFace(1f, alpha)
            }
        }
    }
}

/**
 * Draws the record simplified for text size (#773; #785, #786: `TurningRecord`), centred at
 * [centre], [radius] to its rim, turned by [degrees]: the rim's colour as an outline only, everything
 * inside it the red label, with no groove and no black core, and the label's white face (the arc,
 * the short line and the centre hole) scaled up with it, from a label of 3 to one of 5.7 in the
 * symbol's 16 units.
 */
internal fun DrawScope.drawTextRecord(
    colors: LogoColors,
    centre: Offset,
    radius: Float,
    degrees: Float = 0f,
) {
    if (radius <= 0f) return
    val k = radius / RIM
    translate(centre.x, centre.y) {
        rotate(degrees, pivot = Offset.Zero) {
            scale(k, k, pivot = Offset.Zero) {
                drawCircle(colors.rim, RIM, Offset.Zero)
                drawCircle(LABEL_COLOR, TEXT_LABEL, Offset.Zero)
                drawFace(TEXT_LABEL / LABEL, 1f)
            }
        }
    }
}

// The label's white face about the centre, in the symbol's units, [grown] times the logo's.
private fun DrawScope.drawFace(
    grown: Float,
    alpha: Float,
) {
    val face = Stroke(FACE_WIDTH * grown, cap = StrokeCap.Round)
    val arc = ARC_R * grown
    // `M14.4 14.9 A2 2 0 0 1 17.6 14.9`: the arc of radius 2 about (16, 16.1), over the top.
    drawArc(
        Color.White,
        startAngle = ARC_START,
        sweepAngle = ARC_SWEEP,
        useCenter = false,
        topLeft = Offset(-arc, ARC_CY * grown - arc),
        size = Size(2 * arc, 2 * arc),
        alpha = alpha,
        style = face,
    )
    // `M15.1 18.05 H16.9`.
    drawLine(
        Color.White,
        Offset(-LINE_HALF * grown, LINE_Y * grown),
        Offset(LINE_HALF * grown, LINE_Y * grown),
        FACE_WIDTH * grown,
        StrokeCap.Round,
        alpha = alpha,
    )
    drawCircle(Color.White, HOLE * grown, Offset.Zero, alpha)
}

/** Draws the tile of D and R2: a rounded square 143 units across, centred at [centre]. */
internal fun DrawScope.drawTile(
    colors: LogoColors,
    centre: Offset,
    alpha: Float,
    edgeWidth: Float,
) {
    if (alpha <= 0f) return
    val topLeft = Offset(centre.x - TILE_HALF, centre.y - TILE_HALF)
    val size = Size(2 * TILE_HALF, 2 * TILE_HALF)
    drawRoundRect(colors.tile, topLeft, size, CornerRadius(TILE_CORNER), alpha = alpha)
    if (colors.tileEdge.alpha > 0f) {
        val corner = CornerRadius(TILE_CORNER)
        drawRoundRect(colors.tileEdge, topLeft, size, corner, alpha = alpha, style = Stroke(edgeWidth))
    }
}

private const val RIM = 7.5f
private const val CORE = 5.7f
private const val GROOVE = 5.4f
private const val GROOVE_WIDTH = .8f
private const val LABEL = 3f

// The label of the record at text size, which fills the rim's outline (#773).
private const val TEXT_LABEL = 5.7f
private const val HOLE = .9f
private const val FACE_WIDTH = .42f
private const val ARC_R = 2f
private const val ARC_CY = .1f
private const val ARC_START = -143.13f
private const val ARC_SWEEP = 106.26f
private const val LINE_HALF = .9f
private const val LINE_Y = 2.05f
private const val TILE_HALF = 71.5f
private const val TILE_CORNER = 31.5f
private val CORE_COLOR = Color.Black
private val GROOVE_COLOR = Color(0xFF57606A)
private val LABEL_COLOR = Color(0xFFCF222E)
