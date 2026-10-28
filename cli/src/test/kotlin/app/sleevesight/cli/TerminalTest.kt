package app.sleevesight.cli

import app.sleevesight.cli.Terminal.WordsSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where `keys-qr` reads the words from (#590): never where they would show as typed. */
class TerminalTest {
    private val reader: (String) -> String? = { null }

    private fun source(
        console: Boolean = false,
        tty: Boolean = false,
        stdinIsTerminal: Boolean = false,
        stdinTaken: Boolean = false,
    ) = Terminal(reader.takeIf { console }, reader.takeIf { tty }, stdinIsTerminal).wordsSource(stdinTaken)

    @Test
    fun `Java's console, a terminal, comes first`() {
        assertEquals(WordsSource.CONSOLE, source(console = true, tty = true, stdinIsTerminal = true))
        assertEquals(WordsSource.CONSOLE, source(console = true, stdinTaken = true))
    }

    @Test
    fun `standard input a terminal, with standard output redirected, is read through the controlling terminal`() {
        assertEquals(WordsSource.TTY, source(tty = true, stdinIsTerminal = true))
        assertEquals(WordsSource.TTY, source(tty = true, stdinTaken = true))
    }

    @Test
    fun `standard input a terminal whose echo cannot be turned off is refused`() {
        assertEquals(WordsSource.NO_MASKED_TERMINAL, source(stdinIsTerminal = true))
    }

    @Test
    fun `a pipe on standard input is read as it is, unless a key comes from it`() {
        assertEquals(WordsSource.STDIN, source())
        assertEquals(WordsSource.STDIN, source(tty = true))
        assertEquals(WordsSource.STDIN_TAKEN, source(stdinTaken = true))
    }
}
