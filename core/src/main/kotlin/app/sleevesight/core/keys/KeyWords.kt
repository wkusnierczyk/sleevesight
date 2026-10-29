package app.sleevesight.core.keys

import app.sleevesight.core.ResourceReader

// The words a sealed setup code is sealed with (#590): *Load keys* shows five, drawn at random from
// the EFF's short wordlist 2, and the API keys page and the CLI's `keys-qr` take them back.

/**
 * The words of the EFF's short wordlist 2 (1,296 words, about 10.3 bits each, from
 * https://www.eff.org/files/2016/09/08/eff_short_wordlist_2_0.txt, under CC BY 3.0 US), [words] in
 * the list's order. Each word's first three letters are its own, so three letters are enough to
 * type a word, and a word typed wrong after them is caught ([complete]). The list is bundled as the
 * EFF publishes it, at [RESOURCE], whose SHA-256 is [SHA256]; the API keys page holds the same text
 * (`assets/api-keys/words.js`), which the app's tests check.
 */
class KeyWords(
    val words: List<String>,
) {
    private val byPrefix: Map<String, String> = words.associateBy { it.take(PREFIX) }

    init {
        require(words.size == SIZE) { "The list has ${words.size} words, not $SIZE" }
        require(byPrefix.size == SIZE) { "Two words of the list start with the same $PREFIX letters" }
        require(words.all { WORD.matches(it) }) { "A word of the list is not of lower-case letters" }
    }

    /**
     * The word of the list [typed] is, or null when it is none: [typed], trimmed and in lower case,
     * holds at least the word's first [PREFIX] letters and nothing that is not the word's. A word
     * of three letters is typed whole. Trimmed of the spaces JavaScript's `trim` drops, as the
     * page trims.
     */
    fun complete(typed: String): String? {
        val token = typed.replace(SPACES_AT_ENDS, "").lowercase()
        if (token.length < PREFIX) return null
        return byPrefix[token.take(PREFIX)]?.takeIf { it.startsWith(token) }
    }

    /**
     * The words in [text], as typed on the computer: split at spaces, line breaks and commas, each
     * [complete]d, null where one is not a word of the list. The spaces are JavaScript's `\s`, as
     * the page splits: ASCII's, Unicode's separators (`\p{Z}`, a no-break space among them) and
     * U+FEFF.
     */
    fun read(text: String): List<String?> = text.split(SEPARATORS).filter { it.isNotEmpty() }.map(::complete)

    /**
     * [COUNT] different words, each picked by [pick], which gives a random number from 0 up to, and
     * not including, its bound, from a cryptographically strong source.
     */
    fun draw(pick: (Int) -> Int): List<String> {
        val drawn = LinkedHashSet<String>()
        while (drawn.size < COUNT) drawn += words[pick(words.size)]
        return drawn.toList()
    }

    companion object {
        /** How many words *Load keys* shows, about 52 bits. */
        const val COUNT = 5

        /** How many first letters of a word are its own. */
        const val PREFIX = 3

        /** How many words the list holds. */
        const val SIZE = 1296

        /** Where the list is bundled, under the resource root. */
        const val RESOURCE = "sleevesight/eff_short_wordlist_2_0.txt"

        /** The SHA-256 of the list as the EFF publishes it, and as bundled. */
        const val SHA256 = "22b45c52e0bd0bbf03aa522240b111eb4c7c0c1d86c4e518e1be2a7eb2a625e4"

        // Lower-case letters, and the one hyphen of `yo-yo`.
        private val WORD = Regex("[a-z]+(-[a-z]+)?")
        private const val SPACE = "\\s\\p{Z}\\uFEFF"
        private val SEPARATORS = Regex("[$SPACE,]+")
        private val SPACES_AT_ENDS = Regex("^[$SPACE]+|[$SPACE]+$")

        /**
         * The words of [text], the list as the EFF publishes it: a line a word, its five dice
         * digits, a tab, and the word.
         */
        fun parse(text: String): KeyWords =
            KeyWords(text.lines().filter { it.isNotBlank() }.map { it.substringAfter('\t').trim() })

        /** The list bundled with `:core`, read by [reader]. */
        fun load(reader: ResourceReader): KeyWords =
            parse(checkNotNull(reader.text(RESOURCE)) { "No word list at $RESOURCE" })
    }
}
