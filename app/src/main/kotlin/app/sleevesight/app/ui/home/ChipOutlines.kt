package app.sleevesight.app.ui.home

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus

// The citations of sentences their lines do not back (#1036): their chips drawn as a dim outline,
// in the insight's prose (`Citations.kt`) and as the lines' tags in *Sources* (`SourcesSection.kt`).

/**
 * The ids of the lines [grounded] cites only from sentences they do not back (#1036,
 * `SentenceStatus.UNSUPPORTED`): a line that backs another of its sentences is not one.
 */
internal fun unsupportedLines(grounded: GroundedText): Set<String> {
    val sentences = grounded.paragraphs.flatten()
    val backing =
        sentences.filter { it.status == SentenceStatus.OK || it.status == SentenceStatus.MODEL }.flatMap { it.cites }
    return sentences.filter { it.status == SentenceStatus.UNSUPPORTED }.flatMap { it.cites }.toSet() - backing.toSet()
}

// Where [text]'s outlined chips are ([OUTLINE_TAG]), as character ranges.
internal fun outlinedChips(text: AnnotatedString): List<IntRange> =
    text.getStringAnnotations(OUTLINE_TAG, 0, text.length).map { it.start until it.end }

/**
 * A thin outline in [ink] around each of [chips] in the text [layout] holds (#1036), one for each
 * line a chip spans, from the line's top to its bottom, as a chip's fill is drawn, round its id and
 * the spaces either side.
 */
internal fun Modifier.drawChipOutlines(
    chips: List<IntRange>,
    layout: MutableState<TextLayoutResult?>,
    ink: Color,
): Modifier =
    if (chips.isEmpty()) {
        this
    } else {
        drawWithCache {
            // Read here, in the draw phase, the layout is the one this frame measured.
            val rects = layout.value?.let { chipRects(it, chips) }.orEmpty()
            val stroke = CHIP_OUTLINE.toPx()
            val corner = CornerRadius(CHIP_CORNER.toPx())
            onDrawBehind {
                for (rect in rects) {
                    val inset = rect.deflate(stroke / 2)
                    drawRoundRect(ink, inset.topLeft, inset.size, corner, style = Stroke(stroke))
                }
            }
        }
    }

// The rectangle of each line each of [chips] spans in [layout], across its characters drawn there.
private fun chipRects(
    layout: TextLayoutResult,
    chips: List<IntRange>,
): List<Rect> =
    chips.filter { !it.isEmpty() && it.last < layout.layoutInput.text.length }.flatMap { chip ->
        chip
            .map { offset -> layout.getLineForOffset(offset) to layout.getBoundingBox(offset) }
            .filter { (_, box) -> box.width > 0f }
            .groupBy({ it.first }, { it.second })
            .map { (line, boxes) ->
                val left = boxes.minOf { it.left }
                Rect(left, layout.getLineTop(line), boxes.maxOf { it.right }, layout.getLineBottom(line))
            }
    }

private val CHIP_OUTLINE = 1.dp
private val CHIP_CORNER = 2.dp
