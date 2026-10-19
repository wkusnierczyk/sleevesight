package app.sleevesight.core.resolution

import app.sleevesight.core.normalizer.AccentFolder

/**
 * Compares names and titles as sources write them differently: case, accents, punctuation,
 * typographic apostrophes, `&` for `and`, a leading `The`; apart, as a weaker variant, a leading
 * honorific. Deterministic, and the same on every platform: the one platform part is [AccentFolder].
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
     * Whether [a] and [b] are one name written with and without a leading honorific (`Mr.`, `Mrs.`,
     * `Ms.`, `Miss`, `Mister`, `Dr.`, `Doctor`, `Sir`, `Dame`) on one of them only: `Mr. A
     * Balladeer` and `a balladeer` (#79). Narrow on purpose, since a variant may be another artist:
     * the rest must be the other name whole, a leading `the` aside as in [sameName], and at least two
     * words as written, split at whitespace only, because an honorific before a single word is
     * usually a stage name of its own (`Dr. John` is not `John`, nor `Mr. Big` `Big`, nor `Mr.
     * T-Bone` `T-Bone`: a hyphenated word is one word). A word with no letters or digits, such as a
     * lone `-`, is not counted. A leading article counts as a word: `A Balladeer` is two, and so is
     * `The Bear`, so `Mr. The Bear` is a variant of `Bear`, as `The Bear` is the same name as `Bear`
     * in [sameName]. Two different honorifics (`Mr. X Y`, `Mrs. X Y`) are two people, but one
     * honorific spelled two ways (`Dr.` and `Doctor`, `Mr.` and `Mister`) on both names, the rest
     * one name, is a variant at any length, since neither drops it: `Dr. Hook & the Medicine Show`
     * and `Doctor Hook & the Medicine Show`, `Dr. John` and `Doctor John`. Not [sameName]: a variant
     * is weaker evidence, which the resolver weighs only when nothing matches by name.
     */
    fun honorificVariant(
        a: String,
        b: String,
    ): Boolean = sameRest(a, b) || sameRest(b, a) || respelled(a, b)

    // Whether [variant] is an honorific, then at least two words whose key is [plain]'s, a leading
    // `the` aside.
    private fun sameRest(
        variant: String,
        plain: String,
    ): Boolean {
        val words = variant.trim().split(WHITESPACE)
        val after = words.drop(1)
        if (key(words.first()) !in HONORIFICS || after.count { key(it).isNotEmpty() } < VARIANT_MIN_WORDS) return false
        val rest = key(after.joinToString(" ")).removePrefix(THE)
        return rest.isNotEmpty() && rest == key(plain).removePrefix(THE)
    }

    // Whether [a] and [b] lead with one honorific spelled two ways (`Dr.`, `Doctor`), then one name,
    // a leading `the` aside.
    private fun respelled(
        a: String,
        b: String,
    ): Boolean {
        val wordsA = a.trim().split(WHITESPACE)
        val wordsB = b.trim().split(WHITESPACE)
        val honorificA = key(wordsA.first())
        val honorificB = key(wordsB.first())
        // Spelled the same, the names are the same or two; spelled two ways, one is in [SPELLINGS].
        if (honorificA == honorificB || spelling(honorificA) != spelling(honorificB)) return false
        val restA = key(wordsA.drop(1).joinToString(" ")).removePrefix(THE)
        return restA.isNotEmpty() && restA == key(wordsB.drop(1).joinToString(" ")).removePrefix(THE)
    }

    /**
     * Whether [credit] is [artist] leading a band, in the usual forms: `X and His …`, `X and Her …`,
     * `X & the …` (or `and the`), `X with …`, with more words after them. [artist]'s name must be
     * whole, a leading `the` aside: `Dick Daley and His Del-Tones` is not led by Dick Dale.
     */
    fun leads(
        credit: String,
        artist: String,
    ): Boolean {
        val ka = key(artist).removePrefix(THE)
        val kc = key(credit).removePrefix(THE)
        if (ka.isEmpty() || !kc.startsWith("$ka ")) return false
        val rest = kc.removePrefix("$ka ")
        return BAND_FORMS.any { rest.startsWith("$it ") }
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
        fun spelling(honorific: String): String = SPELLINGS[honorific] ?: honorific

        val COMBINING_MARKS = Regex("[\\u0300-\\u036F]")
        val APOSTROPHES = setOf('\'', '’', '‘', 'ʼ')
        const val THE = "the "

        val WHITESPACE = Regex("\\s+")

        // As keys: `Mr.` is `mr`.
        val HONORIFICS = setOf("mr", "mrs", "ms", "miss", "mister", "dr", "doctor", "sir", "dame")

        // The honorifics written two ways, each to the one spelling [respelled] compares.
        val SPELLINGS = mapOf("doctor" to "dr", "mister" to "mr")

        // The fewest words a name keeps after an honorific for [honorificVariant] to match.
        const val VARIANT_MIN_WORDS = 2

        // After the leader's name, as keys: `&` is `and`.
        val BAND_FORMS = listOf("and his", "and her", "and the", "with")

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
