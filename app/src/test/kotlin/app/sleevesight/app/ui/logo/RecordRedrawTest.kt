package app.sleevesight.app.ui.logo

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * How the header logo's record asks for a frame after a step of its turn ([redraw], #1326), on
 * Android 8.0 (the app's oldest) and the tests' current one: drawn in hardware, its view's parents
 * draw again and the view keeps its drawing; drawn in software (not hardware accelerated, or a
 * software layer), the view itself is invalidated, or its kept bitmap would show the record still.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class RecordRedrawTest {
    private val context = getApplicationContext<Context>()

    /** A parent that counts its descendants' invalidations. */
    private class Parent(
        context: Context,
    ) : FrameLayout(context) {
        var told = 0

        override fun onDescendantInvalidated(
            child: View,
            target: View,
        ) {
            told++
            super.onDescendantInvalidated(child, target)
        }
    }

    /** A view in [Parent], hardware accelerated or not as [hardware] says, with [layer]. */
    private fun viewIn(
        parent: Parent,
        hardware: Boolean,
        layer: Int = View.LAYER_TYPE_NONE,
    ): View {
        val view =
            object : View(context) {
                override fun isHardwareAccelerated(): Boolean = hardware
            }
        parent.addView(view)
        view.setLayerType(layer, null)
        shadowOf(view).clearWasInvalidated()
        return view
    }

    @Test
    fun inHardwareTheParentsDrawAgainAndTheViewKeepsItsDrawing() {
        val parent = Parent(context)
        val view = viewIn(parent, hardware = true)
        redraw(view)
        assertEquals("the parent told", 1, parent.told)
        assertFalse("the view invalidated", shadowOf(view).wasInvalidated())
    }

    @Test
    fun withAHardwareLayerTooTheViewKeepsItsDrawing() {
        val parent = Parent(context)
        val view = viewIn(parent, hardware = true, layer = View.LAYER_TYPE_HARDWARE)
        redraw(view)
        assertEquals("the parent told", 1, parent.told)
        assertFalse("the view invalidated", shadowOf(view).wasInvalidated())
    }

    @Test
    fun withASoftwareLayerTheViewIsInvalidated() {
        val view = viewIn(Parent(context), hardware = true, layer = View.LAYER_TYPE_SOFTWARE)
        redraw(view)
        assertTrue("the view invalidated", shadowOf(view).wasInvalidated())
    }

    @Test
    fun drawnInSoftwareTheViewIsInvalidated() {
        val view = viewIn(Parent(context), hardware = false)
        redraw(view)
        assertTrue("the view invalidated", shadowOf(view).wasInvalidated())
    }
}
