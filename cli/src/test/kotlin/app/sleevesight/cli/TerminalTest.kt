package app.sleevesight.cli

import app.sleevesight.cli.Terminal.WordsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where `keys-qr` reads the words from (#590): Java's console, a terminal, or a file named for them. */
class TerminalTest {
    private val console = Terminal(console = { null })
    private val none = Terminal()

    @Test
    fun `Java's console, a terminal, is read`() {
        assertEquals(WordsSource.CONSOLE, console.wordsSource(wordsFrom = false))
    }

    @Test
    fun `a file named for the words is read, console or not`() {
        assertEquals(WordsSource.FILE, none.wordsSource(wordsFrom = true))
        assertEquals(WordsSource.FILE, console.wordsSource(wordsFrom = true))
    }

    @Test
    fun `anything else is refused`() {
        assertEquals(WordsSource.REFUSED, none.wordsSource(wordsFrom = false))
    }

    @Test
    fun `a console is used only when it is a terminal`() {
        // Java 21 and before: no console unless both streams are a terminal; no `isTerminal`.
        assertFalse(Terminal.usable(present = false, isTerminal = null))
        assertTrue(Terminal.usable(present = true, isTerminal = null))
        // Java 22 and later: a console whatever the streams, which says whether it is a terminal.
        assertFalse(Terminal.usable(present = true, isTerminal = false))
        assertTrue(Terminal.usable(present = true, isTerminal = true))
    }
}
