package app.sleevesight.core.resolution

/**
 * When a work's Wikipedia article says it was composed (#1273): the years its infobox gives in the
 * field `composed`, else `year`, from the wikitext of the article's opening section. Only a date's
 * shapes are read: a year (`1801`), a span of years with an en dash or a hyphen (`1890–1905`, or
 * `1717–23`, its end shortened), either one after `c.` or `circa`, and the templates that write them
 * (`{{start date|1804}}`, `{{circa|1720}}`, `{{ndash}}`), with comments, references and footnote
 * templates dropped and links read as their text. Anything else (`1830s`, `May 1801`, `1801, revised
 * 1803`, an unknown template) gives nothing.
 *
 * Plain scanning, no regular expression: the phone's regex engine (ICU) reads some patterns otherwise
 * than the JVM the tests run on.
 */
internal object InfoboxDate {
    /** The years [wikitext]'s first infobox dates the composing, from and to; null when it dates none. */
    fun years(wikitext: String): Pair<Int, Int>? {
        val fields = fields(wikitext) ?: return null
        return FIELDS.firstNotNullOfOrNull { name -> fields[name]?.let(::span) }
    }

    /**
     * The named fields of [wikitext]'s first infobox (a template whose name begins `Infobox`), by
     * their names in lowercase, each value trimmed, comments dropped; null when there is none, or it
     * is not closed.
     */
    fun fields(wikitext: String): Map<String, String>? =
        infoboxStart(wikitext)?.let { start -> FieldReader(wikitext).read(start) }

    /** The years [value], a field's, dates: from and to, the same for one year; null for anything else. */
    fun span(value: String): Pair<Int, Int>? = plain(value)?.let(::shape)

    // [value] as text: references and footnotes dropped, the date templates written out, links as their
    // text, dashes and spaces as characters; null when it holds anything else, or is not closed.
    private fun plain(value: String): String? {
        val text = withoutReferences(value)
        val out = StringBuilder()
        var i = 0
        // -1 once a template or a link is not closed, or a template is not a date's.
        while (i in text.indices) i = written(text, i, out)
        val written =
            ENTITIES.entries.fold(out.toString()) { done, (entity, character) -> done.replace(entity, character) }
        return written.takeIf { i >= 0 }
    }

    // Writes the piece of [text] at [i] to [out]: a template as [template] writes it, a link as its text,
    // else a character. The index after it, or -1 for a template or a link not closed, or a template
    // that is not a date's.
    private fun written(
        text: String,
        i: Int,
        out: StringBuilder,
    ): Int =
        when {
            text.startsWith(TEMPLATE_OPEN, i) ->
                closing(text, i, TEMPLATE_OPEN, TEMPLATE_CLOSE)?.let { end ->
                    template(text.substring(i + 2, end))?.let { out.append(it) }?.let { end + 2 }
                } ?: -1
            text.startsWith(LINK_OPEN, i) ->
                closing(text, i, LINK_OPEN, LINK_CLOSE)?.let { end ->
                    out.append(text.substring(i + 2, end).substringAfterLast('|'))
                    end + 2
                } ?: -1
            else -> {
                out.append(text[i])
                i + 1
            }
        }

    // What the template [inner] (between its braces) writes, of those a date's field holds: the year of a
    // date template, a circa's year after `c.`, a dash, a space, or nothing for a footnote; null for any
    // other template.
    private fun template(inner: String): String? {
        val parts = inner.split('|')
        val name =
            parts
                .first()
                .replace('_', ' ')
                .lowercase()
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        val first = parts.drop(1).firstOrNull { '=' !in it }?.trim()
        return when (name) {
            in DATE_TEMPLATES -> first?.takeIf { it.isNotEmpty() && it.all(::isDigit) }
            in CIRCA_TEMPLATES -> first?.let { "c. $it" }
            in DASH_TEMPLATES -> " – "
            in SPACE_TEMPLATES -> " "
            in NOTE_TEMPLATES -> ""
            else -> null
        }
    }

    // [text]'s years, when it is one of a date's shapes: a year, or two with a dash between, each
    // perhaps after `c.` or `circa`; a span no longer than [LONGEST_SPAN].
    private fun shape(text: String): Pair<Int, Int>? {
        val scan = Scan(text.trim())
        val from = scan.year() ?: return null
        val to = if (scan.atEnd()) from else scan.end(from)
        return to?.takeIf { it >= from && it - from <= LONGEST_SPAN }?.let { from to it }
    }

    // The fields read, in order: `composed` (Infobox musical composition's), else `year`.
    private val FIELDS = listOf("composed", "year")

    private val DATE_TEMPLATES = setOf("start date", "start date and age", "end date", "end date and age")
    private val CIRCA_TEMPLATES = setOf("circa", "c.", "c", "ca", "ca.")
    private val DASH_TEMPLATES = setOf("ndash", "snd", "spnd", "sndash", "spaced ndash", "spaced en dash", "–")
    private val SPACE_TEMPLATES = setOf("nbsp", "sp")
    private val NOTE_TEMPLATES =
        setOf("sfn", "sfnp", "sfnm", "efn", "efn-ua", "efn-lr", "refn", "r", "rp", "cn", "citation needed", "fact")

    private val ENTITIES =
        mapOf("&ndash;" to "–", "&#8211;" to "–", "&nbsp;" to " ", "&#160;" to " ", " " to " ")

    // The longest span read as one time of composing, about a composer's working life: a longer one
    // is more likely a slip than a work's years (a revision decades on is not when it was composed).
    private const val LONGEST_SPAN = 60
}

/**
 * The fields of the infobox in [text], read from just after its `{{`: split at each `|` of the infobox's
 * own (not one inside a nested template or a link), comments dropped, up to the `}}` that closes it.
 */
private class FieldReader(
    private val text: String,
) {
    private val parts = mutableListOf<String>()
    private val part = StringBuilder()

    // The depth of templates (the infobox's own is 1) and of links.
    private var templates = 1
    private var links = 0

    // The fields, from [start]; null when the infobox, or a comment in it, is not closed.
    fun read(start: Int): Map<String, String>? {
        var i = start
        while (i in text.indices && templates > 0) i = step(i)
        return if (templates == 0) named(parts) else null
    }

    // Reads the piece at [i]: the index after it, or -1 for a comment not closed.
    private fun step(i: Int): Int =
        when {
            text.startsWith(COMMENT_OPEN, i) ->
                text
                    .indexOf(COMMENT_CLOSE, i + COMMENT_OPEN.length)
                    .let { if (it < 0) -1 else it + COMMENT_CLOSE.length }
            text.startsWith(TEMPLATE_OPEN, i) -> {
                templates++
                part.append(TEMPLATE_OPEN)
                i + 2
            }
            text.startsWith(TEMPLATE_CLOSE, i) -> {
                templates--
                if (templates == 0) parts += part.toString() else part.append(TEMPLATE_CLOSE)
                i + 2
            }
            text.startsWith(LINK_OPEN, i) -> {
                links++
                part.append(LINK_OPEN)
                i + 2
            }
            text.startsWith(LINK_CLOSE, i) -> {
                links = maxOf(0, links - 1)
                part.append(LINK_CLOSE)
                i + 2
            }
            text[i] == '|' && templates == 1 && links == 0 -> {
                parts += part.toString()
                part.clear()
                i + 1
            }
            else -> {
                part.append(text[i])
                i + 1
            }
        }
}

/** A date's text read from the start: its years, perhaps after `c.` or `circa`, and a dash between. */
private class Scan(
    private val text: String,
) {
    private var at = 0

    // The first year: four digits, not beginning with 0.
    fun year(): Int? {
        circa()
        return digits()?.takeIf { it.length == YEAR_DIGITS && it.first() != '0' }?.toInt()
    }

    // Whether only spaces are left.
    fun atEnd(): Boolean {
        spaces()
        return at >= text.length
    }

    // The year after a dash, for a span from [from], with nothing after it; null for anything else.
    fun end(from: Int): Int? {
        val dashed = at < text.length && text[at] in DASHES
        if (dashed) at++
        spaces()
        circa()
        val end = digits()
        return end?.takeIf { dashed && atEnd() }?.let { full(it, from) }
    }

    // [end] as a full year: four digits as they are, two (`1717–23`) in the century of [from], or the
    // next when that would come before it (`1899–05`).
    private fun full(
        end: String,
        from: Int,
    ): Int? =
        when (end.length) {
            YEAR_DIGITS -> end.toInt()
            SHORT_YEAR_DIGITS ->
                (from / CENTURY * CENTURY + end.toInt()).let { if (it < from) it + CENTURY else it }
            else -> null
        }

    private fun spaces() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    // `c.`, `ca.` or `circa`, in any case, and the spaces after it.
    private fun circa() {
        val prefix = CIRCA_WORDS.firstOrNull { text.regionMatches(at, it, 0, it.length, ignoreCase = true) }
        if (prefix != null) {
            at += prefix.length
            spaces()
        }
    }

    private fun digits(): String? {
        val start = at
        while (at < text.length && isDigit(text[at])) at++
        return text.substring(start, at).ifEmpty { null }
    }
}

// Just after the `{{` of [wikitext]'s first template named `Infobox…`, in any case; null for none.
private fun infoboxStart(wikitext: String): Int? {
    var from = 0
    while (true) {
        val at = wikitext.indexOf(TEMPLATE_OPEN, from)
        if (at < 0) return null
        var name = at + 2
        while (name < wikitext.length && wikitext[name].isWhitespace()) name++
        if (wikitext.regionMatches(name, INFOBOX, 0, INFOBOX.length, ignoreCase = true)) return at + 2
        from = at + 2
    }
}

// A template's fields given by name (`name = value`), the last of a name kept, as MediaWiki does; its
// name (the first part) and fields given by position left out.
private fun named(parts: List<String>): Map<String, String> =
    parts
        .drop(1)
        .filter { '=' in it }
        .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }

// [value] without its references (`<ref…>…</ref>`, `<ref … />`); one not closed runs to the end.
private fun withoutReferences(value: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        val at = value.indexOf(REFERENCE_OPEN, i, ignoreCase = true).let { if (it < 0) value.length else it }
        out.append(value, i, at)
        i = if (at == value.length) at else referenceEnd(value, at)
    }
    return out.toString()
}

// The index just after the reference at [at] in [value]: after its `/>` or its `</ref>`, or the value's
// end when it is not closed.
private fun referenceEnd(
    value: String,
    at: Int,
): Int {
    val tagEnd = value.indexOf('>', at)
    return when {
        tagEnd < 0 -> value.length
        value[tagEnd - 1] == '/' -> tagEnd + 1
        else ->
            value
                .indexOf(REFERENCE_CLOSE, tagEnd, ignoreCase = true)
                .let { if (it < 0) value.length else it + REFERENCE_CLOSE.length }
    }
}

// The index of the [close] that ends the [open] at [at] in [text], nested pairs counted; null when none does.
private fun closing(
    text: String,
    at: Int,
    open: String,
    close: String,
): Int? {
    var depth = 0
    var i = at
    while (i < text.length) {
        when {
            text.startsWith(open, i) -> {
                depth++
                i += open.length
            }
            text.startsWith(close, i) -> {
                depth--
                if (depth == 0) return i
                i += close.length
            }
            else -> i++
        }
    }
    return null
}

// ASCII digits only: `Char.isDigit` takes any script's.
private fun isDigit(c: Char): Boolean = c in '0'..'9'

// `circa` before `ca.` and `c.`, which it begins with.
private val CIRCA_WORDS = listOf("circa", "ca.", "c.")

// An en dash or a hyphen between two years.
private const val DASHES = "–-"

private const val COMMENT_OPEN = "<!--"
private const val COMMENT_CLOSE = "-->"
private const val TEMPLATE_OPEN = "{{"
private const val TEMPLATE_CLOSE = "}}"
private const val LINK_OPEN = "[["
private const val LINK_CLOSE = "]]"
private const val REFERENCE_OPEN = "<ref"
private const val REFERENCE_CLOSE = "</ref>"
private const val INFOBOX = "infobox"

private const val YEAR_DIGITS = 4
private const val SHORT_YEAR_DIGITS = 2
private const val CENTURY = 100
