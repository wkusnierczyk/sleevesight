package app.sleevesight.app.ui.home

// The Markdown a model writes against the prompt's contract ("Plain text only: no headings, lists or
// markdown.", `InsightPrompt`), cleaned for display (#555): a title in `*…*` or `_…_` shows in
// italics, `**…**` and `__…__` in bold, and a heading's `#`, a list's marker, a quotation's `>`, a
// rule, code's backticks and a link's brackets and address are taken out, leaving their text. Only
// what is shown changes: the grounding check reads the reply as written, so its sentences, their
// statuses and their citations are those of the raw text, as are the report's and the cache's, and a
// card kept from before the change shows cleaned too. Citations are read first, as the check reads
// them ([segments]), and are never Markdown: a mark beside or around one works as beside or around
// any word (`*Title* [MB1]`, `*Title [MB1]*`).
//
// The rules are CommonMark's, simplified (no nested links, no HTML, no blocks beyond a line's
// marks): a run of `*` or `_` opens when a word follows it and closes when one comes before it, so
// `5 * 3`, a lone `*` and `snake_case` stay as written, and so do marks that do not pair. Runs pair
// across the sentences of a paragraph, since the check may split a title (`*Help! I Need Somebody*`).

/** A run of a sentence as shown: its text, in italics or bold, or one of its citations. */
internal sealed interface ShownRun {
    data class Text(
        val text: String,
        val italic: Boolean = false,
        val bold: Boolean = false,
    ) : ShownRun

    data class Cite(
        val id: String,
    ) : ShownRun
}

/**
 * The sentences of one paragraph, as the grounding check split them, as shown: each its runs of
 * text, its Markdown cleaned and its titles in italics, and its citations where they were.
 */
internal fun shown(sentences: List<String>): List<List<ShownRun>> {
    val paragraph = Paragraph(sentences)
    CodeSpans(paragraph).run()
    Links(paragraph).run()
    LineMarks(paragraph).run()
    Emphasis(paragraph).run()
    return paragraph.output()
}

/** Whether a sentence's runs show nothing: no citation, and no text but white space. */
internal fun List<ShownRun>.isBlank(): Boolean = all { it is ShownRun.Text && it.text.isBlank() }

// One paragraph's characters, its sentences joined by a space and each citation one character, and
// what the Markdown makes of each: taken out, code (shown as written), italic, bold.
private class Paragraph(
    private val sentences: List<String>,
) {
    private val chars = StringBuilder()

    // The sentence of each character, or -1 for the space between two.
    private val sentenceOf = mutableListOf<Int>()
    private val cites = mutableMapOf<Int, String>()

    init {
        sentences.forEachIndexed { s, sentence ->
            if (s > 0) add(' ', -1)
            for (segment in segments(sentence)) {
                when (segment) {
                    is Segment.Text -> segment.text.forEach { add(it, s) }
                    is Segment.Cite -> {
                        cites[chars.length] = segment.id
                        add(CITE_MARK, s)
                    }
                }
            }
        }
    }

    val length: Int = chars.length
    val removed = BooleanArray(length)
    val literal = BooleanArray(length)
    val italic = BooleanArray(length)
    val bold = BooleanArray(length)

    private fun add(
        c: Char,
        sentence: Int,
    ) {
        chars.append(c)
        sentenceOf += sentence
    }

    operator fun get(i: Int): Char = chars[i]

    fun text(
        from: Int,
        to: Int,
    ): String = chars.substring(from, to)

    fun isCite(i: Int): Boolean = i in cites

    // Plain text: neither taken out, nor code, nor a citation.
    fun isPlain(i: Int): Boolean = !removed[i] && !literal[i] && !isCite(i)

    fun remove(range: IntRange) {
        for (k in range) removed[k] = true
    }

    /**
     * The character shown next to [i] in the direction of [step], past what is taken out, or a
     * space past the paragraph's ends; a citation reads as punctuation, as its brackets would.
     */
    fun neighbour(
        i: Int,
        step: Int,
    ): Char {
        var k = i + step
        while (k in 0 until length && removed[k]) k += step
        return when {
            k !in 0 until length -> ' '
            isCite(k) -> ']'
            else -> chars[k]
        }
    }

    // How many plain [c] start at [i], or 0 unless [i] starts their run.
    fun runAt(
        i: Int,
        c: Char,
    ): Int {
        val inRun = i > 0 && chars[i - 1] == c && isPlain(i - 1)
        if (chars[i] != c || !isPlain(i) || inRun) return 0
        var k = i
        while (k < length && chars[k] == c && isPlain(k)) k++
        return k - i
    }

    fun output(): List<List<ShownRun>> {
        val out = List(sentences.size) { mutableListOf<ShownRun>() }
        val run = StringBuilder()
        var style = Style(-1, italic = false, bold = false)

        fun flush() {
            if (run.isNotEmpty()) out[style.sentence] += ShownRun.Text(run.toString(), style.italic, style.bold)
            run.clear()
        }
        for (i in 0 until length) {
            val s = sentenceOf[i]
            if (removed[i] || s < 0) continue
            val id = cites[i]
            val at = Style(s, italic[i], bold[i])
            if (id != null || at != style) flush()
            if (id != null) out[s] += ShownRun.Cite(id) else run.append(chars[i])
            style = at
        }
        flush()
        return out
    }

    private data class Style(
        val sentence: Int,
        val italic: Boolean,
        val bold: Boolean,
    )

    companion object {
        // A citation's place among the paragraph's characters: one character that is no Markdown.
        const val CITE_MARK: Char = '￼'
    }
}

// Code (`` `x` ``): its backticks taken out, each run paired with the next run as long, and what is
// between them shown as written; a run with no pair stays.
private class CodeSpans(
    private val p: Paragraph,
) {
    fun run() {
        var i = 0
        while (i < p.length) {
            val run = p.runAt(i, '`')
            val close = if (run == 0) null else closing(i + run, run)
            if (close == null) {
                i += maxOf(run, 1)
            } else {
                p.remove(i until i + run)
                for (k in i + run until close) p.literal[k] = true
                p.remove(close until close + run)
                i = close + run
            }
        }
    }

    // Where the next run of exactly [run] backticks starts, from [from], or null.
    private fun closing(
        from: Int,
        run: Int,
    ): Int? {
        var k = from
        while (k < p.length) {
            val next = p.runAt(k, '`')
            if (next == run) return k
            k += maxOf(next, 1)
        }
        return null
    }
}

// A link (`[text](address)`, an image's `![text](address)`) as its text, and a citation written as
// one (`[MB1](https://…)`) as the citation.
private class Links(
    private val p: Paragraph,
) {
    fun run() {
        for (i in 0 until p.length) {
            if (p[i] == '[' && p.isPlain(i)) {
                link(i)
            } else if (p.isCite(i)) {
                address(i + 1, web = true)?.let { end -> p.remove(i + 1 until end) }
            }
        }
    }

    private fun link(open: Int) {
        var close = open + 1
        while (close < p.length && !(p[close] == ']' && p.isPlain(close))) {
            if ((p[close] == '[' || p[close] == '\n') && p.isPlain(close)) return
            close++
        }
        val end = address(close + 1, web = false) ?: return
        p.remove(open..open)
        if (open > 0 && p[open - 1] == '!' && p.isPlain(open - 1)) p.remove(open - 1 until open)
        p.remove(close until end)
    }

    // Where a link's address, `(…)` at [at] with no space in it, ends, or null; after a citation
    // only a web address ([web]), so `[MB1](1973)` keeps its year.
    private fun address(
        at: Int,
        web: Boolean,
    ): Int? {
        val opens = at < p.length && p[at] == '(' && p.isPlain(at)
        var k = at + 1
        while (opens && inAddress(k)) k++
        val target = if (opens && k < p.length && p[k] == ')') p.text(at + 1, k) else ""
        val isWeb = "://" in target || target.startsWith("www.")
        return (k + 1).takeIf { target.isNotEmpty() && (isWeb || !web) }
    }

    // Whether [k] is a character of an address: plain, no space, and no `)` yet.
    private fun inAddress(k: Int): Boolean = k < p.length && p[k] != ')' && !p[k].isWhitespace() && p.isPlain(k)
}

// A line's marks, at the paragraph's start or after a line break: a heading's `#`s, a list's `-`,
// `*`, `+` or `1.` and a quotation's `>`, each before a space (a heading's also before the line's
// end), and a rule (`---`) alone on its line.
private class LineMarks(
    private val p: Paragraph,
) {
    fun run() {
        for (i in 0 until p.length) if (i == 0 || p[i - 1] == '\n') line(i)
    }

    private fun line(start: Int) {
        var i = start
        while (i < p.length && p[i] == ' ' && i - start < MAX_INDENT) i++
        var end = i
        while (end < p.length && p[end] != '\n') end++
        val line = p.text(i, end)
        // A rule goes with its line break, so no empty line is left in its place.
        val mark =
            when {
                RULE.matches(line) -> if (end < p.length) line.length + 1 else line.length
                else -> LINE_MARK.find(line)?.value?.length ?: 0
            }
        if (mark > 0 && (i until i + mark).all(p::isPlain)) p.remove(start until i + mark)
    }

    companion object {
        const val MAX_INDENT: Int = 3
        val RULE = Regex("(?:-[ \\t]*){3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,}")
        val LINE_MARK = Regex("^(?:#{1,6}(?: +|$)|(?:[-*+>]|[0-9]{1,2}[.)]) +)")
    }
}

// CommonMark's delimiter runs (`*`, `_`), paired as its *process emphasis* pairs them: each closer
// with the nearest opener of its kind before it, two marks of each for bold where both have two.
private class Emphasis(
    private val p: Paragraph,
) {
    private class Run(
        val char: Char,
        val start: Int,
        val length: Int,
        val opens: Boolean,
        val closes: Boolean,
    ) {
        // The marks not paired yet: [count] of them, after the [left] a closer has used.
        var left = 0
        var count = length
    }

    fun run() {
        val stack = mutableListOf<Run>()
        for (run in runs()) {
            if (run.closes) pair(run, stack)
            if (run.opens && run.count > 0) stack += run
        }
    }

    // Pairs [closer] with the openers in [stack], the nearest first, while it has marks left.
    private fun pair(
        closer: Run,
        stack: MutableList<Run>,
    ) {
        var at = stack.indexOfLast { it.char == closer.char && fits(it, closer) }
        while (at >= 0 && closer.count > 0) {
            val opener = stack[at]
            val use = if (opener.count >= 2 && closer.count >= 2) 2 else 1
            val openEnd = opener.start + opener.left + opener.count
            val closeStart = closer.start + closer.left
            p.remove(openEnd - use until openEnd)
            p.remove(closeStart until closeStart + use)
            val style = if (use == 2) p.bold else p.italic
            for (k in openEnd until closeStart) style[k] = true
            opener.count -= use
            closer.count -= use
            closer.left += use
            // The runs between the two pair with nothing now; an opener used up leaves the stack.
            while (stack.size > at + 1) stack.removeAt(stack.lastIndex)
            if (opener.count == 0) stack.removeAt(at)
            at = stack.indexOfLast { it.char == closer.char && fits(it, closer) }
        }
    }

    // CommonMark's rule of 3: in `*a**b*`, the single marks pair, not one with two.
    private fun fits(
        opener: Run,
        closer: Run,
    ): Boolean {
        val either = (opener.opens && opener.closes) || (closer.opens && closer.closes)
        val sum = opener.length + closer.length
        return !either || sum % THREE != 0 || (opener.length % THREE == 0 && closer.length % THREE == 0)
    }

    private fun runs(): List<Run> {
        val out = mutableListOf<Run>()
        for (i in 0 until p.length) {
            val c = p[i]
            val length = if (c == '*' || c == '_') p.runAt(i, c) else 0
            if (length > 0) out += run(c, i, length)
        }
        return out
    }

    // A run of [length] [c] at [start], which opens when a word follows it and closes when one
    // comes before it; an `_` inside a word (`snake_case`) does neither.
    private fun run(
        c: Char,
        start: Int,
        length: Int,
    ): Run {
        val before = p.neighbour(start, -1)
        val after = p.neighbour(start + length - 1, 1)
        val left = !after.isWhitespace() && (!after.isPunct() || before.isWhitespace() || before.isPunct())
        val right = !before.isWhitespace() && (!before.isPunct() || after.isWhitespace() || after.isPunct())
        val opens = if (c == '*') left else left && (!right || before.isPunct())
        val closes = if (c == '*') right else right && (!left || after.isPunct())
        return Run(c, start, length, opens, closes)
    }

    private fun Char.isPunct(): Boolean = !isLetterOrDigit() && !isWhitespace()

    companion object {
        const val THREE: Int = 3
    }
}
