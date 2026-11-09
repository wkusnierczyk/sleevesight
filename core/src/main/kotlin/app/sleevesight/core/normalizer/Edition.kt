package app.sleevesight.core.normalizer

/**
 * A release name without the edition a player or a store adds to it in closing brackets
 * ("Rubber Soul (Remastered)" is "Rubber Soul"): for comparing the player's album with
 * MusicBrainz's release names, in the prompt's evidence (`PromptEvidence.restatesRelease`) and for
 * the header's year (#556); and, with an edition the name carries without brackets too ("… 20th
 * anniversary", [strippedNames]), for the album's lookups by name (#1070): Wikipedia's article
 * (`AlbumSource.titlesOf`) and Last.fm's `album.getInfo`.
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
     * The names [release] may have without its editions, least stripped first, each other than
     * [release] and never empty; at most two. Bracketed editions go as [stripEditions] takes them and a
     * trailing unbracketed one as [stripEditionSuffix] does, in turn while either takes something:
     * first with every year and ordinal kept as the name's own, then with those an edition takes too
     * ("2011 Remaster", "20th anniversary"). So "Paris 1919 Remastered" is "Paris 1919", then
     * "Paris"; "Abbey Road - 2019 Remaster (Deluxe)" is "Abbey Road - 2019", then "Abbey Road";
     * "Decksandrumsandrockandroll 20th anniversary" is "Decksandrumsandrockandroll" only. For the
     * lookups by name (#1070), which try the name as given first, then these in order, until one is
     * the album: Wikipedia's article and Last.fm's `album.getInfo`, where a name kept with its edition
     * finds nothing. Not for the header's year, which compares the name with MusicBrainz's releases,
     * whose titles keep such words. Each turn takes every bracketed edition at the end and the whole
     * run of unbracketed edition words before them, so a name needs a turn or two; the turns stop at
     * [MAX_PASSES], so a name of any length costs a few linear passes (#1119).
     */
    fun strippedNames(release: String): List<String> = strippedNames(release) {}

    // [strippedNames], calling [onPass] at each turn of either kind, for the tests to count them.
    internal fun strippedNames(
        release: String,
        onPass: () -> Unit,
    ): List<String> {
        val given = release.trim()
        return listOf(false, true)
            .map { numbers ->
                var name = given
                var changed = true
                var passes = 0
                while (changed && passes < MAX_PASSES) {
                    onPass()
                    passes++
                    val less = stripEditionSuffix(stripEditions(name), numbers)
                    changed = less != name
                    name = less
                }
                name
            }.distinct()
            .filter { it != given && it.isNotEmpty() }
    }

    /**
     * [release] less a trailing edition its title carries without brackets (#1070), and any dash
     * before it ("-", "–", "—"), as long as a name is left: "Decksandrumsandrockandroll 20th
     * anniversary", "Abbey Road – 2019 Remaster", "Rubber Soul Deluxe Edition" and "Sundown
     * Remastered" are the names before. Narrowly, whole words only: the suffix is words of
     * [SUFFIX_WORDS], ordinals before "anniversary" ("20th anniversary") and years next to a remaster
     * or a reissue ("2011 Remaster"), at least one of them a remaster, a reissue or "deluxe", or an
     * "anniversary" after an ordinal or with "edition", or an "edition" with a word before it. So
     * "Happy Anniversary", "Abbey Road Edition" and "Sundown Live" are kept, a year or an ordinal
     * elsewhere is the name's own ("The 1975 Deluxe Edition" is "The 1975"), and so is a
     * title that is only such words ("Deluxe", "Super Deluxe Edition"); "1999 Remastered" is "1999".
     * The longest suffix goes that leaves a word before it other than [SUFFIX_WORDS] and dashes,
     * however many words it has ("Abbey Road" and a run of "Deluxe" is "Abbey Road"), found in one
     * pass from the end of the name, so the work grows only with the name's length (#1119).
     * Without [numbers], every year and ordinal is the name's own: "Paris 1919 Remastered" is "Paris
     * 1919", where with them it is "Paris".
     */
    fun stripEditionSuffix(
        release: String,
        numbers: Boolean = true,
    ): String {
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
        val first = SuffixWords(words).start(numbers) ?: return name
        return name
            .substring(0, starts[first])
            .trimEnd()
            .let { if (it.last() in DASHES) it.dropLast(1) else it }
            .trimEnd()
    }

    // A name's [words] (lowercase), with where the words that make a suffix an edition stand last,
    // so whether the words from any one to the end make one is a fixed number of checks; [onCheck]
    // is called at each check of one word, for the tests to count them.
    internal class SuffixWords(
        private val words: List<String>,
        private val onCheck: () -> Unit = {},
    ) {
        private val strong = words.indexOfLast { it in STRONG_SUFFIX_WORDS }
        private val anniversary = words.lastIndexOf("anniversary")
        private val edition = words.lastIndexOf("edition")

        // The last "anniversary" after an ordinal ("20th anniversary").
        private val dated =
            words.indices.lastOrNull { i -> i > 0 && words[i] == "anniversary" && ORDINAL.matches(words[i - 1]) } ?: -1

        // The index of the first word of the longest edition suffix, as [stripEditionSuffix] says,
        // with or without [numbers], that leaves a word of the name's own before it; null when none
        // does. Each word is looked at a fixed number of times, however long the suffix is.
        fun start(numbers: Boolean): Int? {
            // The name's first word of its own: a suffix starts after it.
            val own = words.indexOfFirst { word -> word !in SUFFIX_WORDS && word.any { it !in DASHES } }
            if (own < 0) return null
            // The first word a suffix may start at: every word after it may stand in one, with the
            // words before it in the suffix too.
            var low = words.size - 1
            while (low - 1 > own && isSuffixWord(low, numbers, first = false)) low--
            return (maxOf(low, own + 1) until words.size).firstOrNull { at ->
                isEdition(at) && isSuffixWord(at, numbers, first = true)
            }
        }

        // [Edition.isSuffixWord] for word [i], counted.
        private fun isSuffixWord(
            i: Int,
            numbers: Boolean,
            first: Boolean,
        ): Boolean {
            onCheck()
            return isSuffixWord(words, i, numbers, first)
        }

        // Whether the words from [at] to the end, if each may stand in a suffix, make an edition: one
        // of them a remaster, a reissue or "deluxe", or an "anniversary" after an ordinal or with
        // "edition", or an "edition" with a word before it.
        private fun isEdition(at: Int): Boolean {
            onCheck()
            return strong >= at ||
                (anniversary >= at && (dated > at || edition >= at)) ||
                (edition >= at && words[at] != "edition")
        }
    }

    // Whether [words]' word [i] (lowercase) may stand in an edition suffix that runs to the end of
    // [words], with or without [numbers]: one of [SUFFIX_WORDS], or a number an edition takes. [first]
    // when the suffix starts at it, so the word before it is not the suffix's.
    private fun isSuffixWord(
        words: List<String>,
        i: Int,
        numbers: Boolean,
        first: Boolean,
    ): Boolean = words[i] in SUFFIX_WORDS || (numbers && isEditionNumber(words, i, first))

    // Whether [words]' word [i] is a number an edition suffix may take: a year next to a remaster or a
    // reissue ("2011 Remaster", "Remastered 2009", "2011 Digital Remaster"), an ordinal before
    // "anniversary"; the word before it only when it is not the suffix's [first]. A year or an ordinal
    // elsewhere is the name's own ("The 1975 Deluxe Edition" is "The 1975", "The 1975 10th Anniversary
    // Edition" is "The 1975" too).
    private fun isEditionNumber(
        words: List<String>,
        i: Int,
        first: Boolean,
    ): Boolean {
        val word = words[i]
        val next = words.getOrNull(i + 1)
        return when {
            YEAR.matches(word) ->
                next in REMASTERS ||
                    (next == "digital" && words.getOrNull(i + 2) in REMASTERS) ||
                    (!first && words.getOrNull(i - 1) in REMASTERS)
            ORDINAL.matches(word) -> next == "anniversary"
            else -> false
        }
    }

    /**
     * Whether a bracketed [part] ("(Remastered 2019)") names an edition: one of [EDITION_WORDS] as a
     * whole word, or a year alone ("(2011)"), and none of [OTHER_RECORDINGS] ("(Live 2011 Remaster)"
     * is not). Whole words, case and accents aside only as far as lowercasing goes: "(Specialist)" is
     * not "special", "(Livestock Deluxe)" is a deluxe edition.
     */
    fun isEdition(part: String): Boolean {
        val words = partWords(part)
        if (words.any { it in OTHER_RECORDINGS }) return false
        return words.any { it in EDITION_WORDS } ||
            (words.size == 1 && YEAR.matches(words.single()))
    }

    // [release] less its closing bracketed parts while [take] accepts each (with the white space
    // before it), and as long as something is left. By the bounds of what is left rather than copies
    // of it, so each character is looked at a fixed number of times however many parts go (#1119).
    internal fun strip(
        release: CharSequence,
        take: (String) -> Boolean,
    ): String {
        var start = 0
        var end = release.length
        while (start < end && release[start].isWhitespace()) start++
        while (end > start && release[end - 1].isWhitespace()) end--
        var opener = openerOfLast(release, start, end)
        while (opener != null) {
            var rest = opener
            while (rest > start && release[rest - 1].isWhitespace()) rest--
            opener =
                if (rest > start && take(release.subSequence(rest, end).toString())) {
                    end = rest
                    openerOfLast(release, start, end)
                } else {
                    null
                }
        }
        return release.subSequence(start, end).toString()
    }

    // The index of the bracket that opens the one [name]'s characters from [start] to [end] end with,
    // or null when they end with none or it is not balanced.
    internal fun openerOfLast(
        name: CharSequence,
        start: Int,
        end: Int,
    ): Int? {
        if (end <= start || name[end - 1] !in CLOSERS) return null
        var depth = 0
        var i = end
        do {
            i--
            when (name[i]) {
                in CLOSERS -> depth++
                in OPENERS -> depth--
            }
        } while (depth > 0 && i > start)
        return i.takeIf { depth == 0 }
    }

    /**
     * The most turns [strippedNames] takes for each of its names. A turn takes the bracketed editions
     * at the end and then an unbracketed one before them ("Abbey Road - 2019 Remaster (Deluxe)" in
     * one), so a real name changes in a turn or two, and one more finds nothing left; the rest is
     * room, while a name made of editions in turn, bracketed and not, a thousand times over, costs no
     * more than a few passes over it.
     */
    internal const val MAX_PASSES = 4

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

    internal val YEAR = Regex("(1[89]|20)[0-9]{2}")

    private val ORDINAL = Regex("[0-9]{1,3}(st|nd|rd|th)")

    /** The dashes that may stand before an unbracketed edition: hyphen-minus, en dash, em dash. */
    internal val DASHES = setOf('-', '\u2013', '\u2014')

    /**
     * The words an unbracketed edition suffix is made of, besides ordinals and years. Not "remasters":
     * as a title's last word it names a compilation ("Led Zeppelin Remasters", "The Remasters").
     */
    private val SUFFIX_WORDS =
        setOf(
            "remaster",
            "remastered",
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
    private val STRONG_SUFFIX_WORDS = setOf("remaster", "remastered", "deluxe", "reissue", "reissued")

    /** The words a year in an edition suffix stands next to. */
    private val REMASTERS = setOf("remaster", "remastered", "reissue", "reissued")
}

// A bracketed part's words, lowercase, split at anything but letters and digits: for
// [Edition.isEdition] and [EditionYear.of]. In one pass with no copy per character, since a name
// may have thousands of parts (#1147).
internal fun partWords(part: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (c in part.lowercase()) {
        if (c.isLetterOrDigit()) {
            word.append(c)
        } else if (word.isNotEmpty()) {
            words += word.toString()
            word.clear()
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}
