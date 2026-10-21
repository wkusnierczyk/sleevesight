package app.sleevesight.app.ui.scan

import android.view.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountingTest {
    @Test
    fun `a phone's back camera, turned a quarter, swaps sides upright and not on its side`() {
        val back = Mounting(sensorOrientation = 90, frontFacing = false)
        assertTrue(back.swapsSides(Surface.ROTATION_0))
        assertFalse(back.swapsSides(Surface.ROTATION_90))
        assertTrue(back.swapsSides(Surface.ROTATION_180))
        assertFalse(back.swapsSides(Surface.ROTATION_270))
    }

    @Test
    fun `a front camera at 270 degrees swaps sides upright too`() {
        val front = Mounting(sensorOrientation = 270, frontFacing = true)
        assertTrue(front.swapsSides(Surface.ROTATION_0))
        assertFalse(front.swapsSides(Surface.ROTATION_90))
    }

    @Test
    fun `a tablet whose sensor sits as its natural landscape swaps sides only when turned`() {
        val tablet = Mounting(sensorOrientation = 0, frontFacing = false)
        assertFalse(tablet.swapsSides(Surface.ROTATION_0))
        assertTrue(tablet.swapsSides(Surface.ROTATION_90))
    }
}
