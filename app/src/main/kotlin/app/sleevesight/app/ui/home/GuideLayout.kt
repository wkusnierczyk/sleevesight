@file:Suppress("MagicNumber") // The mock-up's measures, in dp, as its `renderGuide` lays the guide out.

package app.sleevesight.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import app.sleevesight.app.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The parts of Home the guide's notes name (#627), top to bottom as Home shows them, each with its
 * [note]; its [label], the part's name, which a screen reader says before the note and the note's
 * panel has as its heading; and [about], the panel's fuller description (#673).
 */
internal enum class GuidePart(
    @StringRes val note: Int,
    @StringRes val label: Int,
    @StringRes val about: Int,
) {
    SETTINGS(R.string.home_guide_note_settings, R.string.home_guide_part_settings, R.string.home_guide_about_settings),
    PLAYER(R.string.home_guide_note_player, R.string.home_guide_part_player, R.string.home_guide_about_player),
    GENRES(R.string.home_guide_note_genres, R.string.home_guide_part_genres, R.string.home_guide_about_genres),
    INSIGHT(R.string.home_guide_note_insight, R.string.home_guide_part_insight, R.string.home_guide_about_insight),
    CITE(R.string.home_guide_note_cite, R.string.home_guide_part_cite, R.string.home_guide_about_cite),
    WRITE(R.string.home_guide_note_write, R.string.home_guide_part_write, R.string.home_guide_about_write),
    SOURCES(R.string.home_guide_note_sources, R.string.home_guide_part_sources, R.string.home_guide_about_sources),
}

/**
 * The guide's sizes in pixels (#627), from the mock-up's, in dp: the [gap] between the picture and
 * the notes, [firstLine] the middle of a note's first line, which follows the font size, and the
 * rest as [guideLayout] uses them.
 */
internal data class GuideMetrics(
    val density: Float,
    val fontScale: Float,
) {
    private fun dp(value: Float) = value * density

    val gap: Float get() = dp(GAP_DP)
    val firstLine: Float get() = dp(FIRST_LINE_DP) * fontScale
    val unit: Float get() = dp(1f)

    /**
     * The picture's width in a guide [width] wide: a share of it, smaller as the font grows, so the
     * notes keep room, and never wider than [MAX_PICTURE_DP].
     */
    fun pictureWidth(width: Int): Int {
        val share = PICTURE_SHARE / sqrt(fontScale.coerceAtLeast(1f))
        return min(width * share, dp(MAX_PICTURE_DP)).roundToInt()
    }

    /**
     * A note's panel's width in a guide [width] wide (#673): the picture's, over its phone screen,
     * unless that is narrower than [MIN_PANEL_DP] at the font size, as at a large font, where the
     * picture narrows as the text grows: then wider, over the notes too, never wider than the guide.
     */
    fun panelWidth(width: Int): Int =
        max(pictureWidth(width), min(width.toFloat(), dp(MIN_PANEL_DP) * fontScale).roundToInt())

    /** The space kept between a note's panel and the part and note it is about. */
    val panelGap: Float get() = dp(PANEL_GAP_DP)

    companion object {
        fun of(density: Density): GuideMetrics = GuideMetrics(density.density, density.fontScale)
    }
}

/** Where the notes go, the arrows from them, both in [GuidePart] order, and the guide's [height] (#627). */
internal data class GuideShown(
    val notes: List<Offset>,
    val arrows: List<GuideArrow>,
    val height: Float,
)

/**
 * An arrow from a note ([start]) to its part ([end]): a slightly bent curve through [bend]; or, for
 * the last note pushed below its part ([fromBelow]), a curve out from the note and up into the part,
 * through [bend] and [bend2]. Its head is the two lines from [head] and [head2] to [end].
 */
internal data class GuideArrow(
    val start: Offset,
    val bend: Offset,
    val end: Offset,
    val head: Offset,
    val head2: Offset,
    val fromBelow: Boolean = false,
    val bend2: Offset = Offset.Zero,
)

/**
 * The notes and arrows (#627) for [parts], each part's bounds in a picture [pictureWidth] by
 * [pictureHeight], with notes [noteHeights] high, all in [GuidePart] order, as the mock-up lays them
 * out: each note on the right at the height of its part's middle, pushed below the note above when
 * they would overlap, so notes and parts run in the same order and no two arrows cross. The last
 * note, pushed below its part, goes a little lower and to the left, under the picture's corner, and
 * points up into the part's bottom. The others' arrows run from the note's first line to just inside
 * the part's right edge, at its middle, slightly bent.
 */
@Suppress("LongMethod") // One pass over the notes, then one over the arrows, as the mock-up draws them.
internal fun guideLayout(
    parts: Map<GuidePart, Rect>,
    noteHeights: List<Float>,
    pictureWidth: Int,
    pictureHeight: Int,
    metrics: GuideMetrics,
): GuideShown {
    val u = metrics.unit
    val order = GuidePart.entries.sortedBy { parts.getValue(it).center.y }
    val last = order.last()
    val notes = arrayOfNulls<Offset>(GuidePart.entries.size)
    var floor = -4 * u
    var bottom = pictureHeight.toFloat()
    for (part in order) {
        val rect = parts.getValue(part)
        val h = noteHeights[part.ordinal]
        var top = max(floor, rect.center.y - h / 2)
        var left = pictureWidth + metrics.gap
        if (part == last && top + metrics.firstLine > rect.bottom) {
            top = max(top + 14 * u, pictureHeight + 8 * u)
            left -= 40 * u
        }
        notes[part.ordinal] = Offset(left, top)
        floor = top + h + 5 * u
        bottom = max(bottom, top + h)
    }
    val arrows =
        GuidePart.entries.map { part ->
            val rect = parts.getValue(part)
            val note = notes[part.ordinal]!!
            val h = noteHeights[part.ordinal]
            val start = Offset(note.x - 2 * u, note.y + min(h / 2, metrics.firstLine))
            val seed = part.ordinal
            val a = 6 * u
            if (part == last && start.y > rect.bottom) {
                // Out to the left, under the note's line, then straight up into the part's bottom.
                val end = Offset(rect.center.x, rect.bottom + 2 * u)
                GuideArrow(
                    start = start,
                    bend = Offset(start.x - (start.x - end.x) * 0.5f, start.y + 6 * u),
                    bend2 = Offset(end.x, start.y + 6 * u),
                    end = end,
                    head = Offset(end.x - a * 0.6f, end.y + a),
                    head2 = Offset(end.x + a * 0.6f, end.y + a),
                    fromBelow = true,
                )
            } else {
                val end = Offset(min(rect.right - 6 * u, pictureWidth - 6 * u), rect.center.y)
                val dx = end.x - start.x
                val dy = end.y - start.y
                // A slight, even bend, a little different for each, so the arrows look drawn.
                val bend = wobble(seed + 1) * 0.15f + 0.2f
                val through =
                    Offset(
                        start.x + dx / 2 - dy * 0.25f * bend + (wobble(seed + 7) - 0.5f) * 6 * u,
                        start.y + dy / 2 - dx * 0.25f * bend + (wobble(seed + 3) - 0.5f) * 6 * u,
                    )
                val angle = atan2(end.y - through.y, end.x - through.x)
                GuideArrow(
                    start = start,
                    bend = through,
                    end = end,
                    head = Offset(end.x - a * cos(angle - HEAD_SPREAD), end.y - a * sin(angle - HEAD_SPREAD)),
                    head2 = Offset(end.x - a * cos(angle + HEAD_SPREAD), end.y - a * sin(angle + HEAD_SPREAD)),
                )
            }
        }
    return GuideShown(notes.map { it!! }, arrows, bottom + 6 * u)
}

/**
 * Where a note's panel, [height] high, goes in a picture [pictureHeight] high (#673): clear of the
 * [band] from the top to the bottom of the part and its note, so the outlined part, the note and the
 * arrow between them stay in sight, [gap] from it; under it if it fits in the picture, else over it
 * if it fits there, else under it, below the picture if need be, where the guide grows to hold it.
 * Its top.
 */
internal fun panelTop(
    band: ClosedFloatingPointRange<Float>,
    height: Float,
    pictureHeight: Int,
    gap: Float,
): Float {
    val below = band.endInclusive + gap
    val above = band.start - gap - height
    return when {
        below + height <= pictureHeight -> below
        above >= 0f -> above
        else -> below
    }
}

/** Whether a note's panel, [height] high, fits in the picture under or over the [band] ([panelTop]). */
internal fun panelFits(
    band: ClosedFloatingPointRange<Float>,
    height: Float,
    pictureHeight: Int,
    gap: Float,
): Boolean = band.endInclusive + gap + height <= pictureHeight || band.start - gap - height >= 0f

/** A seeded wobble in [0, 1), the mock-up's, so the arrows look drawn and stay put between draws. */
private fun wobble(seed: Int): Float {
    val x = sin(seed * 999.0) * 10_000
    return (x - floor(x)).toFloat()
}

internal fun DrawScope.drawArrow(
    arrow: GuideArrow,
    pen: Color,
    stroke: Stroke,
) {
    val line =
        Path().apply {
            moveTo(arrow.start.x, arrow.start.y)
            if (arrow.fromBelow) {
                cubicTo(arrow.bend.x, arrow.bend.y, arrow.bend2.x, arrow.bend2.y, arrow.end.x, arrow.end.y)
            } else {
                quadraticTo(arrow.bend.x, arrow.bend.y, arrow.end.x, arrow.end.y)
            }
        }
    drawPath(line, pen, style = stroke)
    val head =
        Path().apply {
            moveTo(arrow.head.x, arrow.head.y)
            lineTo(arrow.end.x, arrow.end.y)
            lineTo(arrow.head2.x, arrow.head2.y)
        }
    drawPath(head, pen, style = stroke)
}

/** The picture's share of the guide's width, at the default font size; and its widest, in dp. */
private const val PICTURE_SHARE = 0.42f
private const val MAX_PICTURE_DP = 200f
private const val GAP_DP = 14f
private const val FIRST_LINE_DP = 12f

/** A note's panel's narrowest, in dp at the default font size, and its distance from the part. */
private const val MIN_PANEL_DP = 160f
private const val PANEL_GAP_DP = 8f

/** Half the angle between the arrowhead's two lines, in radians. */
private const val HEAD_SPREAD = 0.5f
