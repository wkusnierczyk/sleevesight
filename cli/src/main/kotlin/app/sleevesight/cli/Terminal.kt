package app.sleevesight.cli

import java.io.Console
import java.io.File
import java.io.PrintStream

/**
 * Where `keys-qr` can read the words from (#590) without showing them: the [console], when Java's
 * console is a terminal; the controlling terminal ([tty]), read with its echo turned off, which
 * works when standard output is redirected too; and whether standard input is a terminal
 * ([stdinIsTerminal]), where reading it would show the words. Each reader takes the prompt.
 */
class Terminal(
    val console: ((String) -> String?)? = null,
    val tty: ((String) -> String?)? = null,
    val stdinIsTerminal: Boolean = false,
) {
    /** How the words are read: masked, from a pipe, or not at all, and why. */
    enum class WordsSource {
        /** Java's console, `readPassword`. */
        CONSOLE,

        /** The controlling terminal, with its echo off. */
        TTY,

        /** The first line of standard input, a pipe or a file, where nothing is shown. */
        STDIN,

        /** Standard input is a terminal, and there is no way to turn its echo off. */
        NO_MASKED_TERMINAL,

        /** A key is read from standard input, and there is no terminal for the words. */
        STDIN_TAKEN,
    }

    /** Where the words come from, when a key is read from standard input or not ([stdinTaken]). */
    fun wordsSource(stdinTaken: Boolean): WordsSource =
        when {
            console != null -> WordsSource.CONSOLE
            tty != null && (stdinTaken || stdinIsTerminal) -> WordsSource.TTY
            stdinTaken -> WordsSource.STDIN_TAKEN
            stdinIsTerminal -> WordsSource.NO_MASKED_TERMINAL
            else -> WordsSource.STDIN
        }

    companion object {
        private const val TTY = "/dev/tty"

        /**
         * The process's own: Java's console when it is a terminal, else the controlling terminal
         * when there is one and its echo can be turned off; prompts on [err].
         */
        fun of(err: PrintStream): Terminal {
            val console = System.console()?.takeIf(::isTerminal)
            val reader =
                console?.let {
                    { prompt: String ->
                        it.readPassword("%s", prompt)?.let { chars -> String(chars).also { chars.fill('\u0000') } }
                    }
                }
            val tty = if (reader == null && ttyUsable()) { prompt: String -> readTty(prompt, err) } else null
            return Terminal(console = reader, tty = tty, stdinIsTerminal = stdinIsTerminal())
        }

        // From Java 22, `System.console()` is there even when the streams are not a terminal, and says
        // so through `isTerminal`, which earlier versions lack (and give no console then). Read on
        // `Console` itself: the class of the console given is the JDK's own, not public.
        private fun isTerminal(console: Console): Boolean =
            try {
                Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
            } catch (_: NoSuchMethodException) {
                true
            } catch (_: ReflectiveOperationException) {
                false
            }

        // Whether this process has a controlling terminal whose settings `stty` can read.
        private fun ttyUsable(): Boolean = File(TTY).canRead() && stty("-g")

        private fun stdinIsTerminal(): Boolean =
            run(ProcessBuilder("sh", "-c", "[ -t 0 ]").redirectInput(ProcessBuilder.Redirect.INHERIT))

        // A line from the controlling terminal, its echo off meanwhile and on again after, also if
        // the process is stopped then; null when the echo cannot be turned off, or at its end.
        private fun readTty(
            prompt: String,
            err: PrintStream,
        ): String? {
            err.print(prompt)
            err.flush()
            if (!stty("-echo")) {
                err.println()
                err.println("The terminal's echo could not be turned off: the words are not read.")
                return null
            }
            val restore = Thread { stty("echo") }
            Runtime.getRuntime().addShutdownHook(restore)
            return try {
                File(TTY).bufferedReader().use { it.readLine() }
            } finally {
                stty("echo")
                runCatching { Runtime.getRuntime().removeShutdownHook(restore) }
                err.println()
            }
        }

        private fun stty(setting: String): Boolean = run(ProcessBuilder("stty", setting).redirectInput(File(TTY)))

        private fun run(process: ProcessBuilder): Boolean =
            try {
                process
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
            } catch (_: java.io.IOException) {
                false
            }
    }
}
