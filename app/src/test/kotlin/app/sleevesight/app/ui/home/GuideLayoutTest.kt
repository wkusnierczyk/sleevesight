package app.sleevesight.app.ui.home

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the guide's notes and arrows go (#627), as the mock-up lays them out. */
class GuideLayoutTest {
    private val metrics = GuideMetrics(density = 1f, fontScale = 1f)
    private val pictureWidth = 150
    private val pictureHeight = 400

    // The parts at Home's places, top to bottom, at a picture 150 wide.
    private val parts =
        mapOf(
            GuidePart.SETTINGS to Rect(130f, 5f, 140f, 15f),
            GuidePart.PLAYER to Rect(6f, 25f, 60f, 33f),
            GuidePart.GENRES to Rect(6f, 230f, 70f, 245f),
            GuidePart.INSIGHT to Rect(6f, 265f, 144f, 290f),
            GuidePart.CITE to Rect(50f, 282f, 60f, 290f),
            GuidePart.WRITE to Rect(6f, 300f, 60f, 316f),
            GuidePart.SOURCES to Rect(6f, 325f, 100f, 360f),
        )

    private fun laidOut(heights: Float = 20f) =
        guideLayout(parts, GuidePart.entries.map { heights }, pictureWidth, pictureHeight, metrics)

    @Test
    fun notesRunTopToBottomInThePartsOrderNeverOverlapping() {
        val shown = laidOut()
        val tops = GuidePart.entries.map { shown.notes[it.ordinal].y }
        assertEquals(tops.sorted(), tops)
        tops.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b >= a + 20f) }
    }

    @Test
    fun aNoteSitsAtItsPartsHeightWhenThereIsRoom() {
        val shown = laidOut()
        val genres = shown.notes[GuidePart.GENRES.ordinal]
        assertEquals(parts.getValue(GuidePart.GENRES).center.y, genres.y + 10f, 0.01f)
        assertEquals(pictureWidth + metrics.gap, genres.x, 0.01f)
    }

    @Test
    fun eachArrowEndsJustInsideItsPartAtItsHeight() {
        val shown = laidOut()
        // The arrows run top to bottom, as the parts do here.
        val insight = shown.arrows[GuidePart.INSIGHT.ordinal]
        val part = parts.getValue(GuidePart.INSIGHT)
        assertEquals(part.center.y, insight.end.y, 0.01f)
        assertEquals(part.right - 6f, insight.end.x, 0.01f)
        // The cogwheel too, at the top.
        val settings = shown.arrows[GuidePart.SETTINGS.ordinal]
        assertEquals(parts.getValue(GuidePart.SETTINGS).right - 6f, settings.end.x, 0.01f)
        assertFalse(insight.fromBelow)
    }

    @Test
    fun theLastNotePushedBelowItsPartPointsUpIntoItsBottom() {
        // Tall notes push *Sources*' note below its part.
        val shown = laidOut(heights = 40f)
        val sources = shown.arrows.last()
        val part = parts.getValue(GuidePart.SOURCES)
        assertTrue(sources.fromBelow)
        assertEquals(part.center.x, sources.end.x, 0.01f)
        assertEquals(part.bottom + 2f, sources.end.y, 0.01f)
        // In under the picture's corner, below the picture.
        val note = shown.notes[GuidePart.SOURCES.ordinal]
        assertEquals(pictureWidth + metrics.gap - 40f, note.x, 0.01f)
        assertTrue(note.y >= pictureHeight + 8f)
        assertTrue(shown.height >= note.y + 40f)
    }
}
