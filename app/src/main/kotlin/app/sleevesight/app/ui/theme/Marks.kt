package app.sleevesight.app.ui.theme

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import app.sleevesight.core.settings.LinkStyle

// Where a link's mark goes (#442, #519): the geometry `MarkedText` in `Accents.kt` draws, worked
// out from the text's layout. No link is made here; `Accents.kt` makes them all.

/** The rectangles that mark a text's links, and the corners of a highlight's. */
internal class Marks(
    val rects: List<Rect>,
    val corner: CornerRadius,
) {
    companion object {
        val NONE = Marks(emptyList(), CornerRadius.Zero)
    }
}

/**
 * The marks of [links] in [layout], as [look] draws them: on each line of text a link spans, one
 * under (or behind) each stretch of it as drawn, so a link that mixes left-to-right and
 * right-to-left text is marked where its glyphs are and nowhere between them.
 */
internal fun marks(
    layout: TextLayoutResult,
    links: List<IntRange>,
    look: LinkStyle,
): Marks {
    val em = emOf(layout)
    val rects = mutableListOf<Rect>()
    for (link in links.filterNot { it.isEmpty() }) {
        for (line in layout.getLineForOffset(link.first)..layout.getLineForOffset(link.last)) {
            val from = maxOf(link.first, layout.getLineStart(line))
            val to = minOf(link.last, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (to < from) continue
            for (stretch in stretches(layout, from..to)) rects += mark(layout, line, stretch, em, look)
        }
    }
    val corner = if (look == LinkStyle.HIGHLIGHT) CornerRadius(em * HIGHLIGHT_CORNER) else CornerRadius.Zero
    return Marks(rects, corner)
}

/** The mark of one [stretch] (left and right) of a link on [line], as [look] draws it. */
private fun mark(
    layout: TextLayoutResult,
    line: Int,
    stretch: Pair<Float, Float>,
    em: Float,
    look: LinkStyle,
): Rect {
    val (left, right) = stretch
    val baseline = layout.getLineBaseline(line)
    return when (look) {
        // Inside the line's own box, should a tight line height leave less room below.
        LinkStyle.UNDERLINE -> {
            val thickness = maxOf(1f, em * UNDERLINE_THICKNESS)
            val top = minOf(baseline + em * UNDERLINE_GAP, layout.getLineBottom(line) - thickness)
            Rect(left, top, right, top + thickness)
        }
        // From above the capitals to below the descenders, a little wider than the glyphs, and
        // inside the line's box, so lines close together keep apart.
        LinkStyle.HIGHLIGHT -> {
            val pad = em * HIGHLIGHT_PADDING
            Rect(
                left - pad,
                maxOf(baseline - em * HIGHLIGHT_ABOVE, layout.getLineTop(line)),
                right + pad,
                minOf(baseline + em * HIGHLIGHT_BELOW, layout.getLineBottom(line)),
            )
        }
    }
}

/** The stretches, left to right, that the characters of [range] (on one line) are drawn over. */
private fun stretches(
    layout: TextLayoutResult,
    range: IntRange,
): List<Pair<Float, Float>> {
    val boxes = range.map { layout.getBoundingBox(it) }.filter { it.width > 0f }.sortedBy { it.left }
    val stretches = mutableListOf<Pair<Float, Float>>()
    for (box in boxes) {
        val last = stretches.lastOrNull()
        if (last != null && box.left <= last.second + TOUCHING) {
            stretches[stretches.lastIndex] = last.first to maxOf(last.second, box.right)
        } else {
            stretches += box.left to box.right
        }
    }
    return stretches
}

/** The text's em in pixels: its font size, which a style may leave out or give in ems. */
private fun emOf(layout: TextLayoutResult): Float =
    with(layout.layoutInput.density) {
        val size = layout.layoutInput.style.fontSize
        when {
            size.isSp -> size.toPx()
            size.isEm -> size.value * DEFAULT_FONT_SIZE.toPx()
            else -> DEFAULT_FONT_SIZE.toPx()
        }
    }

// The line's thickness and its distance below the baseline, in ems: about as thick as Roboto's
// own underline, and far enough down to leave a gap under g, p and y.
private const val UNDERLINE_THICKNESS = 0.05f
private const val UNDERLINE_GAP = 0.24f

// The highlight (#519), in ems: from the baseline up past the capitals and down past the
// descenders, a little wider than the glyphs, with rounded corners, as #442 rendered it beside the
// underline (Roboto, 16 sp: 20 dp tall over 13 dp of glyphs, 3 dp each side, 4 dp corners). Its
// fill, the text colour at 10 %, is `Accents.kt`'s `HIGHLIGHT_ALPHA`.
private const val HIGHLIGHT_ABOVE = 0.86f
private const val HIGHLIGHT_BELOW = 0.38f
private const val HIGHLIGHT_PADDING = 0.17f
private const val HIGHLIGHT_CORNER = 0.24f

// Compose's own default size, which an em is relative to when nothing sets one.
private val DEFAULT_FONT_SIZE = 14.sp

// Characters of one stretch touch, give or take rounding, in pixels.
private const val TOUCHING = 0.5f
