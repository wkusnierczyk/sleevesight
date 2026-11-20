package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * A video source's thumbnail as a hardware bitmap (#1257), on Android: its pixels cannot be read
 * (`getPixels` throws), so the bars are found and cut from a copy in memory. Robolectric makes no
 * hardware bitmap, so only here is the real conversion tried; the cropped picture's size and its
 * first row's colour show it was trimmed, not shown untrimmed as when the pixels cannot be read.
 */
class HardwareThumbnailTrimTest {
    // A 4:3 thumbnail with a 16:9 frame letterboxed in black: grey, its first row red.
    private fun letterboxed(): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLACK)
            val paint = Paint()
            paint.color = GREY
            canvas.drawRect(0f, BAR.toFloat(), WIDTH.toFloat(), (HEIGHT - BAR).toFloat(), paint)
            paint.color = RED
            canvas.drawRect(0f, BAR.toFloat(), WIDTH.toFloat(), (BAR + 1).toFloat(), paint)
        }

    @Test
    fun aHardwareThumbnailIsTrimmedOfItsBars() {
        val hardware = checkNotNull(letterboxed().copy(Bitmap.Config.HARDWARE, false)) { "No hardware bitmap was made" }
        assertEquals(Bitmap.Config.HARDWARE, hardware.config)

        val trimmed = safeTrimBars(hardware)

        assertNotSame(hardware, trimmed)
        assertEquals(WIDTH, trimmed.width)
        assertEquals(HEIGHT - 2 * BAR, trimmed.height)
        // Cut from the copy in memory; read through a copy all the same, should it come back hardware.
        val readable = trimmed.copy(Bitmap.Config.ARGB_8888, false)
        assertEquals(RED, readable.getPixel(WIDTH / 2, 0))
        assertEquals(GREY, readable.getPixel(WIDTH / 2, readable.height / 2))
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 360
        const val BAR = 45
        val GREY = Color.rgb(120, 130, 140)
        val RED = Color.rgb(200, 30, 30)
    }
}
