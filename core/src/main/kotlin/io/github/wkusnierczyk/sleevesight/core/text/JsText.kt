package io.github.wkusnierczyk.sleevesight.core.text

/*
 * JavaScript's idea of whitespace, for the ports of the simulator's code (the normalizer, the
 * grounding check), so they read text exactly as the simulator does. Kotlin's `trim()` and
 * `isWhitespace()` follow Java and differ: they treat the other ASCII control characters as
 * whitespace and leave U+FEFF.
 */

/** JavaScript's `\s` as a regex character class: its WhiteSpace and LineTerminator characters. */
internal const val JS_WHITESPACE_CLASS: String =
    "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

private val JS_WHITESPACE = Regex(JS_WHITESPACE_CLASS)
private val JS_EDGES = Regex("^$JS_WHITESPACE_CLASS+|$JS_WHITESPACE_CLASS+$")

/** Whether [c] matches JavaScript's `\s`. */
internal fun isJsWhitespace(c: Char): Boolean = JS_WHITESPACE.matches(c.toString())

/** JavaScript's `String.prototype.trim`. */
internal fun String.trimJs(): String = replace(JS_EDGES, "")
