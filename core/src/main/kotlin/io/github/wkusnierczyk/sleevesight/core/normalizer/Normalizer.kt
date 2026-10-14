package io.github.wkusnierczyk.sleevesight.core.normalizer

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.NowPlaying
import io.github.wkusnierczyk.sleevesight.core.text.JS_WHITESPACE_CLASS
import io.github.wkusnierczyk.sleevesight.core.text.trimJs

/**
 * Turns a player's title and artist strings into the song's plain title, its credited artists
 * and a lookup key, keeping what it removed (`design/design.md`, *Normalizer*).
 *
 * A port of the simulator's `normalize`, rule for rule, with three deviations:
 * - The design's table says a `(feat. X)` in the title moves X to the artists, and the simulator
 *   only noted it; here X is added after the credited artists unless it is one of them already.
 *   On the fixture playlist the result is the same, since its one featured artist is credited.
 * - An empty artist string gives an empty [NormalizedTrack.primary] and a key starting `|`, where
 *   the simulator gave `undefined` and `undefined|`. With a `(feat. X)` in the title, X becomes the
 *   primary artist and goes into the key.
 * - The lookup key keeps every letter and digit, in any script, and `+` (#72). The simulator kept
 *   ASCII letters and digits only, so every Japanese, Cyrillic or Greek title and artist keyed as
 *   `|`, "First Love" by two Japanese artists shared one key, and "C++" and "C" by AC/DC shared
 *   another; as a cache key, one track would be served another's evidence. `+` is kept because it
 *   is part of a title ("C++", "1+1") where other punctuation is not. See [foldForKey].
 *   For ASCII input, and for every fixture track, the key is the simulator's; it differs only where
 *   the simulator dropped a non-ASCII character (`lıve`, dotless, keys as `lıve`, not `lve`).
 *
 * The patterns spell out JavaScript's `\s`, `\w`, `\d` and `\b`, trimming uses JavaScript's
 * whitespace, and case is matched in ASCII only, as JavaScript's `/i` without `u` does (no
 * `IGNORE_CASE`, which on the JVM and in ICU also folds `ſ` to `s`, `ı` to `i` and the Kelvin
 * sign to `k`). So the port matches the simulator exactly, and every platform's regex engine
 * (Android's is ICU, where `\w` and `\s` are Unicode-aware) reads the patterns the same way.
 */
class Normalizer(
    private val accentFolder: AccentFolder,
) {
    fun normalize(nowPlaying: NowPlaying): NormalizedTrack = normalize(nowPlaying.title, nowPlaying.artists)

    fun normalize(
        title: String,
        artists: String,
    ): NormalizedTrack {
        val notes = mutableListOf<String>()
        var t = title.trimJs()
        var from: String? = null
        var variant: String? = null
        val credited = splitArtists(artists).toMutableList()

        SUFFIX.find(t)?.let { m ->
            notes += "drop \"${m.groupValues[1]}\""
            t = t.substring(0, m.range.first)
        }
        FEATURING.find(t)?.let { m ->
            notes += "feat. → artists"
            t = t.replaceFirst(m.value, "")
            for (name in splitArtists(m.groupValues[1]).map { it.trimJs() }.filter { it.isNotEmpty() }) {
                if (credited.none { it.equals(name, ignoreCase = true) }) credited += name
            }
        }
        (FROM_PARENTHESIZED.find(t) ?: FROM_SUFFIX.find(t))?.let { m ->
            from = m.groupValues[1]
            notes += "from: ${m.groupValues[1]}"
            t = t.replaceFirst(m.value, "")
        }
        VARIANT.find(t)?.let { m ->
            variant = m.groupValues[1]
            notes += "variant: ${m.groupValues[1]}"
            t = t.replaceFirst(m.value, "")
        }

        val primary = credited.firstOrNull().orEmpty()
        return NormalizedTrack(
            title = t.trimJs(),
            artists = credited,
            primary = primary,
            from = from,
            variant = variant,
            notes = notes,
            lookupKey = lookupKey(primary, t),
        )
    }

    private fun lookupKey(
        primary: String,
        title: String,
    ): String {
        // Folded together, so the space between a dropped trailing character and `|` stays as the
        // simulator has it; part by part only when a part keeps nothing (see [foldForKey]).
        val whole = foldCore("$primary|$title")
        val emptyPart =
            foldCore(primary).isEmpty() && primary.isNotBlank() || foldCore(title).isEmpty() && title.isNotBlank()
        return if (emptyPart) foldForKey(primary) + "|" + foldForKey(title) else whole
    }

    /**
     * [text] as keys spell it, for [NormalizedTrack.lookupKey] and for the other parts of a cache
     * key (the retrieval cache's artists and source work), so that equivalent spellings (NFC and
     * NFD, fullwidth and plain, any case) give one key and different words do not.
     *
     * Lowercased, decomposed (NFKD) and lowercased again, so a compatibility form such as a
     * fullwidth `Ａ` keys as `a`. Then kept: letters and digits in any script, `+` and `|`; each run
     * of anything else becomes one space, trimmed at the ends. A combining mark is kept with the
     * letter it follows, except the accents U+0300 to U+036F on a Latin or Greek letter, which are
     * folded away (`é` keys as `e`, `ώ` as `ω`): elsewhere a mark tells letters apart, as the
     * breve does Cyrillic `й` from `и` and the voicing mark `ガ` from `カ`. A mark on a character
     * that is not kept goes with it, so an emoji's variation selector does not stay behind.
     * Variation selectors and tags (U+FE00 to U+FE0F, U+E0000 to U+E007F, U+E0100 to U+E01EF) and
     * enclosing marks (the keycap of `1️⃣`) are dropped wherever they are, leaving the letter before
     * them and any mark after it as they were: `葛󠄀飾` keys as `葛飾`.
     *
     * Supplementary characters (surrogate pairs) are kept whole, except in the symbol and emoji
     * blocks (U+1D000 to U+1D24F, U+1F000 to U+1FAFF), which are dropped like the symbols of the
     * Basic Multilingual Plane: the standard library's portable character classes do not classify
     * them, so the blocks are listed instead. Character classes, not a `\p{L}` regex, which is not
     * portable (architecture, Multiplatform readiness).
     *
     * Text that keeps nothing by these rules but is not blank (a title of emoji or symbols only,
     * `😀`, `★`, an artist `!!!`) keys as its own characters, lowercased, without whitespace, so two
     * such tracks do not share a key.
     */
    fun foldForKey(text: String): String {
        val folded = foldCore(text)
        return if (folded.isEmpty() && text.isNotBlank()) text.lowercase().filterNot { it.isWhitespace() } else folded
    }

    private fun foldCore(text: String): String {
        val folded = accentFolder.decompose(text.lowercase()).lowercase()
        val key = KeyBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val c = folded[i]
            val pair = c.isHighSurrogate() && i + 1 < folded.length && folded[i + 1].isLowSurrogate()
            val code = if (pair) codePoint(c, folded[i + 1]) else c.code
            when {
                IGNORED.any { code in it } || c.category == CharCategory.ENCLOSING_MARK -> Unit
                pair -> key.base(folded.substring(i, i + 2), !inSymbolBlocks(code), false)
                c.category in MARKS -> key.mark(c)
                else -> key.base(c.toString(), isKeyChar(c), foldsAccents(c))
            }
            i += if (pair) 2 else 1
        }
        return key.toString()
    }

    // The key as it is built: kept characters, one space for each run of dropped ones, and each mark
    // kept or dropped by the character it follows.
    private class KeyBuilder(
        capacity: Int,
    ) {
        private val key = StringBuilder(capacity)
        private var gap = false
        private var baseKept = false
        private var baseFolds = false

        fun base(
            text: String,
            kept: Boolean,
            folds: Boolean,
        ) {
            baseKept = kept
            baseFolds = folds
            if (!kept) {
                gap = true
                return
            }
            if (gap && key.isNotEmpty()) key.append(' ')
            gap = false
            key.append(text)
        }

        fun mark(c: Char) {
            val folded = baseFolds && c in FOLDED_ACCENTS
            if (baseKept && !folded) key.append(c)
        }

        override fun toString(): String = key.toString()
    }

    private fun isKeyChar(c: Char): Boolean = c == '|' || c == '+' || c.isLetterOrDigit()

    // Latin (with ASCII digits and punctuation) and Greek letters, whose accents are folded away.
    private fun foldsAccents(c: Char): Boolean =
        c.code < LATIN_END || c.code in LATIN_ADDITIONAL || c.code in GREEK || c.code in GREEK_EXTENDED

    private fun codePoint(
        high: Char,
        low: Char,
    ): Int =
        SUPPLEMENTARY_BASE + ((high.code - HIGH_SURROGATE_BASE) shl SURROGATE_BITS) + (low.code - LOW_SURROGATE_BASE)

    private fun inSymbolBlocks(codePoint: Int): Boolean = SYMBOL_BLOCKS.any { codePoint in it }

    private fun splitArtists(artists: String): List<String> = artists.split(ARTIST_SEPARATOR).filter { it.isNotEmpty() }

    private companion object {
        // JavaScript's `\s`.
        const val S = JS_WHITESPACE_CLASS

        val SUFFIX =
            Regex(
                "$S+-$S+((?:[0-9]{4}$S+)?${ci("remaster")}(?:${ci("ed")})?(?:$S+[0-9]{4})?|" +
                    "${ci("demo")}|${ci("radio edit")}|${ci("single edit")}|${ci("live")}|${ci("mono")}|" +
                    // The simulator's `[\w' ]*\bversion`: the word boundary means the `[\w' ]*` part is
                    // empty (preceded by whitespace) or ends with a quote or a space.
                    "(?:[A-Za-z0-9_' ]*[' ])?${ci("version")})$",
            )
        val FEATURING = Regex("$S*\\((?:${ci("feat")}\\.|${ci("ft")}\\.)$S*([^)]*)\\)")
        val FROM_PARENTHESIZED = Regex("$S*\\(${ci("from")} ([^)]*)\\)")
        val FROM_SUFFIX = Regex("$S+-$S+${ci("from")}$S+\"?([^\"]+)\"?$")
        val VARIANT =
            Regex(
                "$S*\\(([^)]*(?:${ci("piano")}|${ci("acoustic")}|${ci("cover")}|${ci("strange game")})[^)]*)\\)",
            )
        val ARTIST_SEPARATOR = Regex("$S*,$S*")
        val FOLDED_ACCENTS = '\u0300'..'\u036F'

        // Variation selectors and tags: dropped without touching the letter before them.
        val IGNORED = listOf(0xFE00..0xFE0F, 0xE0000..0xE007F, 0xE0100..0xE01EF)

        // Latin Extended-B ends before U+0250.
        const val LATIN_END = 0x0250
        val LATIN_ADDITIONAL = 0x1E00..0x1EFF
        val GREEK = 0x0370..0x03FF
        val GREEK_EXTENDED = 0x1F00..0x1FFF

        // Musical symbols; the emoji, pictograph and other symbol blocks.
        val SYMBOL_BLOCKS = listOf(0x1D000..0x1D24F, 0x1F000..0x1FAFF)

        const val SUPPLEMENTARY_BASE = 0x10000
        const val HIGH_SURROGATE_BASE = 0xD800
        const val LOW_SURROGATE_BASE = 0xDC00
        const val SURROGATE_BITS = 10
        val MARKS =
            setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

        // An ASCII letter matches either case, as in JavaScript's `/i` without `u`: `ci("mono")` is
        // `[mM][oO][nN][oO]`. Other characters stand for themselves.
        fun ci(word: String): String {
            require(word == word.lowercase()) { "ci takes a lowercase pattern, was $word" }
            return word.map { c -> if (c in 'a'..'z') "[$c${c.uppercaseChar()}]" else c.toString() }.joinToString("")
        }
    }
}
