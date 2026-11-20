package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A video source's thumbnail without its black bars (#1257, [trimBars]): a 16:9 frame letterboxed in
 * a 4:3 thumbnail comes out 16:9; a thumbnail without bars, one black all over, and one dark only at
 * the top come out as they are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoThumbnailTest {
    // A [width] by [height] thumbnail, black, with a grey picture between [bar] rows at the top and
    // [bottom] at the bottom.
    private fun thumbnail(
        bar: Int,
        bottom: Int = bar,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLACK)
            val grey = Paint().apply { color = GREY }
            canvas.drawRect(0f, bar.toFloat(), width.toFloat(), (height - bottom).toFloat(), grey)
        }

    @Test
    fun aFrameLetterboxedInAFourByThreeThumbnailComesOutSixteenByNine() {
        val trimmed = trimBars(thumbnail(bar = 45))
        assertEquals(WIDTH, trimmed.width)
        assertEquals(270, trimmed.height)
        assertEquals(16f / 9f, videoCoverRatio(trimmed), 0.001f)
    }

    @Test
    fun aThumbnailWithoutBarsIsKeptAsItIs() {
        val whole = thumbnail(bar = 0)
        assertSame(whole, trimBars(whole))
    }

    @Test
    fun anAllBlackThumbnailIsKeptAsItIs() {
        val black = thumbnail(bar = HEIGHT / 2 + 1)
        assertSame(black, trimBars(black))
    }

    @Test
    fun aFrameDarkOnlyAtTheTopIsKeptAsItIs() {
        // A night sky, not a bar: no black rows at the bottom to match it.
        val night = thumbnail(bar = 60, bottom = 0)
        assertSame(night, trimBars(night))
    }

    @Test
    fun unevenBarsAreTrimmedByTheShorterOfTheTwo() {
        val trimmed = trimBars(thumbnail(bar = 50, bottom = 45))
        assertEquals(HEIGHT - 90, trimmed.height)
    }

    @Test
    fun barsLeavingTooThinAStripAreKept() {
        // A thin line of picture in the middle: 30 of 360 rows, wider than the widest strip.
        val thin = thumbnail(bar = 165)
        assertSame(thin, trimBars(thin))
    }

    @Test
    fun theBarHeightIsTheShorterOfTheBlackRowsAtTheTopAndAtTheBottom() {
        assertEquals(3, barHeight(20) { it < 5 || it >= 17 })
        assertEquals(0, barHeight(20) { it < 5 })
        assertEquals(10, barHeight(20) { true })
    }

    @Test
    fun theCoverIsSixteenByNineWithNoArtAndNeverNarrowerThanSquare() {
        assertEquals(16f / 9f, videoCoverRatio(null), 0.001f)
        // A Short's upright thumbnail: cropped square, as an album's cover.
        assertEquals(1f, videoCoverRatio(thumbnail(bar = 0, width = 90, height = 160)), 0.001f)
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 360
        val GREY = Color.rgb(120, 130, 140)
    }
}
