package app.sleevesight.core.text

/*
 * JavaScript's idea of whitespace, for the ports of the simulator's code (the normalizer, the
 * grounding check), so they read text exactly as the simulator does. Kotlin's `trim()` and
 * `isWhitespace()` follow Java and differ: they treat the other ASCII control characters as
 * whitespace and leave U+FEFF.
 */

/** JavaScript's `\s` as a regex character class: its WhiteSpace and LineTerminator characters. */
internal const val JS_WHITESPACE_CLASS: String =
    "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

/**
 * Whether [c] matches JavaScript's `\s`: one of the characters [JS_WHITESPACE_CLASS] names. Told by
 * the character, not by the regex, which the scans of a reply ask for each of its characters: a regex
 * starts its engine for each (#1318), and on Android each start is a call into ICU.
 */
internal fun isJsWhitespace(c: Char): Boolean =
    when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F',
        '\u3000', '\uFEFF',
        -> true
        else -> c in '\u2000'..'\u200A'
    }

/**
 * JavaScript's `String.prototype.trim`, as a scan from both ends. Not a regex: `$` also matches
 * before a final line terminator, U+0085 among them, which JavaScript's `\s` does not include.
 */
internal fun String.trimJs(): String {
    var start = 0
    var end = length
    while (start < end && isJsWhitespace(this[start])) start++
    while (end > start && isJsWhitespace(this[end - 1])) end--
    return substring(start, end)
}
