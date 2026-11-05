package app.sleevesight.sources.language

import app.sleevesight.sources.wikipedia.WikipediaSource

/**
 * The sentences quoted from a Wikipedia article's lead in the track's language (#902), as the
 * English adapter quotes them (`WikipediaSource.sentences`): the first sentence, which defines the
 * subject, and those that name one of the subjects, each at most
 * [WikipediaSource.MAX_SENTENCE_LENGTH] characters, at most [WikipediaSource.MAX_SENTENCES]. A
 * subject is found as it is written, letter case aside, not as a whole word: the scripts searched in
 * do not all put spaces between words, nor case their letters.
 */
internal object LanguageSentences {
    /**
     * [lead]'s sentences that name one of [subjects], the first whether or not it names one. With
     * [stops] false, for a script that ends no sentence with a mark (Thai), its stretches between
     * spaces stand for sentences ([stretches]).
     */
    fun of(
        lead: String,
        subjects: List<String>,
        stops: Boolean = true,
    ): List<String> {
        val wanted = subjects.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val paragraphs = lead.split('\n').map(WikipediaSource::tidy).filter { it.isNotEmpty() }
        val cut: (String) -> List<String> = if (stops) ::split else ::stretches
        val first = paragraphs.firstOrNull()?.let { cut(it).firstOrNull() }
        return paragraphs
            .flatMap(cut)
            .filter { sentence -> sentence == first || wanted.any { it in sentence.lowercase() } }
            .filter { it.length <= WikipediaSource.MAX_SENTENCE_LENGTH }
            .take(WikipediaSource.MAX_SENTENCES)
    }

    /**
     * [paragraph]'s sentences. A sentence ends at a full stop of its script, with any closing quote
     * or bracket after it: `.`, `!`, `?`, the danda `।` and double danda `॥` (Devanagari, Bengali,
     * Gurmukhi and the other Indic scripts), the Arabic `؟` and Urdu `۔`, the Armenian `։`, and the
     * ideographic `。`, `！`, `？`, which need no space after them. Any other mark ends one only at
     * the paragraph's end or before a space and a letter that is not lower case (a capital, or a
     * letter of a script without case), a digit or an opening quote, and a full stop after a single
     * letter of a cased script (an initial) never does. A tail without an end, a lead cut off, is
     * dropped. As in English (`WikipediaSource.split`), a sentence never ends inside a quotation (in
     * the quotation marks of the languages searched, [balanced]), and
     * a paragraph with a quotation mark straight after a digit (an inch mark, maybe) gives none.
     */
    fun split(paragraph: String): List<String> {
        if (DIGIT_QUOTE.containsMatchIn(paragraph)) return emptyList()
        val sentences = mutableListOf<String>()
        var open: String? = null
        for (piece in pieces(paragraph)) {
            val joined = open?.let { "$it $piece" } ?: piece
            if (balanced(joined)) {
                sentences += joined
                open = null
            } else {
                open = joined
            }
        }
        return sentences
    }

    /**
     * [paragraph], in a script that ends no sentence with a mark (Thai, which puts a space where
     * another script puts a full stop), as stretches of at most [WikipediaSource.MAX_SENTENCE_LENGTH]
     * characters, each ending at a space: as many of its space-separated parts as fit, never cut
     * before a part that opens with a digit (a year after `พ.ศ.`), so each is a verbatim quote of
     * whole parts. A part longer than that alone is left out.
     */
    fun stretches(paragraph: String): List<String> {
        // The parts between spaces, a part opening with a digit kept with the one before it.
        val parts = mutableListOf<String>()
        for (part in paragraph.split(' ').filter { it.isNotEmpty() }) {
            if (part.first().isDigit() && parts.isNotEmpty()) parts[parts.lastIndex] += " $part" else parts += part
        }
        val stretches = mutableListOf<String>()
        var current = ""
        for (part in parts) {
            val joined = if (current.isEmpty()) part else "$current $part"
            if (joined.length <= WikipediaSource.MAX_SENTENCE_LENGTH || current.isEmpty()) {
                current = joined
            } else {
                stretches += current
                current = part
            }
        }
        if (current.isNotEmpty()) stretches += current
        return stretches.filter { it.length <= WikipediaSource.MAX_SENTENCE_LENGTH }
    }

    // The paragraph cut at each sentence end, quotations aside.
    private fun pieces(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            val end = endAt(text, i)
            if (i >= start && end != null) {
                text
                    .substring(start, end)
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { pieces += it }
                start = end
            }
        }
        return pieces
    }

    // Where the sentence ends if the mark at [mark] ends it, closing quotes and brackets after it
    // included; null if there is no mark there, or it ends none.
    private fun endAt(
        text: String,
        mark: Int,
    ): Int? {
        if (text[mark] !in ENDS) return null
        var end = mark + 1
        while (end < text.length && text[end] in CLOSERS) end++
        return end.takeIf { endsSentence(text, mark, it) }
    }

    private fun endsSentence(
        text: String,
        mark: Int,
        end: Int,
    ): Boolean {
        val next = text.substring(end).trimStart().firstOrNull()
        val starts = next == null || next.isDigit() || next in OPENERS || (next.isLetter() && !next.isLowerCase())
        val spaced = end < text.length && text[end].isWhitespace() && starts && !shortened(text, mark)
        return end >= text.length || text[mark] in UNSPACED_ENDS || spaced
    }

    // Whether the full stop at [mark] shortens a word, ending no sentence: after a single capital of a
    // cased script (an initial).
    private fun shortened(
        text: String,
        mark: Int,
    ): Boolean {
        val before = text.getOrNull(mark - 1) ?: return false
        return text[mark] == '.' && before.isUpperCase() && text.getOrNull(mark - 2)?.isLetter() != true
    }

    /**
     * Whether every quotation [text] opens it also closes, in the quotation marks of the languages
     * searched: straight `"` in pairs; `«…»`, `‹…›`, `「…」` and `『…』` balanced; and the curly doubles
     * as each language pairs them, `„…“` (German) or `„…”` (Polish, Hungarian) when a low `„` opens,
     * else `“…”`, else `”…”` (Swedish, Finnish) in pairs. Single curly quotes are left out, as an
     * apostrophe is one of them.
     */
    internal fun balanced(text: String): Boolean {
        fun count(c: Char) = text.count { it == c }
        val low = count('„')
        val left = count('“')
        val right = count('”')
        val doubles =
            when {
                low > 0 -> low == left + right
                left > 0 -> left == right
                else -> right % 2 == 0
            }
        return count('"') % 2 == 0 && doubles && PAIRS.all { (open, close) -> count(open) == count(close) }
    }

    private val PAIRS = listOf('«' to '»', '‹' to '›', '「' to '」', '『' to '』')

    private const val ENDS = ".!?।॥؟۔։。！？"
    private const val UNSPACED_ENDS = "。！？"
    private const val CLOSERS = "\"')]”’“»›」』）"
    private const val OPENERS = "\"'(“‘„‚«‹「『（"

    // As `WikipediaSource`'s: a closing quote straight after any number, an inch mark or a quoted
    // number's end, which would throw the quotations' pairing off.
    private val DIGIT_QUOTE = Regex("\\p{N}[\"”]")
}
