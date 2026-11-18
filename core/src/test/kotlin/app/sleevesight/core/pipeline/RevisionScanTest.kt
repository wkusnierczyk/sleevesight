package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SelfRevision
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RevisionScanTest {
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a reply of many short unmarked lines is read, and read as it streams, in linear time`() {
        // At each line's start a line's mark was looked for with a regex that, finding none there,
        // searched on to the reply's end: about 25 s at 64k characters on the JVM, more on the phone.
        val reply = LINE.repeat(LINES)
        val read = assertIs<InsightReply.Written>(readReply(reply, EvidenceSet.of(emptyList(), JvmHasher), true))
        assertEquals(reply.trim(), read.grounded.text)
        assertNull(SelfRevision.ofPartial(reply))

        // As it streams, a line a piece, 10 ms apart: read every 50 ms, not at every piece.
        val time = TestTimeSource()
        val pace = PartialPace(time)
        val streamed = StringBuilder()
        val shown = mutableListOf<String>()
        repeat(STREAMED) {
            streamed.append(LINE)
            pace.next(streamed.toString(), LINE) { shown += it }
            time += 10.milliseconds
        }
        assertEquals(STREAMED / 5, shown.size)
        assertEquals(LINE, shown.first())
    }

    @Test
    fun `a piece is read first, then at a line's end or after a while, and less often after a long reading`() {
        val time = TestTimeSource()
        val pace = PartialPace(time)
        val read = mutableListOf<String>()

        fun piece(text: String) = pace.read(text) { read += text }
        assertTrue(piece("It"))
        time += PartialPace.LINE
        piece(" is")
        piece(" so.\n")
        time += PartialPace.EVERY - 1.milliseconds
        piece(" More")
        time += 1.milliseconds
        piece(" text")
        assertEquals(listOf("It", " so.\n", " text"), read)

        // A reading that took 100 ms: the next waits four times as long, a line's end or not.
        time += PartialPace.EVERY
        assertTrue(pace.read(" slow") { time += 100.milliseconds })
        time += 399.milliseconds
        piece("\n")
        time += 1.milliseconds
        piece("\n")
        assertEquals(listOf("It", " so.\n", " text", "\n"), read)
    }

    private companion object {
        const val LINE = "It is so.\n"

        // About 64k characters.
        const val LINES = 6_400
        const val STREAMED = 2_000
    }
}
