package app.sleevesight.app.ui.scan

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the scan's frame sits in the camera's picture (#446, #541). */
class ScanFrameTest {
    @Test
    fun theFrameIsALargeSquareCentredInThePictureWithNarrowMarginsAroundIt() {
        // A portrait phone's picture: 720 by 1280 frames turned upright.
        val picture = Size(720f, 1280f)
        val frame = scanFrame(picture)
        assertEquals(frame.width, frame.height, 0.01f)
        assertEquals(picture.width / 2, frame.center.x, 0.01f)
        assertEquals(picture.height / 2, frame.center.y, 0.01f)
        // The dimmed margin at each side of the shorter dimension is at most a twentieth of it.
        assertTrue("margin ${frame.left}", frame.left <= picture.width / 20 + 0.01f)
        assertTrue("margin ${frame.left}", frame.left > 0f)
    }

    @Test
    fun aLandscapePictureTakesItsHeightAsTheShorterSide() {
        val frame = scanFrame(Size(1280f, 720f))
        assertTrue("margin ${frame.top}", frame.top <= 720f / 20 + 0.01f)
        assertEquals(640f, frame.center.x, 0.01f)
    }
}
