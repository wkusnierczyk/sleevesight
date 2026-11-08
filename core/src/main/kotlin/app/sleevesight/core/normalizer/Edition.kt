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
     * [release] less its editions, bracketed ones as [stripEditions] takes them and a trailing
     * unbracketed one as [stripEditionSuffix] does, in turn while either takes something: "Abbey Road
     * - 2019 Remaster (Deluxe)" is "Abbey Road". For the lookups by name (#1070), which try the name
     * as given first: Wikipedia's article and Last.fm's `album.getInfo`, where a name kept with its
     * edition finds nothing. Not for the header's year, which compares the name with MusicBrainz's
     * releases, whose titles keep such words.
     */
    fun stripAllEditions(release: String): String {
        var name = release.trim()
        while (true) {
            val less = stripEditionSuffix(stripEditions(name))
            if (less == name) return name
            name = less
        }
    }

    /**
     * [release] less a trailing edition its title carries without brackets (#1070), and any dash
     * before it ("-", "–", "—"), as long as a name is left: "Decksandrumsandrockandroll 20th
     * anniversary", "Abbey Road – 2019 Remaster", "Rubber Soul Deluxe Edition" and "Sundown
     * Remastered" are the names before. Narrowly, whole words only: the suffix is words of
     * [SUFFIX_WORDS], ordinals ("20th") and years, at least one of them a remaster, a reissue or
     * "deluxe", or an "anniversary" with an ordinal, a year or "edition", or an "edition" with a word
     * before it. So "Happy Anniversary", "Abbey Road Edition" and "Sundown Live" are kept, and so is a
     * title that is only such words ("Deluxe", "Super Deluxe Edition"); "1999 Remastered" is "1999".
     * The longest suffix goes that leaves a word before it other than [SUFFIX_WORDS] and dashes.
     */
    fun stripEditionSuffix(release: String): String {
        val name = release.trim()
        val starts = name.indices.filter { i -> !name[i].isWhitespace() && (i == 0 || name[i - 1].isWhitespace()) }
        val words =
            starts.mapIndexed { n, start ->
                val end = starts.getOrNull(n + 1) ?: name.length
                name
                    .substring(start, end)
                    .trim()
                    .lowercase()
                    .replace('\u2019', '\'')
            }
        // The first word of the longest suffix that leaves a word of the name's own before it.
        val first =
            (1 until words.size).firstOrNull { at ->
                isSuffix(words.subList(at, words.size)) &&
                    words.subList(0, at).any { word -> word !in SUFFIX_WORDS && word.any { it !in DASHES } }
            }
        if (first == null) return name
        return name
            .substring(0, starts[first])
            .trimEnd()
            .dropLastIf { it in DASHES }
            .trimEnd()
    }

    // Whether [words] (lowercase) are an edition suffix, as [stripEditionSuffix] says.
    private fun isSuffix(words: List<String>): Boolean {
        val dated = words.any { ORDINAL.matches(it) || YEAR.matches(it) }
        return words.all { it in SUFFIX_WORDS || ORDINAL.matches(it) || YEAR.matches(it) } &&
            (
                words.any { it in STRONG_SUFFIX_WORDS } ||
                    ("anniversary" in words && (dated || "edition" in words)) ||
                    words.indexOf("edition").let { it > 0 }
            )
    }

    // [this] less its last character when [drop] accepts it.
    private inline fun String.dropLastIf(drop: (Char) -> Boolean): String =
        if (isNotEmpty() && drop(last())) dropLast(1) else this

    /**
     * Whether a bracketed [part] ("(Remastered 2019)") names an edition: one of [EDITION_WORDS] as a
     * whole word, or a year alone ("(2011)"), and none of [OTHER_RECORDINGS] ("(Live 2011 Remaster)"
     * is not). Whole words, case and accents aside only as far as lowercasing goes: "(Specialist)" is
     * not "special", "(Livestock Deluxe)" is a deluxe edition.
     */
    fun isEdition(part: String): Boolean {
        val words =
            part
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
        if (words.any { it in OTHER_RECORDINGS }) return false
        return words.any { it in EDITION_WORDS } ||
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

    /** The words that name an edition, with their inflections. */
    private val EDITION_WORDS =
        setOf(
            "remaster",
            "remastered",
            "remasters",
            "deluxe",
            "expanded",
            "anniversary",
            "edition",
            "editions",
            "bonus",
            "special",
            "reissue",
            "reissued",
        )

    /** The words that name another recording or another kind of release, with their inflections. */
    private val OTHER_RECORDINGS =
        setOf(
            "live",
            "demo",
            "demos",
            "soundtrack",
            "acoustic",
            "instrumental",
            "instrumentals",
            "mix",
            "mixes",
            "mixed",
            "remix",
            "remixes",
            "remixed",
            "session",
            "sessions",
            "unplugged",
            "concert",
            "karaoke",
            "orchestral",
            "orchestra",
            "symphonic",
            "symphony",
            "cover",
            "covers",
            "rehearsal",
            "bootleg",
        )

    private val YEAR = Regex("(1[89]|20)[0-9]{2}")

    private val ORDINAL = Regex("[0-9]{1,3}(st|nd|rd|th)")

    /** The dashes that may stand before an unbracketed edition: hyphen-minus, en dash, em dash. */
    private val DASHES = setOf('-', '\u2013', '\u2014')

    /** The words an unbracketed edition suffix is made of, besides ordinals and years. */
    private val SUFFIX_WORDS =
        setOf(
            "remaster",
            "remastered",
            "remasters",
            "digital",
            "deluxe",
            "super",
            "expanded",
            "special",
            "limited",
            "collector's",
            "collectors",
            "bonus",
            "anniversary",
            "edition",
            "version",
            "reissue",
            "reissued",
        )

    /** The words that alone make [SUFFIX_WORDS] an edition suffix. */
    private val STRONG_SUFFIX_WORDS = setOf("remaster", "remastered", "remasters", "deluxe", "reissue", "reissued")
}
