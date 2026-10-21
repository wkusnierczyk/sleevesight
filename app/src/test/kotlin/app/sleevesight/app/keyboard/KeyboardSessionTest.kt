package app.sleevesight.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's side of the keyboard's protocol (#394), against messages the page's code sealed. */
class KeyboardSessionTest {
    private fun session() = KeyboardSession.parse(KeyboardVectors.code)!!

    private val messages = KeyboardVectors.messages

    @Test
    fun `the code gives the page's service`() {
        assertEquals(KeyboardVectors.service, session().serviceUuid.toString())
    }

    @Test
    fun `each message the page sealed opens to its text, in order`() {
        val session = session()
        for ((_, text, sealed) in messages) assertEquals(text, session.open(sealed))
    }

    @Test
    fun `the longest text, 480 bytes, opens`() {
        assertEquals("x".repeat(KeyboardSession.MAX_TEXT_BYTES), session().open(messages.last().third))
    }

    @Test
    fun `a message is refused when any byte is changed`() {
        val sealed = messages[1].third
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i changed", session().open(tampered))
        }
    }

    @Test
    fun `a message sent again, or one older than the last taken, is refused`() {
        val session = session()
        assertEquals(messages[1].second, session.open(messages[1].third))
        assertNull(session.open(messages[1].third))
        assertNull(session.open(messages[0].third))
        assertEquals(messages[2].second, session.open(messages[2].third))
    }

    @Test
    fun `a refused message does not move the counter`() {
        val session = session()
        val tampered = messages[2].third.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertNull(session.open(tampered))
        assertEquals(messages[1].second, session.open(messages[1].third))
    }

    @Test
    fun `another session's key opens nothing`() {
        val other = KeyboardSession.parse(KeyboardSession.PREFIX + "oKGio6SlpqcAESIzRFVmd4iZqrvM3e7A")!!
        assertNull(other.open(messages[1].third))
    }

    @Test
    fun `a message too short or too long is refused`() {
        assertNull(session().open(ByteArray(19)))
        assertNull(session().open(messages.last().third + ByteArray(1)))
    }

    @Test
    fun `a forgotten session opens nothing`() {
        val session = session()
        session.forget()
        assertNull(session.open(messages[0].third))
    }

    @Test
    fun `a session is claimed once`() {
        val code = KeyboardVectors.freshCode()
        assertTrue(KeyboardSession.parse(code)!!.claim())
        assertFalse(KeyboardSession.parse(code)!!.claim())
        assertTrue(KeyboardSession.parse(KeyboardVectors.freshCode())!!.claim())
    }

    @Test
    fun `codes that are not a session's are not read`() {
        assertNull(KeyboardSession.parse("0123456789abcdef0123456789abcdef"))
        assertNull(KeyboardSession.parse("sleevesight-keyboard:v2:oKGio6SlpqcAESIzRFVmd4iZqrvM3e7_"))
        assertNull(KeyboardSession.parse(KeyboardSession.PREFIX + "oKGio6SlpqcAESIzRFVmd4iZqrvM3e"))
        assertNull(KeyboardSession.parse(KeyboardSession.PREFIX + "oKGio6SlpqcAESIzRFVmd4iZqrvM3e7_AA"))
        assertNull(KeyboardSession.parse(KeyboardSession.PREFIX + "oKGio6SlpqcAESIzRFVmd4iZqrvM3e7!"))
    }
}
