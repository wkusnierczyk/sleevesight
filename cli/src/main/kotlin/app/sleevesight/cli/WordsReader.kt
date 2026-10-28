package app.sleevesight.cli

import app.sleevesight.core.keys.KeyWords
import app.sleevesight.core.platform.JvmResourceReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream

/**
 * The words `keys-qr` seals the code with (#590), from Java's console or the file `--words-from`
 * names ([KeysQr]); what is wrong with them is said on [err], naming no word.
 */
internal class WordsReader(
    private val err: PrintStream,
    private val input: InputStream,
) {
    /**
     * The five words, typed at the [console] (three tries) or in the [file] `--words-from` names, `-`
     * for standard input (one), or null, said why.
     */
    fun read(
        file: String?,
        console: ((String) -> String?)?,
    ): List<String>? {
        val list = KeyWords.load(JvmResourceReader)
        val tries = if (file == null) TRIES else 1
        var words: List<String>? = null
        var asked = 0
        var answered = true
        while (words == null && answered && asked < tries) {
            asked++
            val read = (if (file == null) console?.invoke(PROMPT) else wordsIn(file))?.let(list::read)
            val problem = read?.let(::problemOf)
            when {
                read == null -> {
                    answered = false
                    err.println(NO_WORDS)
                }

                problem != null -> err.println(problem)
                else -> words = read.filterNotNull()
            }
        }
        if (words == null && answered) {
            err.println("Nothing drawn: give the words Load keys shows, or give --unsealed for a clear code.")
        }
        return words
    }

    // The words in [path], or on standard input for `-`: its first line that is not blank; null,
    // said, when it cannot be read (naming neither the path nor the error, which names it).
    private fun wordsIn(path: String): String? {
        val text =
            try {
                if (path == "-") input.bufferedReader().readText() else File(path).readText()
            } catch (_: IOException) {
                err.println("The words could not be read: the file given or standard input.")
                null
            }
        return text?.lines()?.firstOrNull { it.isNotBlank() }
    }

    // What is wrong with the words [read], or null when they are the five.
    private fun problemOf(read: List<String?>): String? {
        val wrong = read.indexOfFirst { it == null }
        return when {
            wrong >= 0 -> "Word ${wrong + 1} is not one of the phone's words."
            read.size != KeyWords.COUNT -> "${read.size} words: the phone shows ${KeyWords.COUNT}."
            else -> null
        }
    }

    private companion object {
        const val TRIES = 3
        const val PROMPT =
            "The five words Load keys shows on the phone, in order (the first three letters of each are enough): "
        const val NO_WORDS = "No words given: nothing drawn."
    }
}
