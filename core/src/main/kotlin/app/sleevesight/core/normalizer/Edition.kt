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
    fun strip(release: String): String {
        var name = release.trim()
        var rest = less(name)
        while (rest != null) {
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
}
