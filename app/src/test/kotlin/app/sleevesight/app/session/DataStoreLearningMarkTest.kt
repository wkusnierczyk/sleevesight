package app.sleevesight.app.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Skip detection's reset mark on a file, through the store the app opens it with ([DataStoreLearningMark.open]). */
class DataStoreLearningMarkTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun file() = File(dir.root, "skip_detection.preferences_pb")

    @Test
    fun noFileIsNoReset() {
        assertEquals(0L, runBlocking { DataStoreLearningMark.open(::file).read() })
    }

    @Test
    fun aWrittenMarkIsRead() {
        val mark = DataStoreLearningMark.open(::file)
        runBlocking { mark.write(42) }
        assertEquals(42L, runBlocking { mark.read() })
    }

    @Test
    fun aCorruptFileCannotBeReadUntilAMarkIsWritten() {
        // Not a preferences file: an invalid wire type at the first byte.
        file().writeText("not a preferences file")
        val mark = DataStoreLearningMark.open(::file)

        assertThrows(CorruptMarkException::class.java) { runBlocking { mark.read() } }
        // Still refused on a second read, rather than read as no reset.
        assertThrows(CorruptMarkException::class.java) { runBlocking { mark.read() } }

        runBlocking { mark.write(7) }
        assertEquals(7L, runBlocking { mark.read() })
    }
}
