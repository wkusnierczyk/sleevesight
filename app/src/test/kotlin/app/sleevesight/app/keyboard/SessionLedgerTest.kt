package app.sleevesight.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A keyboard session is run once, even after the app's process has ended (#394). */
class SessionLedgerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(code: String = KeyboardVectors.freshCode()) = KeyboardSession.parse(code)!!

    @Test
    fun `a session is claimed once`() {
        val ledger = SessionLedger(tmp.root.resolve("sessions"))
        val code = KeyboardVectors.freshCode()
        assertTrue(ledger.claim(session(code)))
        assertFalse(ledger.claim(session(code)))
        assertTrue(ledger.claim(session()))
    }

    @Test
    fun `a session claimed before the process ended is refused after it`() {
        val file = tmp.root.resolve("sessions")
        val code = KeyboardVectors.freshCode()
        assertTrue(SessionLedger(file).claim(session(code)))
        assertFalse(SessionLedger(file).claim(session(code)))
    }

    @Test
    fun `the ledger keeps the last sessions only, and no key`() {
        val file = tmp.root.resolve("sessions")
        val ledger = SessionLedger(file)
        val first = KeyboardVectors.freshCode()
        ledger.claim(session(first))
        val later = List(SessionLedger.LIMIT) { session() }
        later.forEach { ledger.claim(it) }
        // Each line is the service's UUID of a session claimed, and nothing else.
        assertEquals(later.map { it.serviceUuid.toString() }, file.readLines())
        assertTrue(SessionLedger(file).claim(session(first)))
    }

    @Test
    fun `a ledger that cannot be read is not written over`() {
        // A directory where the file would be: reading it fails.
        val file = tmp.newFolder("sessions")
        val ledger = SessionLedger(file)
        val code = KeyboardVectors.freshCode()
        assertTrue(ledger.claim(session(code)))
        assertFalse(ledger.claim(session(code)))
        assertTrue(file.isDirectory)
    }

    @Test
    fun `a write that fails leaves the ledger as it was`() {
        val file = tmp.root.resolve("sessions")
        val code = KeyboardVectors.freshCode()
        assertTrue(SessionLedger(file).claim(session(code)))
        val before = file.readText()
        // The new file cannot be made: a directory stands where it would go.
        tmp.root.resolve("sessions.new").mkdir()
        assertTrue(SessionLedger(file).claim(session()))
        assertEquals(before, file.readText())
        assertFalse(SessionLedger(file).claim(session(code)))
    }

    @Test
    fun `a ledger that cannot be written still refuses within the run`() {
        val blocked = tmp.newFile("not-a-directory")
        val ledger = SessionLedger(blocked.resolve("sessions"))
        val code = KeyboardVectors.freshCode()
        assertTrue(ledger.claim(session(code)))
        assertFalse(ledger.claim(session(code)))
    }
}
