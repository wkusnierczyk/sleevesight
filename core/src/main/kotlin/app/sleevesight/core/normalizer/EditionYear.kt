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
            // The name less the part is built only for the part that gives a year, so a run of
            // editions without one costs a pass over the name, not one per part (#1147).
            found =
                yearIn(partWords(RANGE.replace(part, " $RANGE_WORD ")), qualified = true)
                    ?.takeIf { through }
                    ?.let { (year, remaster) ->
                        YearTag(year, remaster, (name.substring(0, rest) + name.substring(end)).trim())
                    }
            end = rest
            opener = Edition.openerOfLast(name, 0, end)
        }
        return found ?: if (through) suffix(name.substring(0, end), name.substring(end)) else null
    }

    // The year tag at the end of [head], without brackets: the longest run of at most [MAX_SUFFIX]
    // words of [SUFFIX_WORDS] and years at its end that gives one, with a word before it; the
    // editions after it ([tail]) kept. Only the last [MAX_SUFFIX] + 1 words are read, found from the
    // end, and the year ranges marked in one pass over the name, so the work grows with its length
    // only (#1147).
    private fun suffix(
        head: String,
        tail: String,
    ): YearTag? {
        val (starts, words) = lastWords(head)
        // Word 0 is the one before the tag's words, or the name's first word: a tag starts after it.
        val tagged =
            (1 until words.size)
                .filter { from -> words.drop(from).all { it in SUFFIX_WORDS || Edition.YEAR.matches(it) } }
                .firstNotNullOfOrNull { from -> yearIn(words.drop(from), qualified = false)?.let { from to it } }
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

    // The starts of [head]'s last [MAX_SUFFIX] + 1 words, first to last, and the words, lowercase:
    // found from the end, by index. A word in a year range ("1987 - 1988") is no edition's year: it
    // stands as [RANGE_WORD], the ranges marked in one pass over [head].
    private fun lastWords(head: String): Pair<List<Int>, List<String>> {
        val starts = ArrayDeque<Int>()
        val ends = ArrayDeque<Int>()
        var at = head.length
        while (starts.size <= MAX_SUFFIX) {
            while (at > 0 && head[at - 1].isWhitespace()) at--
            if (at == 0) break
            ends.addFirst(at)
            while (at > 0 && !head[at - 1].isWhitespace()) at--
            starts.addFirst(at)
        }
        val ranged = BooleanArray(starts.size)
        for (range in RANGE.findAll(head)) {
            starts.forEachIndexed { k, start -> if (start in range.range) ranged[k] = true }
        }
        val words = starts.indices.map { k -> if (ranged[k]) RANGE_WORD else head.substring(starts[k], ends[k]) }
        return starts to words.map(String::lowercase)
    }

    // The year and whether it is a remaster's, of a year next to a remaster ("2024 remaster",
    // "remastered 2011", "2011 digital remaster"), or next to an "edition" ("2004 edition", "deluxe
    // edition 2011"), in [words]; null when none. With [qualified], also a year before an edition one
    // or two of [QUALIFIERS] name ("2011 anniversary edition", "2011 super deluxe edition"): in a
    // bracketed part only, since without brackets the year may be the name's own ("The 1975 Deluxe
    // Edition").
    private fun yearIn(
        words: List<String>,
        qualified: Boolean,
    ): Pair<String, Boolean>? =
        words.indices.firstNotNullOfOrNull { i ->
            val next = words.getOrNull(i + 1).let { if (it == "digital") words.getOrNull(i + 2) else it }
            val before = words.getOrNull(i - 1)
            when {
                !Edition.YEAR.matches(words[i]) -> null
                next in REMASTERS || before in REMASTERS -> words[i] to true
                next == "edition" || before == "edition" -> words[i] to false
                qualified && qualifiedEdition(words, i) -> words[i] to false
                else -> null
            }
        }

    // Whether [words] after word [i] are one or two of [QUALIFIERS], then "edition".
    private fun qualifiedEdition(
        words: List<String>,
        i: Int,
    ): Boolean =
        (1..MAX_QUALIFIERS).any { n ->
            words.getOrNull(i + n + 1) == "edition" && (1..n).all { words.getOrNull(i + it) in QUALIFIERS }
        }

    /**
     * The most words an unbracketed year tag has: "2011 Remastered Version", "Super Deluxe Edition
     * 2011".
     */
    private const val MAX_SUFFIX = 4

    /** The most [QUALIFIERS] between a year and its "edition": "2011 Super Deluxe Edition". */
    private const val MAX_QUALIFIERS = 2

    /** The words that may name an edition between its year and "edition", or before "edition" and its year. */
    private val QUALIFIERS =
        setOf("anniversary", "deluxe", "expanded", "special", "legacy", "super", "limited", "collectors")

    /**
     * A range of years, its endpoints no edition's year (#1116): "1987–1988", "1987 - 1988",
     * "2011-12", with a hyphen, an en dash or an em dash, and any spaces, no-break ones too
     * (`\p{Zs}`: ICU's `\s` takes them and the JVM's does not, so the two read alike). ICU-safe:
     * braces only as quantifiers and in `\p{Zs}`.
     */
    private val RANGE = Regex("(1[89]|20)[0-9]{2}[\\s\\p{Zs}]*[-\u2013\u2014][\\s\\p{Zs}]*[0-9]{2,4}")

    /** What a year range stands as among a name's words: no year, and no edition's word. */
    private const val RANGE_WORD = "range"

    /** The words that make a year next to them a remaster's. */
    private val REMASTERS = setOf("remaster", "remastered")

    /** The words besides years an unbracketed year tag is made of. */
    private val SUFFIX_WORDS = REMASTERS + QUALIFIERS + setOf("edition", "digital", "version")
}
