package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The now-playing screen's colours from the art. */
@RunWith(RobolectricTestRunner::class)
class ArtColorsTest {
    private fun art(color: Int) = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun theSeedIsTheArtsColour() {
        assertEquals(Color.RED, artSeed(art(Color.RED)))
    }

    @Test
    fun differentArtGivesDifferentSchemesLightAndDark() {
        val red = artScheme(art(Color.RED), dark = false)
        val blue = artScheme(art(Color.BLUE), dark = false)
        assertNotNull(red)
        assertNotEquals(red?.primary, blue?.primary)
        assertNotEquals(red?.surface, artScheme(art(Color.RED), dark = true)?.surface)
    }

    @Test
    fun artThatCannotBeReadFallsBackToTheAppsColours() {
        val recycled = art(Color.RED).apply { recycle() }
        assertNull(artSeed(recycled))
        assertNull(safeArtScheme(recycled, dark = false))
    }
}
