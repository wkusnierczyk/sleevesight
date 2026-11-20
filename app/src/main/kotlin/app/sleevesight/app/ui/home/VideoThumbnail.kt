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

// A video source's thumbnail on Home (#1257): the picture without the black bars above and below it.

/**
 * [art] trimmed of its bars ([safeTrimBars]), worked out off the main thread once per bitmap; null
 * until then, while the cover shows [art] as it is, cropped to 16:9, which for a 16:9 frame
 * letterboxed in a 4:3 thumbnail already looks as the trimmed one will.
 */
@Composable
internal fun trimmedThumbnail(art: Bitmap): Bitmap? {
    var trimmed by remember(art) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(art) { trimmed = withContext(Dispatchers.Default) { safeTrimBars(art) } }
    return trimmed
}

/**
 * [trimBars], or [thumbnail] itself when its pixels cannot be read after all (recycled meanwhile, no
 * memory for a copy): the cover untrimmed then, never a crash.
 */
internal fun safeTrimBars(thumbnail: Bitmap): Bitmap = runCatching { trimBars(thumbnail) }.getOrDefault(thumbnail)

/**
 * [thumbnail] without the black bars above and below the picture, as YouTube sends a 16:9 frame
 * letterboxed in a 4:3 thumbnail; [thumbnail] itself when it has none. Only bars of the same height
 * at the top and the bottom are trimmed ([barHeight]), so a dark sky at the top of a frame without
 * bars is kept. [thumbnail] itself, too, when trimming would leave less than [MIN_KEPT] of its height
 * or a strip wider than [MAX_RATIO] (an all-black or nearly black picture), or when its pixels cannot
 * be read ([readablePixels]). The rows are read from a copy at most [SCAN_WIDTH] wide, so even an all-black
 * thumbnail, every row of which is read, costs little; the trimmed thumbnail is cut from a copy whose
 * pixels can be read (a hardware bitmap's cannot).
 */
internal fun trimBars(thumbnail: Bitmap): Bitmap {
    val readable = readablePixels(thumbnail) ?: return thumbnail
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
    return if (bar == 0 || kept < height * MIN_KEPT || width > kept * MAX_RATIO) {
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

/** The least share of a thumbnail's height that trimming keeps. */
private const val MIN_KEPT = 0.4f

/** The widest a trimmed thumbnail may be, width to height, or it is kept as it is. */
private const val MAX_RATIO = 3f

/**
 * The width to height of a video source's cover for [art] (trimmed, [trimBars]): its own, between
 * square and [MAX_RATIO]; 16:9 for no art, the placeholder's and the untrimmed thumbnail's while it is
 * being trimmed.
 */
internal fun videoCoverRatio(art: Bitmap?): Float =
    if (art == null || art.height <= 0) {
        VIDEO_RATIO
    } else {
        (art.width.toFloat() / art.height).coerceIn(1f, MAX_RATIO)
    }

/** A video's width to height, 16:9: the placeholder's of a video source with no thumbnail. */
private const val VIDEO_RATIO = 16f / 9f
