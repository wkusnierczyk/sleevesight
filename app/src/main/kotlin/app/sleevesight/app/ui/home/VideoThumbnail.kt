package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

// A video source's thumbnail on Home (#1257): the picture without the black bars above and below it.

/**
 * [art] trimmed of its bars ([safeTrimBars]), worked out off the main thread once per bitmap; null
 * until then, while the cover shows [art] as it is at [untrimmedRatio], which for a 16:9 frame
 * letterboxed in a 4:3 thumbnail already looks as the trimmed one will.
 */
@Composable
internal fun trimmedThumbnail(art: Bitmap): Bitmap? {
    var trimmed by remember(art) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(art) { trimmed = withContext(Dispatchers.Default) { safeTrimBars(art) } }
    return trimmed
}

/**
 * Whether [turn]'s cover shows, or is turning to, its back: the album [CoverTurn.memory] keeps turned
 * over is [CoverTurn.album]. A video's cover is square then, not a strip (#1257).
 */
internal fun turnedOver(turn: CoverTurn): Boolean = turn.memory.backOf.value == turn.album.orEmpty()

/**
 * [trimBars], or [thumbnail] itself when its pixels cannot be read after all (recycled meanwhile, no
 * memory for a copy): the cover untrimmed then, never a crash.
 */
internal fun safeTrimBars(thumbnail: Bitmap): Bitmap = runCatching { trimBars(thumbnail) }.getOrDefault(thumbnail)

/**
 * [thumbnail] without the black bars above and below the picture, as YouTube sends a 16:9 frame
 * letterboxed in a 4:3 thumbnail; [thumbnail] itself when it has none. Only what looks like
 * letterboxing is trimmed: bars of the same height at the top and the bottom ([barHeight]), so a
 * dark sky at the top of a frame is kept, of a thumbnail taller than 16:9 ([LETTERBOXED_BELOW]),
 * leaving a picture of a video's own width to height ([VIDEO_RATIOS]), so a night scene dark at both
 * edges, or an all-black picture, is kept whole. [thumbnail] itself, too, when its pixels cannot be
 * read ([readablePixels]). The rows are read from a copy at most [SCAN_WIDTH] wide, so even an all-black
 * thumbnail, every row of which is read, costs little; the trimmed thumbnail is cut from a copy whose
 * pixels can be read (a hardware bitmap's cannot).
 */
internal fun trimBars(thumbnail: Bitmap): Bitmap {
    val readable =
        thumbnail.takeIf { it.width < it.height * LETTERBOXED_BELOW }?.let(::readablePixels) ?: return thumbnail
    val width = readable.width
    val height = readable.height
    val scan = if (width > SCAN_WIDTH) Bitmap.createScaledBitmap(readable, SCAN_WIDTH, height, true) else readable
    val row = IntArray(scan.width)
    val bar =
        barHeight(height) { y ->
            scan.getPixels(row, 0, scan.width, 0, y, scan.width, 1)
            isBlack(row)
        }
    if (scan !== readable) scan.recycle()
    val kept = height - 2 * bar
    return if (bar == 0 || kept <= 0 || !isVideoRatio(width.toFloat() / kept)) {
        if (readable !== thumbnail) readable.recycle()
        thumbnail
    } else {
        Bitmap.createBitmap(readable, 0, bar, width, kept)
    }
}

/**
 * [art] with pixels that can be read, or null: recycled, empty, or a [hardware] bitmap (whose pixels
 * cannot be read) with no room for a copy in memory, which is copied to one in memory otherwise. As
 * `ArtColors` reads the art.
 */
internal fun readablePixels(
    art: Bitmap,
    hardware: Boolean = art.config == Bitmap.Config.HARDWARE,
): Bitmap? =
    when {
        art.isRecycled || art.width <= 0 || art.height <= 0 -> null
        hardware -> art.copy(Bitmap.Config.ARGB_8888, false)
        else -> art
    }

/**
 * The height of the black bars above and below a picture [height] rows tall whose row y is black when
 * [blackRow] says so: the black rows at the top and at the bottom, as many as the fewer of the two, so
 * that only bars on both sides count; [height] / 2 at most.
 */
internal fun barHeight(
    height: Int,
    blackRow: (Int) -> Boolean,
): Int {
    val half = height / 2
    var top = 0
    while (top < half && blackRow(top)) top++
    var bottom = 0
    while (bottom < top && blackRow(height - 1 - bottom)) bottom++
    return bottom
}

/**
 * Whether [row]'s pixels are black: all but [NOISE] of them no brighter than [DARK] in any channel, so
 * a JPEG's specks in a bar do not make it a picture.
 */
private fun isBlack(row: IntArray): Boolean {
    val allowed = (row.size * NOISE).toInt()
    var bright = 0
    for (pixel in row) {
        val brightest = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        if (brightest > DARK && ++bright > allowed) return false
    }
    return true
}

/** The brightest a channel of a bar's pixel is, out of 255: black with a JPEG's noise. */
private const val DARK = 32

/** The share of a bar's pixels that may be brighter than [DARK]. */
private const val NOISE = 0.02f

/** The widest copy of a thumbnail whose rows are read for bars: enough to tell a bar from a picture. */
private const val SCAN_WIDTH = 64

/** Whether [ratio], a width to height, is within [RATIO_SLACK] of one of [VIDEO_RATIOS]. */
private fun isVideoRatio(ratio: Float): Boolean = VIDEO_RATIOS.any { abs(ratio / it - 1f) <= RATIO_SLACK }

/** The widths to heights of videos letterboxed in a thumbnail: 16:9, 1.85:1, 2:1 and 2.39:1. */
private val VIDEO_RATIOS = listOf(VIDEO_RATIO, FLAT_RATIO, UNIVISIUM_RATIO, SCOPE_RATIO)

/** Cinema's flat, 1.85:1. */
private const val FLAT_RATIO = 1.85f

/** 2:1, as some series are shot. */
private const val UNIVISIUM_RATIO = 2f

/** Cinema's scope, 2.39:1. */
private const val SCOPE_RATIO = 2.39f

/** How far a trimmed picture's width to height may be from a video's: a row or two of a JPEG's bar. */
private const val RATIO_SLACK = 0.04f

/** The widest a thumbnail can be, width to height, and still hold a letterboxed frame: under 16:9. */
private const val LETTERBOXED_BELOW = 1.7f

/** The widest a video source's cover is, width to height: a wider thumbnail is cropped to it. */
private const val MAX_RATIO = 3f

/**
 * The width to height of a video source's cover for [art] (trimmed, [trimBars]): its own, between
 * square and [MAX_RATIO]; 16:9 for no art, the placeholder's.
 */
internal fun videoCoverRatio(art: Bitmap?): Float =
    if (art == null || art.height <= 0) {
        VIDEO_RATIO
    } else {
        (art.width.toFloat() / art.height).coerceIn(1f, MAX_RATIO)
    }

/**
 * The width to height of a video source's cover while [art] is being trimmed ([trimmedThumbnail]):
 * 16:9 for a thumbnail that may hold a letterboxed frame (taller than 16:9, a 4:3 one), so the
 * frame shows as it will once trimmed; else its own ([videoCoverRatio]).
 */
internal fun untrimmedRatio(art: Bitmap): Float =
    if (art.width < art.height * LETTERBOXED_BELOW) VIDEO_RATIO else videoCoverRatio(art)

/** A video's width to height, 16:9: the placeholder's of a video source with no thumbnail. */
private const val VIDEO_RATIO = 16f / 9f
