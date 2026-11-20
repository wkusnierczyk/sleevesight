package app.sleevesight.app.ui.settings

import app.sleevesight.core.settings.SettingsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** *Load from a file* (#815) reads a file whole up to [SettingsFile.MAX_BYTES], and no more. */
class ReadAtMostTest {
    private fun bytes(n: Int) = ByteArray(n) { 'a'.code.toByte() }

    @Test
    fun aFileOfTheMostBytesIsReadWhole() {
        val read = readAtMost(ByteArrayInputStream(bytes(SettingsFile.MAX_BYTES)), SettingsFile.MAX_BYTES)
        assertEquals(SettingsFile.MAX_BYTES, read.size)
    }

    @Test
    fun aFileOneByteLongerIsNotRead() {
        assertThrows(IOException::class.java) {
            readAtMost(ByteArrayInputStream(bytes(SettingsFile.MAX_BYTES + 1)), SettingsFile.MAX_BYTES)
        }
    }
}
