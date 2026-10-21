package app.sleevesight.app.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    private val text = "sleevesight-keys:v1\nlastfm=0123456789abcdef0123456789abcdef"

    /**
     * [text] as a camera frame's luminance plane: [size] pixels square, black on white, or white on
     * black when [inverted], each row padded to [rowStride] bytes as a camera's often are.
     */
    private fun frame(
        size: Int,
        rowStride: Int,
        inverted: Boolean = false,
    ): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val dark: Byte = if (inverted) -1 else 0
        val light: Byte = if (inverted) 0 else -1
        return ByteArray(rowStride * size) { i ->
            val x = i % rowStride
            val y = i / rowStride
            if (x < size && matrix[x, y]) dark else light
        }
    }

    @Test
    fun `a frame's code is read, whatever its row padding`() {
        assertEquals(text, QrDecoder.decode(frame(400, 448), 400, 400, 448))
        assertEquals(text, QrDecoder.decode(frame(400, 400), 400, 400, 400))
    }

    @Test
    fun `a code drawn light on dark is read too`() {
        assertEquals(text, QrDecoder.decode(frame(400, 400, inverted = true), 400, 400, 400))
    }

    @Test
    fun `a frame with no code reads as nothing`() {
        assertNull(QrDecoder.decode(ByteArray(400 * 400) { -1 }, 400, 400, 400))
    }
}
