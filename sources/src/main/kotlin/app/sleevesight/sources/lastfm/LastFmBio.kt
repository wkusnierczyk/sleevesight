package app.sleevesight.sources.lastfm

/**
 * The sentences of a Last.fm bio that become evidence (D1): from the bio's summary, as plain text,
 * the first [MAX_SENTENCES] whole sentences of a quotable length, verbatim. A sentence is never cut,
 * so a quote is always one the bio's authors wrote. A bio that says several artists share the name
 * gives none: it describes more than one artist, so no sentence of it can be credited to this one.
 */
internal object LastFmBio {
    /** At most this many sentences per bio (D1's fixed number per article). */
    const val MAX_SENTENCES: Int = 3

    /** Shorter sentences are fragments (a list item, a heading). */
    const val MIN_LENGTH: Int = 20

    /** Longer ones are passages, not short quotes. */
    const val MAX_LENGTH: Int = 300

    // Last.fm's link at the summary's end, with the full stop an album's wiki puts after it (#885).
    private val READ_MORE = Regex("Read more on Last\\.fm\\.?$")
    private const val LICENCE_NOTE =
        "User-contributed text is available under the Creative Commons By-SA License; additional terms may apply."

    private val DISAMBIGUATION =
        Regex(
            "^there (are|is|were) (at least )?(\\d+|two|three|four|five|several|multiple|many|more than one) " +
                "(different )?(artists?|bands?|acts?|groups?|musicians?|people|persons?)",
            RegexOption.IGNORE_CASE,
        )

    private val SHARED_NAME =
        Regex(
            "(several|multiple|many|\\d+|two|three|four|five) (different )?" +
                "(artists|bands|acts|groups|musicians|people|persons) " +
                "(share|sharing|with|using|have) (this|the same|that) name",
            RegexOption.IGNORE_CASE,
        )

    // An enumerated list, `1) … 2) …` or `1. … 2. …`; a number such as `1.5` has no space after the
    // mark. Deliberately loose (see [sharedPage]).
    private val FIRST_ITEM = Regex("(^|\\s)1[.)] ")
    private val SECOND_ITEM = Regex("\\s2[.)] ")

    private val WHITESPACE = Regex("\\s+")

    // A trailing ellipsis, before any closing quotes or brackets.
    private val ELLIPSIS = Regex("(\\.\\.\\.|…)[\"')\\]”’]*$")

    /** Words that end in a full stop without ending a sentence. */
    private val ABBREVIATIONS =
        setOf(
            "mr",
            "mrs",
            "ms",
            "dr",
            "st",
            "jr",
            "sr",
            "mt",
            "vs",
            "feat",
            "ft",
            "vol",
            "approx",
            "etc",
            "inc",
            "ltd",
            "co",
        )

    /**
     * Catalogue abbreviations that end in a full stop without ending a sentence when a catalogue
     * number follows (#1192): `No. 2`, `Nos. 20 & 23`, `Op. 27`, `Opp. 1`, `Nr. 3`, `Hob. VIIe/1`,
     * `K. Anh. 1` (Anhang), `Sz. 106` (Bartók), `Kk. 380` (Scarlatti), `Wq. 182` (C. P. E. Bach).
     * Single letters (`K. 545`, `D. 960`, `H. 1/94`) are initials already, and `BWV`, `HWV`, `RV`,
     * `WoO` take no full stop. Before anything else they end one: `The answer was No. He left.`
     */
    private val CATALOGUE = setOf("no", "nos", "nr", "op", "opp", "hob", "anh", "sz", "kk", "wq")

    // A catalogue number after a [CATALOGUE] abbreviation and a space: a digit (`27`, `1007–1012`),
    // or Hoboken's Roman group, a letter for a subgroup maybe, then `:` or `/` and a number
    // (`XVI:52`, `VIIe/1`). A Roman numeral alone (`No. I`) is not taken: it may be the pronoun.
    // A year, four digits from 1000 to 2099 and then a space or the end, is not taken either: it
    // more likely opens the next sentence (`a photo op. 1984 saw …`) than numbers a work, whose
    // numbers in these catalogues stay below 1000; with more after it (`Op. 1984, No. 2`) it is.
    private val CATALOGUE_NUMBER = Regex("^(?:(?!(?:1[0-9]|20)[0-9]{2}(?: |$))\\p{N}|[IVXLC]+[a-z]?[:/]\\p{N})")

    private val ENTITIES =
        mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ")

    /** The sentences to quote from [summary], in the bio's order; none from a [sharedPage]'s. */
    fun sentences(summary: String?): List<String> {
        val text = plainText(summary.orEmpty())
        return if (isShared(text)) {
            emptyList()
        } else {
            split(text)
                // A sentence that trails off, inside closing quotes or brackets or not, is cut off.
                .filter { it.length in MIN_LENGTH..MAX_LENGTH && !ELLIPSIS.containsMatchIn(it) }
                .take(MAX_SENTENCES)
        }
    }

    /**
     * Whether [summary] says the page is shared by several artists of one name: it starts "There
     * are several artists …", says several artists share the name, or enumerates them: a `1)` or
     * `1.` at the start or after a space (a line break counts, see [plainText]), and a `2)` or `2.`
     * after a space. Such a page's listeners, tags and similar artists mix them all.
     *
     * Detection deliberately over-matches, since taking a shared page for one artist's is the
     * unsafe failure. Known limit: a single artist's bio with chart positions ("No. 1. … No. 2.")
     * is taken as shared; its match is then unverified at best and its sentences are dropped.
     */
    fun sharedPage(summary: String?): Boolean = isShared(plainText(summary.orEmpty()))

    private fun isShared(text: String): Boolean =
        DISAMBIGUATION.containsMatchIn(text) ||
            SHARED_NAME.containsMatchIn(text) ||
            (FIRST_ITEM.containsMatchIn(text) && SECOND_ITEM.containsMatchIn(text))

    /**
     * [html] without tags (a line break or a block's edge leaves a space), Last.fm's "Read more"
     * link (with a full stop after it or not) and licence note, entities decoded, spaces collapsed.
     */
    fun plainText(html: String): String =
        decodeEntities(stripTags(html))
            .replace(LICENCE_NOTE, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .replace(READ_MORE, "")
            .trim()

    /**
     * Sentences end at `.`, `!` or `?` (with any closing quote or bracket) before a space and a
     * capital or digit, or at the end of the text. A tail without such an end, a summary cut off
     * mid-sentence, is not a sentence and is dropped.
     */
    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in ".!?") {
                var end = i + 1
                while (end < text.length && text[end] in CLOSERS) end++
                if (endsSentence(text, i, end)) {
                    sentences += text.substring(start, end).trim()
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        text
            .substring(start)
            .trim()
            .takeIf { it.trimEnd { c -> c in CLOSERS }.lastOrNull()?.let { c -> c in ".!?" } == true }
            ?.let { sentences += it }
        return sentences
    }

    private const val CLOSERS = "\"')]”’"

    private fun endsSentence(
        text: String,
        mark: Int,
        end: Int,
    ): Boolean {
        if (end >= text.length) return true
        val next = text.getOrNull(end + 1)
        val startsNext = text[end] == ' ' && next != null && (next.isUpperCase() || next.isDigit() || next in OPENERS)
        val word = text.substring(0, mark).takeLastWhile { it.isLetterOrDigit() }
        // An initial (`J. R. R.`), an abbreviation (`St. John's`), or a catalogue abbreviation before
        // its number (`Op. 27`, [CATALOGUE]) does not end a sentence. The initial is read on the word
        // as written: lowercased, `İ` is two characters (#1192).
        val lower = word.lowercase()
        val shortened =
            text[mark] == '.' &&
                (
                    (word.length == 1 && word[0].isLetter()) ||
                        lower in ABBREVIATIONS ||
                        catalogued(text, lower, mark, end)
                )
        return startsNext && !shortened
    }

    private const val OPENERS = "\"“‘'("

    // Whether [word], lowercased and ending at the full stop at [mark], is a [CATALOGUE]
    // abbreviation with its number after the space at [end]. The end of a hyphenated word is not one
    // (`a co-op. 1984 …`, `a no-no. 2 …`): an abbreviation stands alone.
    private fun catalogued(
        text: String,
        word: String,
        mark: Int,
        end: Int,
    ): Boolean {
        if (word !in CATALOGUE || text.getOrNull(mark - word.length - 1) in HYPHENS) return false
        val from = minOf(end + 1, text.length)
        return CATALOGUE_NUMBER.containsMatchIn(text.substring(from, minOf(from + NUMBER_WINDOW, text.length)))
    }

    // A hyphen, plain, Unicode's, or non-breaking, before the last part of a hyphenated word.
    private val HYPHENS = setOf('-', '‐', '‑')

    // Enough of the text after the space for the longest [CATALOGUE_NUMBER] start (`XXVIIIb:1`).
    private const val NUMBER_WINDOW = 12

    // Tags removed; a line break or a block's edge (`<br>`, `<p>`, `</li>`) leaves a space, so the
    // words on either side stay apart.
    private fun stripTags(html: String): String {
        val out = StringBuilder(html.length)
        val tag = StringBuilder()
        var inTag = false
        for (c in html) {
            when {
                c == '<' -> {
                    inTag = true
                    tag.clear()
                }

                c == '>' && inTag -> {
                    inTag = false
                    val name =
                        tag
                            .trimStart('/')
                            .takeWhile { it.isLetterOrDigit() }
                            .toString()
                            .lowercase()
                    if (name in BREAKING_TAGS) out.append(' ')
                }

                inTag -> tag.append(c)
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private val BREAKING_TAGS = setOf("br", "p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6")

    private val ENTITY = Regex("&(#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z]+);")

    private fun decodeEntities(text: String): String =
        ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            val code =
                when {
                    name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(HEX)
                    name.startsWith("#") -> name.drop(1).toIntOrNull()
                    else -> null
                }
            when {
                code != null && code in CODE_POINTS -> codePointToString(code)
                code != null -> match.value
                else -> ENTITIES[name] ?: match.value
            }
        }

    private const val HEX = 16
    private const val MAX_CODE_POINT = 0x10FFFF
    private val CODE_POINTS = 1..MAX_CODE_POINT
    private const val BMP_LIMIT = 0x10000
    private const val SURROGATE_BITS = 10
    private const val LOW_MASK = 0x3FF

    private fun codePointToString(code: Int): String {
        if (code < BMP_LIMIT) return code.toChar().toString()
        val offset = code - BMP_LIMIT
        val high = (Char.MIN_HIGH_SURROGATE.code + (offset shr SURROGATE_BITS)).toChar()
        val low = (Char.MIN_LOW_SURROGATE.code + (offset and LOW_MASK)).toChar()
        return "$high$low"
    }
}
