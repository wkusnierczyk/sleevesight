package app.sleevesight.app.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** The guide's picture full screen (#673): pinch, double tap and drag, the picture kept in sight. */
class GuideZoomTest {
    // A box 400 by 800, the picture fitted in it 360 by 760.
    private fun zoom() = GuideZoom().apply { bounds(Size(400f, 800f), Size(360f, 760f)) }

    private fun assertOffset(
        x: Float,
        y: Float,
        actual: Offset,
    ) {
        assertEquals(x, actual.x, 0.01f)
        assertEquals(y, actual.y, 0.01f)
    }

    @Test
    fun fittedThePictureIsCentredInTheBox() {
        val zoom = zoom()
        assertEquals(1f, zoom.scale)
        assertOffset(20f, 20f, zoom.offset)
    }

    @Test
    fun aDoubleTapMagnifiesAboutThePointTappedAndASecondFitsItBack() {
        val zoom = zoom()
        val at = Offset(200f, 400f)
        // The picture's point under the tap, before.
        val under = (at - zoom.offset) / zoom.scale
        zoom.toggle(at)
        assertEquals(GuideZoom.DOUBLE_TAP_SCALE, zoom.scale)
        val after = (at - zoom.offset) / zoom.scale
        assertOffset(under.x, under.y, after)
        zoom.toggle(at)
        assertEquals(1f, zoom.scale)
        assertOffset(20f, 20f, zoom.offset)
    }

    @Test
    fun aPinchMagnifiesNoLessThanFittedAndNoMoreThanTheMost() {
        val zoom = zoom()
        zoom.transform(Offset(200f, 400f), Offset.Zero, 0.5f)
        assertEquals(1f, zoom.scale)
        zoom.transform(Offset(200f, 400f), Offset.Zero, 100f)
        assertEquals(GuideZoom.MAX_SCALE, zoom.scale)
    }

    @Test
    fun aDragMovesTheMagnifiedPictureButNotPastItsEdges() {
        val zoom = zoom()
        zoom.toggle(Offset(200f, 400f))
        val before = zoom.offset
        zoom.transform(Offset(200f, 400f), Offset(-30f, 40f), 1f)
        assertOffset(before.x - 30f, before.y + 40f, zoom.offset)
        // Far to the right and down: its left and top edges stop at the box's.
        zoom.transform(Offset(200f, 400f), Offset(5_000f, 5_000f), 1f)
        assertOffset(0f, 0f, zoom.offset)
        // Far to the left and up: its right and bottom edges stop at the box's.
        zoom.transform(Offset(200f, 400f), Offset(-5_000f, -5_000f), 1f)
        assertOffset(400f - 360f * zoom.scale, 800f - 760f * zoom.scale, zoom.offset)
    }

    @Test
    fun fittedADragDoesNotMoveIt() {
        val zoom = zoom()
        zoom.transform(Offset(200f, 400f), Offset(50f, -70f), 1f)
        assertOffset(20f, 20f, zoom.offset)
    }
}
