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
    /** [lead]'s sentences that name one of [subjects], the first whether or not it names one. */
    fun of(
        lead: String,
        subjects: List<String>,
    ): List<String> {
        val wanted = subjects.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val paragraphs = lead.split('\n').map(WikipediaSource::tidy).filter { it.isNotEmpty() }
        val first = paragraphs.firstOrNull()?.let { split(it).firstOrNull() }
        return paragraphs
            .flatMap(::split)
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
     * dropped. As in English (`WikipediaSource.split`), a sentence never ends inside a quotation, and
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
        val before = text.getOrNull(mark - 1)
        val initial =
            text[mark] == '.' &&
                before != null &&
                before.isUpperCase() &&
                text.getOrNull(mark - 2)?.isLetter() != true
        val spaced = end < text.length && text[end].isWhitespace() && starts && !initial
        return end >= text.length || text[mark] in UNSPACED_ENDS || spaced
    }

    private fun balanced(text: String): Boolean =
        text.count { it == '"' } % 2 == 0 && text.count { it == '“' } == text.count { it == '”' }

    private const val ENDS = ".!?।॥؟۔։。！？"
    private const val UNSPACED_ENDS = "。！？"
    private const val CLOSERS = "\"')]”’」』）"
    private const val OPENERS = "\"'(“‘「『（"

    // As `WikipediaSource`'s: a closing quote straight after any number, an inch mark or a quoted
    // number's end, which would throw the quotations' pairing off.
    private val DIGIT_QUOTE = Regex("\\p{N}[\"”]")
}
