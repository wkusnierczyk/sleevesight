package app.sleevesight.app.keyboard

import app.sleevesight.app.ui.scan.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

/** The page's own QR encoder (`assets/keyboard/qr.js`), read by the app's decoder. */
class KeyboardQrTest {
    @Test
    fun `the page's code of a session reads back as the session's code`() {
        val modules = KeyboardVectors.qr
        val quiet = 4
        val scale = 8
        val side = (modules.size + 2 * quiet) * scale
        val luminance =
            ByteArray(side * side) { i ->
                val x = i % side / scale - quiet
                val y = i / side / scale - quiet
                val dark = x in modules.indices && y in modules.indices && modules[y][x]
                if (dark) 0 else -1
            }
        assertEquals(KeyboardVectors.code, QrDecoder.decode(luminance, side, side, side))
    }
}
