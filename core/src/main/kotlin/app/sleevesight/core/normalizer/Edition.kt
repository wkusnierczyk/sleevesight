package app.sleevesight.core.normalizer

/**
 * A release name without the edition a player or a store adds to it in closing brackets
 * ("Rubber Soul (Remastered)" is "Rubber Soul"): for comparing the player's album with
 * MusicBrainz's release names, in the prompt's evidence (`PromptEvidence.restatesRelease`) and for
 * the header's year (#556).
 */
object Edition {
    /**
     * [release] less its closing bracketed parts, `(…)` or `[…]`, each balanced (nested ones
     * included) and with any white space before it (no-break spaces too), one after another, as long
     * as something is left: "Sundown (Remastered (2024)) [Deluxe]" is "Sundown", "(Untitled)
     * (Deluxe)" is "(Untitled)". An unbalanced closing bracket ends the stripping.
     */
    fun strip(release: String): String = strip(release) { true }

    /**
     * [release] less its closing bracketed parts as [strip] takes them, but only while each reads as
     * an edition of the same recordings ([isEdition]): "Abbey Road (Remastered 2019) [Super Deluxe]"
     * is "Abbey Road", but "Sundown (Live)", "Interstellar (Original Motion Picture Soundtrack)" and
     * "Sundown (Live) [Remastered]" keep their names, since a live album or a soundtrack is another
     * release than the studio one of that name. For the header's year (#556).
     */
    fun stripEditions(release: String): String = strip(release, ::isEdition)

    /**
     * Whether a bracketed [part] ("(Remastered 2019)") names an edition: a word of [EDITION_WORDS],
     * or a year alone ("(2011)"), and no word of [OTHER_RECORDINGS] ("(Live 2011 Remaster)" is not).
     */
    fun isEdition(part: String): Boolean {
        val words =
            part
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.any { word -> OTHER_RECORDINGS.any { word.startsWith(it) } }) return false
        return words.any { word -> EDITION_WORDS.any { word.startsWith(it) } } ||
            (words.size == 1 && YEAR.matches(words.single()))
    }

    // [release] less its closing bracketed parts while [take] accepts each.
    private fun strip(
        release: String,
        take: (String) -> Boolean,
    ): String {
        var name = release.trim()
        var rest = less(name)
        while (rest != null && take(name.substring(rest.length))) {
            name = rest
            rest = less(name)
        }
        return name
    }

    // [name] less the bracketed part it ends with and the white space before it; null when it ends
    // with none, or nothing would be left.
    private fun less(name: String): String? =
        openerOfLast(name)?.let { name.substring(0, it).trim() }?.takeIf { it.isNotEmpty() }

    // The index of the bracket that opens the one [name] ends with, or null when it ends with none or
    // it is not balanced.
    private fun openerOfLast(name: String): Int? {
        if (name.isEmpty() || name.last() !in CLOSERS) return null
        var depth = 0
        return name.indices.reversed().firstOrNull { i ->
            when (name[i]) {
                in CLOSERS -> depth++
                in OPENERS -> depth--
            }
            depth == 0
        }
    }

    private val OPENERS = setOf('(', '[')
    private val CLOSERS = setOf(')', ']')

    /** The beginnings of the words that name an edition: remaster(ed), deluxe, expanded, … */
    private val EDITION_WORDS =
        listOf("remaster", "deluxe", "expanded", "anniversary", "edition", "bonus", "special", "reissue")

    /** The beginnings of the words that name another recording or another kind of release. */
    private val OTHER_RECORDINGS =
        listOf("live", "demo", "soundtrack", "acoustic", "instrumental", "mix", "remix", "session", "unplugged")

    private val YEAR = Regex("(1[89]|20)[0-9]{2}")
}
