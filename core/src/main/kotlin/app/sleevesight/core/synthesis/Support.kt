package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceLine

/**
 * Whether the lines a sentence cites back it, word for word (#1036; `design/design.md`, *Synthesis*,
 * *Grounding check*): the grounding check otherwise confirms only that a cited line exists, so a
 * plausible false sentence with a valid citation would pass. It matches words, not meaning, and a
 * false *unsupported* dims a true sentence, so it compares only two things, each loosely, and in
 * doubt finds the sentence backed; a wrong sentence that slips through is the accepted cost.
 *
 * - **Years**: a four-digit number from 1000 to 2099 (digit groups joined first, on both sides:
 *   `1,974` is `1974`). It is backed by the same number in a cited line, by a number under 100 there
 *   equal to its last two digits (`'01` backs `2001`), or by a decade that holds it (`1970s`, `70s`,
 *   `the Seventies`). A decade in the sentence (`1970s`) is backed by any year of it there. No other
 *   number is compared: a count, a chart place, a day or an age may be written another way or worked
 *   out. Months and dates are not compared either; a year inside a date counts as that year.
 * - **Multi-word names**: two or more capitalised words in a row, with only spaces, an apostrophe or
 *   a hyphen between them (`Steve George`, `Billboard Hot`, `Larry O'Gaff`), that do not start the
 *   sentence (`Released March …`: its first capital may be the sentence's). Each word must be in a
 *   cited line, in any case, a letter with a diacritic matching any letter, or as a word sharing all
 *   but its last three letters, five at least (`Newfoundland`, `Newfoundlanders`). Words that are no
 *   one's name are not compared within one ([NOT_NAMES]): small words (`The`, `Of`), months, numbers
 *   in words, the sources' names, Roman numerals, and places and their peoples (`United States`,
 *   `New Zealand`), which lines and sentences give in too many ways. A single capitalised word is
 *   never compared (`Swiss`, `March`).
 * - The words of what the card is about ([backs]' `subject`: the track's title, artists and album,
 *   the artist's or album's name, the player's release line) count as in every cited line.
 *
 * Not checked at all: a sentence that cites a line in another language (#902,
 * `EvidenceLine.language`), which the model translated and the app has only as found. Portable: no
 * regex classes (D2), letters and digits read with the standard library.
 */
@Suppress("TooManyFunctions", "MagicNumber") // One step of the comparison each; years' own values.
internal object Support {
    /**
     * Whether [lines], those [sentence] cites, back it, as [Support] compares them; true when one
     * of them is in another language, or there are none.
     */
    fun backs(
        sentence: String,
        lines: List<EvidenceLine>,
        subject: Collection<String>,
    ): Boolean {
        if (lines.isEmpty() || lines.any { it.language != null }) return true
        val said = tokens(CITATION.replace(sentence, " "))
        val cited = lines.flatMap { tokens(it.text) } + subject.flatMap(::tokens)
        val words = cited.filter { !it.number }.mapTo(mutableSetOf()) { fold(it.text) }
        return yearsBacked(said, cited) && names(said).all { name -> name.all { nameWordBacked(it, words) } }
    }

    // A run of letters or of digits in a text, [number] for digits (without leading zeros, digit
    // groups joined), with the text between it and the run before ([gap]).
    private class Token(
        val text: String,
        val number: Boolean,
        val gap: String,
    )

    private fun tokens(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var gapStart = 0
        while (i < text.length) {
            val start = i
            when {
                text[i].isDigit() -> {
                    while (i < text.length && (text[i].isDigit() || groupSeparator(text, i))) i++
                    val number =
                        text
                            .substring(start, i)
                            .filter { it.isDigit() }
                            .trimStart('0')
                            .ifEmpty { "0" }
                    tokens += Token(number, number = true, gap = text.substring(gapStart, start))
                    gapStart = i
                }

                text[i].isLetter() -> {
                    while (i < text.length && text[i].isLetter()) i++
                    tokens += Token(text.substring(start, i), number = false, gap = text.substring(gapStart, start))
                    gapStart = i
                }

                else -> {
                    i++
                }
            }
        }
        return tokens
    }

    // Whether the character at [i] of [text] joins digit groups: a comma or a no-break, thin or
    // narrow no-break space after a digit and before exactly three digits (`1,974`).
    private fun groupSeparator(
        text: String,
        i: Int,
    ): Boolean =
        text[i] in GROUP_SEPARATORS &&
            text.getOrNull(i - 1)?.isDigit() == true &&
            i + GROUP < text.length &&
            (i + 1..i + GROUP).all { text[it].isDigit() } &&
            text.getOrNull(i + GROUP + 1)?.isDigit() != true

    // Whether every year of [said] is backed by [cited] ([Support]).
    private fun yearsBacked(
        said: List<Token>,
        cited: List<Token>,
    ): Boolean {
        val numbers = cited.filter { it.number }.mapNotNullTo(mutableSetOf()) { it.text.toIntOrNull() }
        val small = numbers.filter { it < CENTURY }.toSet()
        val decades = decadesIn(cited)
        return said.withIndex().all { (index, token) ->
            val year = token.text.toIntOrNull()?.takeIf { token.number && it in YEARS }
            val decade = year != null && year % DECADE == 0 && said.getOrNull(index + 1)?.text == "s"
            year == null ||
                year in numbers ||
                (year % CENTURY) in small ||
                year / DECADE in decades ||
                (decade && numbers.any { it in YEARS && it / DECADE == year / DECADE })
        }
    }

    // The decades [tokens] name, each as its year over ten: `1970s` (197), `70s` and `the Seventies`
    // (197, read in the twentieth century).
    private fun decadesIn(tokens: List<Token>): Set<Int> =
        tokens
            .withIndex()
            .mapNotNull { (index, token) ->
                val value = token.text.toIntOrNull()?.takeIf { token.number && it % DECADE == 0 }
                val plural = tokens.getOrNull(index + 1)?.text == "s"
                when {
                    value != null && plural && value in YEARS -> value / DECADE
                    value != null && plural && value < CENTURY -> TWENTIETH + value / DECADE
                    else -> DECADE_WORDS[token.text.lowercase()]?.let { TWENTIETH + it }
                }
            }.toSet()

    // The multi-word names of [said]: runs of two or more capitalised words with only spaces, an
    // apostrophe or a hyphen between them, none of them the sentence's first word.
    private fun names(said: List<Token>): List<List<String>> {
        val names = mutableListOf<List<String>>()
        var run = mutableListOf<String>()
        var startsSentence = false
        for ((index, token) in said.withIndex()) {
            val capital = !token.number && token.text[0].isUpperCase()
            val joined = run.isNotEmpty() && token.gap.all { it == ' ' || it in JOINERS }
            if (capital && joined) {
                run += token.text
            } else {
                if (run.size >= 2 && !startsSentence) names += run
                run = if (capital) mutableListOf(token.text) else mutableListOf()
                startsSentence = capital && said.subList(0, index).all { it.number }
            }
        }
        if (run.size >= 2 && !startsSentence) names += run
        return names
    }

    // Whether [word], of a multi-word name, is in [words] (folded, as [sameName] matches), or is a
    // word that is no one's name ([NOT_NAMES], Roman numerals).
    private fun nameWordBacked(
        word: String,
        words: Set<String>,
    ): Boolean {
        val folded = fold(word)
        return folded in NOT_NAMES || ROMAN.matches(word) || words.any { sameName(folded, it) }
    }

    // [word] in lower case, each letter with a diacritic ([LATIN_ACCENTED]) as [ANY_LETTER].
    private fun fold(word: String): String =
        word.lowercase().map { if (it in LATIN_ACCENTED) ANY_LETTER else it }.joinToString("")

    // Whether two folded words name the same: equal, or sharing all but the last three letters of
    // the shorter, five at least; a letter with a diacritic matches any.
    private fun sameName(
        a: String,
        b: String,
    ): Boolean {
        val shorter = minOf(a.length, b.length)
        var common = 0
        while (common < shorter && same(a[common], b[common])) common++
        val equal = a.length == b.length && common == shorter
        return equal || (shorter >= STEM && common >= maxOf(STEM, shorter - STEM_SLACK))
    }

    private fun same(
        a: Char,
        b: Char,
    ): Boolean = a == b || a == ANY_LETTER || b == ANY_LETTER

    // Decades in words, by their tens in the twentieth century (`twenties` is 2).
    private val DECADE_WORDS: Map<String, Int> =
        "twenties thirties forties fifties sixties seventies eighties nineties"
            .split(' ')
            .withIndex()
            .associate { (index, word) -> word to index + 2 }

    // Words of a multi-word name that are no one's name ([Support]): small words, months, numbers in
    // words, the sources' own names, and places and their peoples.
    private val NOT_NAMES: Set<String> =
        (
            "the a an of and or in on at to for with from by de la le el von van der den des del della " +
                "les los las du di da " +
                "january february march april may june july august september october november december " +
                "jan feb mar apr jun jul aug sep sept oct nov dec " +
                "one two three four five six seven eight nine ten eleven twelve twenty hundred thousand " +
                "first second third fourth fifth sixth seventh eighth ninth tenth no number " +
                "twenties thirties forties fifties sixties seventies eighties nineties " +
                "wikipedia wikidata musicbrainz discogs last fm lastfm lrclib exa " +
                "united states kingdom america american usa us uk britain british england english " +
                "new zealand north south east west northern southern eastern western"
        ).split(' ').toSet()

    private const val STEM = 5
    private const val STEM_SLACK = 3
    private const val DECADE = 10
    private const val CENTURY = 100
    private const val TWENTIETH = 190
    private const val GROUP = 3
    private val YEARS = 1000..2099
    private const val ANY_LETTER = '\u0000'

    // What may join the words of a name besides spaces: apostrophes and hyphens.
    private const val JOINERS = "'’-‐‑"

    // A comma, and the no-break, thin and narrow no-break spaces, between digit groups.
    private const val GROUP_SEPARATORS = ",   "

    // Latin letters with diacritics: Latin-1's (but × and ÷), Latin Extended-A and -B.
    private val LATIN_ACCENTED: CharRange = 'À'..'ɏ'

    private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    private val ROMAN = Regex("[IVXLC]+")
}
