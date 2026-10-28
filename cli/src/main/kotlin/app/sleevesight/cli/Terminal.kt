package app.sleevesight.cli

import java.io.Console

/**
 * Where `keys-qr` reads the words from (#590), so that they never show as typed: Java's [console],
 * read with `readPassword`, which masks them, and only when the console is a terminal; or a file
 * the person names (`--words-from`), where nothing is typed. Nothing else is read: no guessing at
 * terminals, whose echo the CLI would have to turn off itself.
 */
class Terminal(
    val console: ((String) -> String?)? = null,
) {
    /** Where the words come from, or that they are refused before anything is read. */
    enum class WordsSource {
        /** Java's console, a terminal, `readPassword`. */
        CONSOLE,

        /** The file `--words-from` names, `-` for standard input. */
        FILE,

        /** Neither: standard output or input is redirected, or there is no console. */
        REFUSED,
    }

    /** Where the words come from, with a file named for them ([wordsFrom]) or not. */
    fun wordsSource(wordsFrom: Boolean): WordsSource =
        when {
            wordsFrom -> WordsSource.FILE
            console != null -> WordsSource.CONSOLE
            else -> WordsSource.REFUSED
        }

    companion object {
        /** The process's own: Java's console, when there is one and it is a terminal. */
        fun of(): Terminal {
            val console = System.console()?.takeIf { usable(present = true, isTerminal = isTerminal(it)) }
            val reader =
                console?.let {
                    { prompt: String ->
                        it.readPassword("%s", prompt)?.let { chars -> String(chars).also { chars.fill('\u0000') } }
                    }
                }
            return Terminal(console = reader)
        }

        /**
         * Whether a console is one to read the words from: [present] (before Java 22, a console is
         * there only when both standard input and output are a terminal), and a terminal by its
         * `isTerminal` ([isTerminal], from Java 22, which gives a console even when the streams are
         * redirected; null before, which has no such method). With `java.base` forced (`main`), whose
         * console is there only on a terminal, this check is a backstop for someone who sets the
         * provider back by hand.
         */
        fun usable(
            present: Boolean,
            isTerminal: Boolean?,
        ): Boolean = present && isTerminal != false

        // `Console.isTerminal`, read on `Console` itself (the console given is of a JDK class that
        // is not public); null when this Java has no such method, false when it cannot be read.
        private fun isTerminal(console: Console): Boolean? =
            try {
                Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
            } catch (_: NoSuchMethodException) {
                null
            } catch (_: ReflectiveOperationException) {
                false
            }
    }
}
