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
        // The arrows, as the notes, in the parts' order.
        assertEquals(GuidePart.entries.size, shown.arrows.size)
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
    fun theArrowsAreInThePartsOrderWhateverOrderThePartsAreDrawnIn() {
        // The player above the cogwheel: the arrows are still each at its own part.
        val swapped =
            parts + (GuidePart.SETTINGS to Rect(130f, 25f, 140f, 35f)) + (GuidePart.PLAYER to Rect(6f, 5f, 60f, 13f))
        val shown = guideLayout(swapped, GuidePart.entries.map { 20f }, pictureWidth, pictureHeight, metrics)
        for (part in GuidePart.entries) {
            val arrow = shown.arrows[part.ordinal]
            // The last note's, pushed below its part, ends at its bottom.
            val at = swapped.getValue(part).let { if (arrow.fromBelow) it.bottom + 2f else it.center.y }
            assertEquals(part.name, at, arrow.end.y, 0.01f)
        }
    }

    @Test
    fun theLastNotePushedBelowItsPartPointsUpIntoItsBottom() {
        // Tall notes push *Sources*' note below its part.
        val shown = laidOut(heights = 40f)
        val sources = shown.arrows[GuidePart.SOURCES.ordinal]
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

    // A note's panel (#673): clear of its part and its note, in the picture where it fits.

    @Test
    fun aPanelGoesUnderItsPartAndNoteWhenItFitsInThePicture() {
        // The cogwheel and its note, 0 to 20: under both.
        assertEquals(28f, panelTop(0f..20f, 100f, pictureHeight, 8f), 0.01f)
        assertTrue(panelFits(0f..20f, 100f, pictureHeight, 8f))
    }

    @Test
    fun aPanelGoesOverThemWhenItDoesNotFitUnder() {
        // The Write button and its note, 298 to 318: 120 does not fit under, in 400.
        assertEquals(298f - 8f - 120f, panelTop(298f..318f, 120f, pictureHeight, 8f), 0.01f)
        assertTrue(panelFits(298f..318f, 120f, pictureHeight, 8f))
    }

    @Test
    fun aPanelFittingNeitherGoesUnderThemBelowThePicture() {
        assertEquals(256f, panelTop(228f..248f, 300f, pictureHeight, 8f), 0.01f)
        assertFalse(panelFits(228f..248f, 300f, pictureHeight, 8f))
    }

    @Test
    fun aPanelIsThePicturesWidthUnlessThatIsTooNarrowAtTheFontSize() {
        assertEquals(metrics.pictureWidth(400), metrics.panelWidth(400))
        // At twice the font size the picture narrows, and the panel widens past it, within the guide.
        val large = GuideMetrics(density = 1f, fontScale = 2f)
        assertTrue(large.panelWidth(400) > large.pictureWidth(400))
        assertEquals(320, large.panelWidth(400))
        assertEquals(300, large.panelWidth(300))
    }
}
