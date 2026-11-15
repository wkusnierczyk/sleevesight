package app.sleevesight.core.resolution

import app.sleevesight.core.text.isJsWhitespace

// The scans that read a MusicBrainz title of a part of a work (#1289, `quotedPart` in
// `RecordingFallbacks.kt`), each reading the title once over rather than backtracking as a regex would
// on a hostile one (#1304).

/**
 * Where the first quoted title in [text] opens and closes (#1304): in English quotes, straight ones,
 * German ones (closed by either English quote) or guillemets, with at least one character between,
 * none of them its own closing quote. Null when there is none. A scan that reads [text] at most once
 * over for each kind of quote, however many quotes open and never close.
 */
internal fun firstQuoted(text: CharSequence): IntRange? {
    // Where the closing quote last found for each kind is: `NOT_LOOKED_UP` before any opening quote of
    // that kind, `NONE_LEFT` once none follows.
    val closes = IntArray(PIECE_QUOTES.size) { NOT_LOOKED_UP }
    for (open in text.indices) {
        val c = text[open]
        val kind = PIECE_QUOTES.indexOfFirst { it.first == c }
        if (kind >= 0 && closes[kind] != NONE_LEFT && closes[kind] <= open) {
            closes[kind] = text.indexOfAny(PIECE_QUOTES[kind].second, open + 1)
        }
        if (kind >= 0 && closes[kind] > open + 1) return open..closes[kind]
    }
    return null
}

/**
 * Whether [text], what follows a part's quoted title, is what may follow it (#1304): nothing, or one
 * parenthetical with no parenthesis inside, with whitespace around. A scan that reads [text] once.
 */
internal fun fitsPieceTail(text: CharSequence): Boolean {
    var at = skipWhitespace(text, 0)
    if (at < text.length && text[at] == '(') {
        at++
        while (at < text.length && text[at].let { it != '(' && it != ')' }) at++
        if (at == text.length || text[at] == '(') return false
        at = skipWhitespace(text, at + 1)
    }
    return at == text.length
}

// Where the run of JavaScript's whitespace in [text] from [from] ends.
private fun skipWhitespace(
    text: CharSequence,
    from: Int,
): Int {
    var at = from
    while (at < text.length && isJsWhitespace(text[at])) at++
    return at
}

// The opening quotes of a part's quoted title ([firstQuoted]), each with the closing ones it takes.
private val PIECE_QUOTES =
    listOf(
        '“' to charArrayOf('”'),
        '"' to charArrayOf('"'),
        '„' to charArrayOf('“', '”'),
        '«' to charArrayOf('»'),
    )

// [firstQuoted]'s marks for a kind of quote: its closing quote not looked for yet, or none left.
private const val NOT_LOOKED_UP = -2
private const val NONE_LEFT = -1
