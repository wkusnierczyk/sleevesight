package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceLine

/**
 * Whether the lines a sentence cites back it, word for word (#1036; `design/design.md`, *Synthesis*,
 * *Grounding check*): the grounding check otherwise confirms only that a cited line exists, so a
 * plausible false sentence with a valid citation would pass. Narrow on purpose, since it matches
 * words, not meaning: a false *unsupported* dims a true sentence, so it compares only what a wrong
 * detail most often changes, and reads each loosely.
 *
 * What it compares, the sentence's citations taken out:
 * - **Numbers in digits** (`1974`, `12,838`, `9th`, `1970s`): each must be in a cited line, in digits
 *   (leading zeros and digit-group commas aside: `03` is `3`, `12,838` is `12838`) or in words up to
 *   the tens (`one` to `ninety-nine`, `hundred`, `thousand`; ordinals `first` to `twentieth`). Two
 *   digits also match a four-digit number ending in them (`'74`, `1970-75`), and a decade's four
 *   digits its two (`1970s` beside `70s`). Numbers in words in the sentence are not compared (`one of
 *   the`, `number one`).
 * - **Names**: a word of three letters or more that opens with a capital, but for a few small words
 *   ([SMALL_WORDS]: `The`, `Of`, `And`), the names of the sources ([SOURCE_NAMES]), Roman numerals,
 *   and the sentence's first word when the word after it does not open with a capital too (`Despite
 *   its …`, `Released in …`: the capital is the sentence's, not a name's). Each must be in a cited
 *   line, in any case, a letter with a diacritic matching any letter (`Beyonce`, `Beyoncé`), or as a
 *   word that shares all but its last three letters, five at least (`Canada`, `Canadian`). A month's
 *   name also matches its abbreviation or an ISO date of that month (`March`, `Mar.`, `1974-03-25`).
 * - Either may instead be a word of what the card is about ([subject]: the track's title, artists and
 *   album, the artist's or album's name), which a line often leaves out (`it`, `the song`).
 *
 * Not checked: a sentence that cites a line in another language (#902, `EvidenceLine.language`),
 * which the model translated and the app has only as found; any word in lower case; numbers in
 * words. Portable: no regex classes (D2), letters and digits read with the standard library.
 */
@Suppress("TooManyFunctions", "MagicNumber") // One step of the comparison each; numbers' and months' own values.
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
        val cited = lines.flatMap { tokens(it.text) }
        val known = subject.flatMap(::tokens)
        val words = (cited + known).filter { !it.number }.mapTo(mutableSetOf()) { fold(it.text) }
        val numbers = (cited + known).filter { it.number }.mapTo(mutableSetOf()) { it.text }
        val months = monthsIn(lines, words)
        val letters = said.filter { !it.number }
        return said.withIndex().all { (index, token) ->
            when {
                token.number -> numberBacked(token.text, said.getOrNull(index + 1), numbers, words)
                !isName(token, letters) -> true
                else -> {
                    val folded = fold(token.text)
                    val month = MONTHS[folded]
                    if (month != null) month in months else words.any { sameName(folded, it) }
                }
            }
        }
    }

    // A run of letters or of digits in a text, [number] for digits (without leading zeros, and
    // without the commas between groups of three: `12,838`).
    private class Token(
        val text: String,
        val number: Boolean,
        val first: Boolean,
    )

    private fun tokens(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> {
                    val (digits, end) = digitsAt(text, i)
                    tokens += Token(digits, number = true, first = false)
                    i = end
                }

                c.isLetter() -> {
                    val start = i
                    while (i < text.length && text[i].isLetter()) i++
                    tokens += Token(text.substring(start, i), number = false, first = tokens.none { !it.number })
                }

                else -> {
                    i++
                }
            }
        }
        return tokens
    }

    // The number whose digits start at [start] in [text], without leading zeros and with the commas
    // between groups of three left out, and where it ends.
    private fun digitsAt(
        text: String,
        start: Int,
    ): Pair<String, Int> {
        val run = StringBuilder()
        var i = start
        while (i < text.length && inNumber(text, i)) {
            if (text[i].isDigit()) run.append(text[i])
            i++
        }
        return run.toString().trimStart('0').ifEmpty { "0" } to i
    }

    // Whether the character at [i] in [text] is part of a number: a digit, or a comma before a group
    // of three.
    private fun inNumber(
        text: String,
        i: Int,
    ): Boolean = text[i].isDigit() || (text[i] == ',' && groupOfThree(text, i + 1))

    // Whether three digits, and no fourth, start at [at] in [text].
    private fun groupOfThree(
        text: String,
        at: Int,
    ): Boolean =
        at + DIGIT_GROUP <= text.length &&
            (at until at + DIGIT_GROUP).all { text[it].isDigit() } &&
            text.getOrNull(at + DIGIT_GROUP)?.isDigit() != true

    // Whether [token] is compared as a name ([Support]): a capital, three letters or more, not a
    // small word, a source's name or a Roman numeral; the sentence's first word only before another
    // capital word.
    private fun isName(
        token: Token,
        letters: List<Token>,
    ): Boolean {
        val text = token.text
        val folded = fold(text)
        val candidate =
            text.length >= MIN_NAME &&
                text[0].isUpperCase() &&
                folded !in SMALL_WORDS &&
                folded !in SOURCE_NAMES &&
                !ROMAN.matches(text)
        val next = letters.getOrNull(letters.indexOf(token) + 1)
        return candidate && (!token.first || next?.text?.get(0)?.isUpperCase() == true)
    }

    // Whether the number [digits] (followed in the sentence by [next]) is in [numbers] or [words].
    private fun numberBacked(
        digits: String,
        next: Token?,
        numbers: Set<String>,
        words: Set<String>,
    ): Boolean {
        // `'74` and `1970-75` beside a year; `1970s` beside `70s`.
        val yearEnd = digits.length == 2 && numbers.any { it.length == YEAR_DIGITS && it.endsWith(digits) }
        val decade = digits.length == YEAR_DIGITS && digits.endsWith("0") && next?.text == "s"
        val value = digits.toIntOrNull()
        return digits in numbers ||
            yearEnd ||
            (decade && digits.takeLast(2) in numbers) ||
            (value != null && inWords(value).any { form -> form.all { it in words } })
    }

    // The ways [value] is written in words that [numberBacked] accepts, each as the words it takes.
    private fun inWords(value: Int): List<List<String>> {
        val forms = mutableListOf<List<String>>()
        CARDINALS[value]?.let { forms += listOf(it) }
        ORDINALS[value]?.let { forms += listOf(it) }
        if (value in TWO_DIGIT_WORDS && value % TEN != 0) {
            val tens = CARDINALS[value - value % TEN]
            val unit = CARDINALS[value % TEN]
            if (tens != null && unit != null) forms += listOf(tens, unit)
        }
        return forms
    }

    // The months [lines] name, by number: in their words ([words], folded) or in an ISO date.
    private fun monthsIn(
        lines: List<EvidenceLine>,
        words: Set<String>,
    ): Set<Int> =
        words.mapNotNullTo(mutableSetOf()) { MONTHS[it] } +
            lines.flatMap { line -> ISO_MONTH.findAll(line.text).mapNotNull { it.groupValues[1].toIntOrNull() } }

    // [word] in lower case, each letter with a diacritic ([isAccented]) as [ANY_LETTER].
    private fun fold(word: String): String =
        word.lowercase().map { if (isAccented(it)) ANY_LETTER else it }.joinToString("")

    private fun isAccented(c: Char): Boolean = c in LATIN_ACCENTED

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

    // A month's name and its abbreviations, folded, by its number.
    private val MONTHS: Map<String, Int> =
        "january february march april may june july august september october november december"
            .split(' ')
            .withIndex()
            .flatMap { (index, name) ->
                val number = index + 1
                listOfNotNull(name to number, (name.take(3) to number).takeIf { name.length > 3 }) +
                    if (name == "september") listOf("sept" to number) else emptyList()
            }.toMap()

    // Words written with a capital in names and titles that say nothing a line must hold.
    private val SMALL_WORDS: Set<String> =
        (
            "the and for with from into onto upon von van der den des del della les los las " +
                "but nor yet its"
        ).split(' ').toSet()

    // The sources' own names, which a sentence may give as where it read something.
    private val SOURCE_NAMES: Set<String> =
        setOf("wikipedia", "wikidata", "musicbrainz", "discogs", "last", "lastfm", "lrclib", "exa", "sleevesight")

    private val CARDINALS: Map<Int, String> =
        (
            "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen " +
                "fifteen sixteen seventeen eighteen nineteen twenty"
        ).split(' ').withIndex().associate { (index, word) -> index to word } +
            mapOf(
                30 to "thirty",
                40 to "forty",
                50 to "fifty",
                60 to "sixty",
                70 to "seventy",
                80 to "eighty",
                90 to "ninety",
                100 to "hundred",
                1000 to "thousand",
                1_000_000 to "million",
            )

    private val ORDINALS: Map<Int, String> =
        (
            "first second third fourth fifth sixth seventh eighth ninth tenth eleventh twelfth " +
                "thirteenth fourteenth fifteenth sixteenth seventeenth eighteenth nineteenth twentieth"
        ).split(' ').withIndex().associate { (index, word) -> index + 1 to word }

    private const val MIN_NAME = 3
    private const val STEM = 5
    private const val STEM_SLACK = 3
    private const val YEAR_DIGITS = 4
    private const val DIGIT_GROUP = 3
    private const val TEN = 10
    private val TWO_DIGIT_WORDS = 21..99
    private const val ANY_LETTER = '\u0000'

    // Latin letters with diacritics: Latin-1's (but × and ÷), Latin Extended-A and -B.
    private val LATIN_ACCENTED: CharRange = '\u00C0'..'\u024F'

    private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    private val ROMAN = Regex("[IVXLC]+")
    private val ISO_MONTH = Regex("[0-9]{4}-([0-9]{2})")
}
