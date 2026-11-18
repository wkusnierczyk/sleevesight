package app.sleevesight.core.text

/**
 * Where each match of this regex in [text] is, in order, as `findAll(text)` finds them, but read with
 * one start of the regex engine (#1351). `findAll`, `find(text, start)` in a loop and `replace` with a
 * transform start the engine again for each match, and on Android each start copies the whole text
 * into ICU, so a text of N characters with M matches cost N×M there. A `replace` with a replacement
 * string runs one matcher over the text: each match is marked off with a character the text does not
 * hold, and the marks are read back. `java.util.regex.Matcher` is no option: `:core`'s `main` is
 * portable Kotlin, with no JVM API.
 *
 * One matcher goes on from where its last match ended, as `findAll`'s new starts do, but not after
 * an empty match (it steps over a whole surrogate pair, `findAll` over one character) nor for `\G`,
 * which a new start reads afresh. So a regex with `\G`, or a pass that finds an empty match, is read
 * with `findAll` after all: none of the regexes read here matches empty.
 */
internal fun Regex.rangesIn(text: String): List<IntRange> = onePass(text) ?: findAll(text).map { it.range }.toList()

// Where each match of this regex in [text] is, read with one matcher; null where that would not be
// what a new start for each match finds ([rangesIn]).
private fun Regex.onePass(text: String): List<IntRange>? {
    val mark = absentFrom(text)
    val ranges = if (mark == null || "\\G" in pattern) null else marked(text, mark)
    return ranges?.takeIf { found -> found.none { it.isEmpty() } }
}

// Where each match of this regex in [text] is, read with one matcher, [mark] around each.
private fun Regex.marked(
    text: String,
    mark: Char,
): List<IntRange> {
    val marked = replace(text, "$mark\$0$mark")
    val ranges = mutableListOf<IntRange>()
    var at = 0
    var start = -1
    for (c in marked) {
        if (c != mark) {
            at++
        } else if (start < 0) {
            start = at
        } else {
            ranges += start until at
            start = -1
        }
    }
    return ranges
}

/** What each match of this regex in [text] holds, in order, as `findAll(text)` finds them ([rangesIn]). */
internal fun Regex.valuesIn(text: String): List<String> = rangesIn(text).map { text.substring(it.first, it.last + 1) }

/**
 * [text] with each match of [regex] replaced by what [transform] makes of where it is, as
 * `regex.replace(text) { … }` replaces them, with one start of the regex engine ([rangesIn]); by
 * `replace` itself where [rangesIn] falls back, as its empty matches differ from `findAll`'s (an
 * empty match at the end after one that ends there).
 */
internal fun replaceOnce(
    text: String,
    regex: Regex,
    transform: (IntRange) -> CharSequence,
): String =
    regex.onePass(text)?.let { replaceRanges(text, it, transform) }
        ?: regex.replace(text) { transform(it.range) }

/** [text] with each of [ranges], in order and apart, replaced by what [transform] makes of it. */
internal fun replaceRanges(
    text: String,
    ranges: List<IntRange>,
    transform: (IntRange) -> CharSequence,
): String {
    if (ranges.isEmpty()) return text
    val out = StringBuilder(text.length)
    var from = 0
    for (range in ranges) {
        out.append(text, from, range.first).append(transform(range))
        from = range.last + 1
    }
    return out.append(text, from, text.length).toString()
}

// A character [text] does not hold, to mark matches off with: U+0001 when it is not there, as it
// seldom is, else the first of the others not there; null only for a text that holds every one.
private fun absentFrom(text: String): Char? {
    if (text.indexOf(FIRST_MARK) < 0) return FIRST_MARK
    val held = BooleanArray(Char.MAX_VALUE.code + 1)
    for (c in text) held[c.code] = true
    return (1..Char.MAX_VALUE.code).firstOrNull { !held[it] && it.toChar() !in IN_REPLACEMENT }?.toChar()
}

// What a replacement string reads as its own: `$` and `\`, and digits, which after `$0` would be read
// as part of the group's number (`$00`).
private const val IN_REPLACEMENT = "$\\0123456789"

private const val FIRST_MARK = '\u0001'
