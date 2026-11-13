package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceLine

/**
 * Whether the lines a sentence cites back it, word for word (#1036; `design/design.md`, *Synthesis*,
 * *Grounding check*): the grounding check otherwise confirms only that a cited line exists, so a
 * plausible false sentence with a valid citation would pass. Narrow on purpose, since it matches
 * words, not meaning: a false *unsupported* dims a true sentence, so it compares only what a wrong
 * detail most often changes, reads each loosely, and in doubt finds the sentence backed.
 *
 * What it compares, the sentence's citations taken out:
 * - **Years** (a four-digit number from 1000 to 2099): each must be in a cited line, or its last two
 *   digits must be (`'74`). A decade (`1970s`) is backed by any year in it, or by the decade written
 *   either way (`70s`). Other numbers are not compared: a count, a chart place, a day of the month or
 *   an age may be written another way or worked out (35, from a birth in 1938 and a recording in
 *   1973), which words cannot tell.
 * - **Names**: a word of three letters or more that opens with a capital, but not the sentence's
 *   first word (`Released in …`: its capital may be the sentence's), a few small words
 *   ([SMALL_WORDS]: `The`, `And`), the sources' names ([SOURCE_NAMES]), Roman numerals, and places
 *   and their peoples ([PLACES]: `United States`, `USA`, `British`, `Britain`), which lines and
 *   sentences name in too many ways to match. Each must be in a cited line, in any case, a letter
 *   with a diacritic matching any letter (`Beyonce`, `Beyoncé`), or as a word that shares all but
 *   its last three letters, five at least (`Newfoundland`, `Newfoundlanders`). A month's name also
 *   matches its abbreviation or an ISO date of that month (`March`, `Mar.`, `1974-03-25`).
 * - Either may instead be a word of what the card is about ([subject]: the track's title, artists and
 *   album, the artist's or album's name, the player's release line), which a line often leaves out
 *   (`it`, `the song`).
 *
 * Not checked: a sentence that cites a line in another language (#902, `EvidenceLine.language`),
 * which the model translated and the app has only as found; any word in lower case; numbers other
 * than years. Portable: no regex classes (D2), letters and digits read with the standard library.
 */
@Suppress("TooManyFunctions", "MagicNumber") // One step of the comparison each; years' and months' own values.
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
        return said.withIndex().all { (index, token) ->
            when {
                token.number -> yearBacked(token.text, said.getOrNull(index + 1), numbers)
                !isName(token) -> true
                else -> {
                    val folded = fold(token.text)
                    val month = MONTHS[folded]
                    if (month != null) month in months else words.any { sameName(folded, it) }
                }
            }
        }
    }

    // A run of letters or of digits in a text, [number] for digits (without leading zeros), [first]
    // for the text's first run of letters.
    private class Token(
        val text: String,
        val number: Boolean,
        val first: Boolean,
    )

    private fun tokens(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                text[i].isDigit() -> {
                    while (i < text.length && text[i].isDigit()) i++
                    val digits = text.substring(start, i).trimStart('0').ifEmpty { "0" }
                    tokens += Token(digits, number = true, first = false)
                }

                text[i].isLetter() -> {
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

    // Whether [token] is compared as a name ([Support]): a capital, three letters or more, not the
    // sentence's first word, a small word, a source's name, a place's or its people's, or a Roman
    // numeral.
    private fun isName(token: Token): Boolean {
        val text = token.text
        val folded = fold(text)
        val excluded = folded in SMALL_WORDS || folded in SOURCE_NAMES || folded in PLACES || ROMAN.matches(text)
        return !token.first && text.length >= MIN_NAME && text[0].isUpperCase() && !excluded
    }

    // Whether the number [digits] (followed in the sentence by [next]) is backed by [numbers]: only a
    // year is compared ([Support]).
    private fun yearBacked(
        digits: String,
        next: Token?,
        numbers: Set<String>,
    ): Boolean {
        val year = digits.toIntOrNull()?.takeIf { it in YEARS }
        val decade = year != null && next?.text == "s" && year % DECADE == 0
        val years = numbers.mapNotNull { it.toIntOrNull() }.filter { it in YEARS }
        return year == null ||
            digits in numbers ||
            digits.takeLast(2) in numbers ||
            (decade && years.any { it / DECADE == year / DECADE })
    }

    // The months [lines] name, by number: in their words ([words], folded) or in an ISO date.
    private fun monthsIn(
        lines: List<EvidenceLine>,
        words: Set<String>,
    ): Set<Int> =
        words.mapNotNullTo(mutableSetOf()) { MONTHS[it] } +
            lines.flatMap { line -> ISO_MONTH.findAll(line.text).mapNotNull { it.groupValues[1].toIntOrNull() } }

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

    // Places, their peoples and the words of their names, which lines and sentences give in many
    // ways (`US`, `USA`, `United States`, `America`, `American`): not compared, rather than matched
    // by a table of equivalents that would miss some. A word of a band's name among them (`New
    // Order`) goes uncompared too, which only lets a sentence pass.
    private val PLACES: Set<String> =
        (
            "united states kingdom america american americans usa britain british brit brits england " +
                "english scotland scottish ireland irish wales welsh canada canadian canadians australia " +
                "australian australians new zealand german germany french france italian italy spanish " +
                "spain japan japanese sweden swedish norway norwegian denmark danish finland finnish " +
                "dutch netherlands holland belgium belgian brazil brazilian mexico mexican jamaica " +
                "jamaican europe european africa african nigeria nigerian korea korean china chinese " +
                "india indian russia russian soviet north south east west northern southern eastern western"
        ).split(' ').toSet()

    private const val MIN_NAME = 3
    private const val STEM = 5
    private const val STEM_SLACK = 3
    private const val DECADE = 10
    private val YEARS = 1000..2099
    private const val ANY_LETTER = '\u0000'

    // Latin letters with diacritics: Latin-1's (but × and ÷), Latin Extended-A and -B.
    private val LATIN_ACCENTED: CharRange = 'À'..'ɏ'

    private val CITATION = Regex("\\[[A-Z]{1,2}[0-9]*\\]")
    private val ROMAN = Regex("[IVXLC]+")
    private val ISO_MONTH = Regex("[0-9]{4}-([0-9]{2})")
}
