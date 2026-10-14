package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.normalizer.AccentFolder

/**
 * Compares names and titles as sources write them differently: case, accents, punctuation,
 * typographic apostrophes, `&` for `and`, a leading `The`. Deterministic, and the same on every
 * platform: the one platform part is [AccentFolder].
 */
class NameMatcher(
    private val accentFolder: AccentFolder,
) {
    /**
     * [text] lowercased and accent-folded, apostrophes dropped (`Don't` and `Don’t` are `dont`),
     * `&` read as `and`, every other run of characters that are not letters or digits one space.
     */
    fun key(text: String): String {
        val folded = accentFolder.decompose(text.lowercase()).replace(COMBINING_MARKS, "")
        val words = StringBuilder(folded.length)
        for (c in folded) {
            when {
                c in APOSTROPHES -> Unit
                c == '&' -> words.append(" and ")
                c.isLetterOrDigit() -> words.append(c)
                else -> words.append(' ')
            }
        }
        return words
            .toString()
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /** Whether [a] and [b] name the same artist: equal keys, a leading `the` aside. */
    fun sameName(
        a: String,
        b: String,
    ): Boolean {
        val ka = key(a).removePrefix(THE)
        val kb = key(b).removePrefix(THE)
        return ka.isNotEmpty() && ka == kb
    }

    /**
     * How alike two titles are, from 0 to 1: one minus the edit distance of their keys over the
     * longer key's length.
     */
    fun titleSimilarity(
        a: String,
        b: String,
    ): Double {
        val ka = key(a)
        val kb = key(b)
        val longer = maxOf(ka.length, kb.length)
        if (longer == 0) return 0.0
        return 1.0 - editDistance(ka, kb).toDouble() / longer
    }

    private companion object {
        val COMBINING_MARKS = Regex("[\\u0300-\\u036F]")
        val APOSTROPHES = setOf('\'', '’', '‘', 'ʼ')
        const val THE = "the "

        fun editDistance(
            a: String,
            b: String,
        ): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}
