package app.sleevesight.core.normalizer

/**
 * A year a release's name gives its edition (#1116): [year] next to a remaster ([remaster]) or
 * before an "edition", and the name less that part ([name]). For the album line, which then shows
 * no year of its own, and *Dates*, which says a remaster MusicBrainz dates earlier, or has no
 * release of, as such.
 */
data class YearTag(
    val year: String,
    val remaster: Boolean,
    val name: String,
)

/** The year a release's name gives its edition ([YearTag], #1116), as players and stores write it. */
object EditionYear {
    /**
     * The year [release]'s name gives its edition, in a closing bracketed part among the editions
     * [Edition.stripEditions] takes ("Material Girl (2024 Remaster)", "Abbey Road (Remastered 2019)
     * [Deluxe]", "X (2011 Digital Remaster)", "Rumours (2004 Edition)"), or at the end without
     * brackets, a dash before it or not ("Abbey Road - 2019 Remaster", "Sundown Remastered 2011");
     * its [YearTag.name] is the rest, other editions kept ("Abbey Road [Deluxe]"). The year next to
     * the word, a remaster's on either side. Null when the name gives none, when the part also names
     * another recording ("(Live 2011 Remaster)", [Edition.isEdition]), or when nothing would be left
     * ("1999 Remastered"). A year that is the name's own may read as one ("Paris 1919 Remastered"):
     * the callers take it only when it is not before the release's own.
     */
    fun of(release: String): YearTag? {
        val name = release.trim()
        var end = name.length
        var opener = Edition.openerOfLast(name, 0, end)
        var found: YearTag? = null
        var through = true
        while (opener != null && found == null && through) {
            var rest = opener
            while (rest > 0 && name[rest - 1].isWhitespace()) rest--
            val part = name.substring(opener, end)
            through = rest > 0 && Edition.isEdition(part)
            val left = (name.substring(0, rest) + name.substring(end)).trim()
            found =
                yearIn(partWords(part))
                    ?.takeIf { through }
                    ?.let { (year, remaster) -> YearTag(year, remaster, left) }
            end = rest
            opener = Edition.openerOfLast(name, 0, end)
        }
        return found ?: if (through) suffix(name.substring(0, end), name.substring(end)) else null
    }

    // The year tag at the end of [head], without brackets: the longest run of at most [MAX_SUFFIX]
    // words of [SUFFIX_WORDS] and years at its end that gives one, with a word before it; the
    // editions after it ([tail]) kept.
    private fun suffix(
        head: String,
        tail: String,
    ): YearTag? {
        val starts = head.indices.filter { i -> !head[i].isWhitespace() && (i == 0 || head[i - 1].isWhitespace()) }
        val words = starts.map { start -> head.substring(start).takeWhile { !it.isWhitespace() }.lowercase() }
        val tagged =
            (maxOf(1, words.size - MAX_SUFFIX) until words.size)
                .filter { from -> words.drop(from).all { it in SUFFIX_WORDS || Edition.YEAR.matches(it) } }
                .firstNotNullOfOrNull { from -> yearIn(words.drop(from))?.let { from to it } }
        return tagged?.let { (from, tag) ->
            val left =
                head
                    .substring(0, starts[from])
                    .trimEnd()
                    .let { if (it.lastOrNull() in Edition.DASHES) it.dropLast(1) else it }
                    .trimEnd()
            YearTag(tag.first, tag.second, (left + tail).trim()).takeIf { left.isNotEmpty() }
        }
    }

    // The year and whether it is a remaster's, of a year next to a remaster ("2024 remaster",
    // "remastered 2011", "2011 digital remaster") or before an "edition", in [words]; null when none.
    private fun yearIn(words: List<String>): Pair<String, Boolean>? =
        words.indices.firstNotNullOfOrNull { i ->
            val next = words.getOrNull(i + 1).let { if (it == "digital") words.getOrNull(i + 2) else it }
            when {
                !Edition.YEAR.matches(words[i]) -> null
                next in REMASTERS -> words[i] to true
                next == "edition" -> words[i] to false
                words.getOrNull(i - 1) in REMASTERS -> words[i] to true
                else -> null
            }
        }

    /** The most words an unbracketed year tag has: "2011 Remastered Version", "2011 Digital Remaster". */
    private const val MAX_SUFFIX = 3

    /** The words that make a year next to them a remaster's. */
    private val REMASTERS = setOf("remaster", "remastered")

    /** The words besides years an unbracketed year tag is made of. */
    private val SUFFIX_WORDS = REMASTERS + setOf("edition", "digital", "version")
}
