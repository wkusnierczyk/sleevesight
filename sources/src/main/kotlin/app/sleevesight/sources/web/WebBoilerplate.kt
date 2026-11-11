package app.sleevesight.sources.web

/**
 * Parts of a web line that are only page boilerplate, left out before the line is kept (#905;
 * `design/design.md`, *Web search provider (D11)*, *Page boilerplate*): the text of a page around
 * its facts, which a search engine's highlight often quotes and which, sent as an evidence line,
 * the model wrote about ("an official video is on YouTube", a rights notice called "a useful
 * detail").
 *
 * [clean] never cuts text inside a part (a passage between the separators ` ... ` the highlights
 * are joined with): it only decides whether a whole part goes. A part goes when it is boilerplate
 * through and through: once its site's known page text is set aside, what is left has no digit, no
 * credit (a role's label before a name, in any case: "Music by", "Lyrics:", "Written by", "Album:"; not a
 * streaming page's "song and lyrics by"), and no
 * word beyond the subject's names (the track's title, its work and its artists, or the album and
 * its artist) and a page's words ([PAGE_WORDS]: "official video", "chords", "YouTube", a site's
 * address). Every other part stays as it came, boilerplate and all, but for a streaming page's
 * navigation at its start or its end ([withoutEdges], #955): earlier versions that cut boilerplate
 * out of a part took facts with it ("- Likes: 98K ## Description …", "is Am G D."), and dropping a
 * real fact costs more than keeping a little noise. A line whose parts all go gives no line.
 *
 * The known page text is set aside only on its own kind of site, by the result's address ([Site]),
 * by the kinds of page met on thinly documented tracks (the #767 phone round, 2026-11-04):
 *
 * - on a video site: the footer (YouTube's "About Press Copyright … Test new features"), the
 *   channel's stats as listed fields ("- Channel: … - Views: 12.3M - Likes: 98K"), each value
 *   bounded, and the bits of them a cut leaves (".7K"), a transcript's markers ("[Music]", "♪"), and
 *   "(Full Album)";
 * - on a streaming service: navigation and sign-in text ("Skip to main content", "Sign in to see
 *   lyrics and listen to the full track", "Listen on Audiomack"), and a listing's kind beside its
 *   title ("- Single", "Album •"); "single" and "album" are no page's words elsewhere, since "a
 *   single by" and "the album Sundown" are facts;
 * - on Bandcamp: the rights heading ("### license all rights reserved"), the upcoming shows, and
 *   the supporters' user names: the "supported by" label, and a run of eight or more words without
 *   punctuation, two of them handles (`killer_tofu123`), after the label, or starting the part and
 *   meeting the track list's table or a heading;
 * - on a chord site: the tuning, capo and "Chords used" fields, a practice tip, and runs of chord
 *   symbols (`Dm C6`, `Bbmaj7 G`);
 * - on Letras: its credits page's report links and registry line;
 * - on any page: a lyrics site's fixed sentences, whole ("Compare plain and time-synced lyrics, …",
 *   "Artists like … 10 similar artists"), and a part that is only "All rights reserved".
 */
internal object WebBoilerplate {
    /** The separator between a line's parts: the one highlights are joined with. */
    const val SEPARATOR: String = " ... "

    /**
     * [text] (whitespace collapsed) of the page at [url] without its parts that are only
     * boilerplate, for the [subject] (names, as given), folded by [fold] as the filter folds them;
     * the parts kept as they came; empty when no part is left.
     */
    fun clean(
        text: String,
        url: String,
        subject: List<String>,
        fold: (String) -> String,
    ): String {
        val site = Site.of(url)
        val words = subject.flatMap { fold(it).split(' ') }.filter { it.isNotEmpty() }.toSet()
        val parts = text.split(PART_BREAK).map { it.trim() }
        val kept =
            parts.withIndex().flatMap { (i, part) ->
                // A separator the line starts or ends with stays: it shows the quote goes on.
                if (part.isEmpty()) return@flatMap if (i == 0 || i == parts.lastIndex) listOf(part) else emptyList()
                if (onlyBoilerplate(part, site, words, fold)) emptyList() else listOf(withoutEdges(part, site))
            }
        if (kept.none { it.isNotEmpty() }) return ""
        return kept.joinToString(SEPARATOR).trim()
    }

    /** The kinds of site with page text of their own, by the result's host and the hosts below it. */
    internal enum class Site(
        hosts: String,
    ) {
        VIDEO("youtube.com youtu.be"),
        STREAMING(
            "spotify.com music.apple.com audiomack.com deezer.com tidal.com soundcloud.com jiosaavn.com gaana.com " +
                "music.amazon.com pandora.com",
        ),
        BANDCAMP("bandcamp.com"),
        CHORDS(
            "ultimate-guitar.com chordu.com cifraclub.com cifraclub.com.br e-chords.com chordify.net azchords.com " +
                "guitartabs.cc songsterr.com chords-and-tabs.net",
        ),
        LETRAS("letras.com letras.mus.br"),
        OTHER(""),
        ;

        /** The site's hosts, each with the hosts below it. */
        val hosts: List<String> = hosts.split(' ').filter { it.isNotEmpty() }

        companion object {
            /** The kind of site [url] is on; [OTHER] for any other, or no host. */
            fun of(url: String): Site {
                val host = RecordingFilter.hostOf(url) ?: return OTHER
                return entries.firstOrNull { site -> site.hosts.any { host == it || host.endsWith(".$it") } } ?: OTHER
            }
        }
    }

    /**
     * [part], a part that stays, without its [site]'s interface text at its start and its end
     * ([EDGES]: a streaming page's "Sign up Log in Resize main navigation", and what a cut leaves of
     * it at the start, "in Resize main navigation"), with the dashes, bars and bullets it leaves
     * there; never inside the part, so no fact goes (#955). [part] as it came when nothing is
     * trimmed, or if nothing would be left.
     */
    internal fun withoutEdges(
        part: String,
        site: Site,
    ): String {
        val (start, end) = EDGES[site] ?: return part
        var text = part
        start.find(text)?.let { text = text.substring(it.range.last + 1).trimStart { c -> c in LEFT_OVER } }
        end.find(text)?.let { text = text.substring(0, it.range.first).trimEnd { c -> c in LEFT_OVER } }
        return text.ifEmpty { part }
    }

    // [part] with its site's known page text and any page's fixed sentences set aside, each leaving the separator.
    private fun strip(
        part: String,
        site: Site,
    ): String {
        var text = part
        if (site == Site.VIDEO) for (rule in MARKERS) text = rule.replace(text, " ")
        for (rule in PASSAGES[site].orEmpty() + ANY_PAGE) text = rule.replace(text, SEPARATOR)
        text = text.replace(SPACES, " ")
        if (site == Site.CHORDS) text = INVISIBLE.replace(withoutChords(text), "")
        if (site == Site.BANDCAMP) text = withoutSupporters(text)
        return text
    }

    // A piece of what is left without the spaces, dashes, bars and bullets at its ends.
    private fun tidy(piece: String): String =
        piece
            .replace(SPACES, " ")
            .trim()
            .trimStart { it in LEFT_OVER }
            .trimEnd { it in LEFT_OVER || it == '#' }
            .trim()

    /**
     * [text] without runs of two or more chord symbols, one with more than its letter (not "A E"):
     * a chord sheet's chords. Scanned word by word, so a long highlight cannot exhaust the stack.
     */
    internal fun withoutChords(text: String): String {
        val words = text.split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var end = i
            while (end < words.size && CHORD.matches(words[end])) end++
            val run = words.subList(i, end)
            if (run.size < 2 || run.none { it.length > 1 }) out += words.subList(i, maxOf(i + 1, end))
            i = maxOf(i + 1, end)
        }
        return out.joinToString(" ")
    }

    /**
     * [text] without Bandcamp's supporters: the "supported by" label and the run of user names
     * after it, ending the part or meeting the track list's table (`|`) or a heading (`#`), or a
     * run starting the part (the label cut away) that meets the table or a heading. A run is
     * [MIN_SUPPORTERS] or more words of letters, digits and underscores, two of them handles; a
     * sentence, its run ending in punctuation, never is. Scanned word by word, so a long highlight
     * cannot exhaust the stack.
     */
    internal fun withoutSupporters(text: String): String {
        val words = text.split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val end = supportersEnd(words, i)
            if (end != null) {
                out += SEPARATOR.trim()
                i = end
            } else {
                out += words[i]
                i++
            }
        }
        return out.joinToString(" ")
    }

    // Where the supporters starting at [i] of [words] end (the label with them), or null if none start there.
    private fun supportersEnd(
        words: List<String>,
        i: Int,
    ): Int? {
        val label = i + 1 < words.size && words[i].equals("supported", ignoreCase = true) && words[i + 1] == "by"
        if (!label && i != 0) return null
        val start = if (label) i + 2 else i
        var end = start
        while (end < words.size && NAME.matches(words[end])) end++
        // After the label, a run may end the part; without it, it must meet the table or a heading.
        val meets = end < words.size && (words[end].startsWith('|') || words[end].startsWith('#'))
        val atEdge = meets || (label && end == words.size)
        val names = words.subList(start, end)
        return end.takeIf { atEdge && names.size >= MIN_SUPPORTERS && names.count(::isHandle) >= 2 }
    }

    /**
     * Whether [part] is page boilerplate through and through: with its [site]'s known page text set
     * aside ([strip]), no piece left has a digit, a credit, or a word beyond the [subject]'s and a
     * page's ([saysMore]). On a video site a count or a time stamp's tail alone (".7K") is what a
     * cut left of the stats, not a number of the page's own.
     */
    private fun onlyBoilerplate(
        part: String,
        site: Site,
        subject: Set<String>,
        fold: (String) -> String,
    ): Boolean =
        strip(part, site)
            .split(PART_BREAK)
            .map(::tidy)
            .filter { it.isNotEmpty() }
            .none { piece ->
                when {
                    site == Site.VIDEO && piece.length <= STATS_BIT_LENGTH && STATS_BIT.matches(piece) -> false
                    piece.any { it.isDigit() } -> true
                    CREDIT.containsMatchIn(piece) -> true
                    else -> saysMore(piece, subject, fold)
                }
            }

    /**
     * Whether [part] says more than [subject] (folded words) in a page's words: at least two
     * letters, and a word (a run of letters or digits, folded) that is neither the subject's, a
     * common word ([RecordingFilter.COMMON_WORDS]), a page's ([PAGE_WORDS]), nor a single letter. A
     * site's address is a page's word.
     */
    internal fun saysMore(
        part: String,
        subject: Set<String>,
        fold: (String) -> String,
    ): Boolean {
        if (part.count { it.isLetter() } < 2) return false
        return fold(ADDRESS.replace(part, " "))
            .split(' ')
            .any { word ->
                word.isNotEmpty() &&
                    word !in subject &&
                    word !in RecordingFilter.COMMON_WORDS &&
                    word !in PAGE_WORDS &&
                    !(word.length == 1 && word[0].isLetter())
            }
    }

    // A word of a user name: an underscore in it, or a digit after a letter (`ju5tintime`, `tofu123`),
    // not a number with its unit (`5m`, `16s`), which a listing's duration has.
    private fun isHandle(word: String): Boolean = '_' in word || HANDLE_DIGIT.containsMatchIn(word)

    private val HANDLE_DIGIT = Regex("\\p{L}\\p{N}")

    private val NAME = Regex("[\\p{L}\\p{N}_]+")

    /** The fewest words a run of supporters' names has. */
    private const val MIN_SUPPORTERS = 8

    private const val STATS_BIT_LENGTH = 24

    // A count or a time stamp's tail, alone: what a cut leaves of a video's stats.
    private val STATS_BIT = Regex("\\.?\\d+(?:[.,]\\d+)*[KMB]|\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[-+]\\d{2}:?)?")

    // The separator between parts: three dots with a space or the line's end on each side.
    private val PART_BREAK = Regex("(?<=^|\\s)\\.\\.\\.(?=\\s|$)")

    private val SPACES = Regex(" {2,}")

    // Left at a piece's ends once a passage is taken out: list dashes, table bars, bullets, commas.
    private const val LEFT_OVER = " -–—|•·,;"

    // Zero-width spaces and byte-order marks, which chord sheets put between chords and lyrics.
    private val INVISIBLE = Regex("[\\u200B\\uFEFF]")

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    /**
     * A credit: a role's label and a name after it, in any case and script, which keeps a part even
     * when the name is the subject's ("Music by Gordon Lightfoot", "Lyrics: gordon lightfoot",
     * "Compositor: …", "Album: Sundown").
     */
    private val CREDIT =
        Regex(
            // Not a streaming page's title, "<song> - song and lyrics by <artist>".
            "(?:\\b(?:music|(?<!song and )lyrics|words|written|composed|produced|arranged|mixed|engineered|" +
                "recorded) by|" +
                "\\b(?:music|lyrics|words|writers?|composers?|lyricists?|songwriters?|producers?|arrangers?|" +
                "compositor|letra|writer/composer|album|single|ep)\\s*:|\\b(?:escrita|composta) por:?)\\s*\\p{L}",
            IGNORE_CASE,
        )

    /** A transcript's markers on a video site, set aside leaving a space. */
    private val MARKERS: List<Regex> =
        listOf("\\[(?:Music|Applause|Laughter|Cheering|Instrumental)]", "♪").map { Regex(it, IGNORE_CASE) }

    /** Each kind of site's known page text, set aside leaving the separator. */
    private val PASSAGES: Map<Site, List<Regex>> =
        mapOf(
            Site.VIDEO to
                listOf(
                    "About Press Copyright Contact us Creators Advertise Developers Terms " +
                        "Privacy(?: Policy & Safety)? How YouTube works Test new features" +
                        "(?: NFL Sunday Ticket)?(?: © \\d{4} Google LLC)?",
                    // A video's title words for a whole album: "Sundown (Full Album)".
                    "\\(?Full Album\\)?",
                    // Each stat with its value only: a count, a time, a date, or a channel's name up to the
                    // next field; never past a heading, so a description after them is not counted as one.
                    "(?<=^|\\s)- Channel: [^#]{1,60}?(?=\\s- (?:Length|Duration|Views|Likes|Published)\\b)",
                    "(?<=^|\\s)- (?:Views|Likes|Dislikes|Comments|Subscribers)(?:: [\\d.,]+[KMB]?)?(?=\\s|$)",
                    "(?<=^|\\s)- (?:Length|Duration)(?:: [\\d:]+)?(?=\\s|$)",
                    "(?<=^|\\s)- (?:Published|Uploaded)(?:: [\\d-]+(?:T[\\d:+-]+)?)?(?=\\s|$)",
                    "(?<=^|\\s)-(?=\\s|$)",
                ),
            Site.STREAMING to
                listOf(
                    "Skip to main content",
                    "Premium ?Support ?Download",
                    "Sign ?up ?Log ?in",
                    "Resize main navigation",
                    "Sign in to see lyrics and listen to the full track",
                    "Recommended Based on this song",
                    "Listen on (?:Spotify|Apple Music|Audiomack|Deezer|SoundCloud|Tidal|Amazon Music|Pandora|JioSaavn)",
                    "Listen to \\S.{0,80}? online\\.?",
                    // A listing's kind beside its title: "Sundown - Single", "Single • 1974".
                    "(?<=^|\\s)[-·•] ?(?:Single|EP|Album)(?=\\s|$)",
                    "(?<=^|\\s)(?:Single|EP|Album) ?[·•]",
                ),
            Site.BANDCAMP to
                listOf(
                    "#+ license all rights reserved\\.?(?=\\s#|$)",
                    // The shows list to its sentence's end or the next heading, never a fact after it.
                    "#+ shows\\b[^.#]*\\.?",
                    "supported by(?=\\s#|$)",
                ),
            Site.CHORDS to
                listOf(
                    "Chords used:",
                    "Tuning: ?(?:Standard(?: Tuning)?|Half Step Down|Drop [A-G]|Open [A-G])?(?: ?\\([A-Ga-g#b ]+\\))?",
                    "Capo: ?(?:no capo|\\+?\\d+ ?(?:st|nd|rd|th)? ?(?:fret)?)",
                    // The tip's own sentence, never a fact after it.
                    "your practice should emphasi[sz]e these chord progressions\\b[^.]*\\.?",
                ),
            Site.LETRAS to
                listOf(
                    "Ver créditos completos\\.?",
                    "¿Los datos están equivocados\\? Avísanos\\.?",
                    "ECAD verificado obra #\\d+ em \\S+",
                ),
        ).mapValues { (_, rules) -> rules.map { Regex(it, IGNORE_CASE) } }

    // A streaming page's navigation, each phrase whole words; also in [PASSAGES].
    private const val STREAMING_INTERFACE: String =
        "(?<![\\p{L}\\p{N}])(?:Skip to main content|Premium ?Support ?Download|Sign ?up ?Log ?in|" +
            "Resize main navigation)(?![\\p{L}\\p{N}])"

    // What may stand between two phrases of the interface: spaces, dashes, bars, bullets.
    private const val BETWEEN: String = "[\\s|•·,;–—-]*"

    /**
     * Each kind of site's interface text at a part's start and at its end: one phrase or more, the
     * first at the start may be what a cut left of "Sign up Log in" ("in Resize main navigation",
     * "up Log in Resize …"), only before a whole phrase.
     */
    private val EDGES: Map<Site, Pair<Regex, Regex>> =
        mapOf(
            Site.STREAMING to
                Pair(
                    Regex("^(?:(?:(?:Sign ?)?up ?)?(?:Log ?)?in\\b)?(?:$BETWEEN$STREAMING_INTERFACE)+", IGNORE_CASE),
                    Regex("(?:$BETWEEN$STREAMING_INTERFACE)+$BETWEEN$", IGNORE_CASE),
                ),
        )

    /** A lyrics site's fixed sentences, whole, which no prose has, and a part only of a rights line: on any page. */
    private val ANY_PAGE: List<Regex> =
        listOf(
            "Format: Lyrics \\+ LRC",
            "Plain lyrics Synced LRC",
            "Compare plain and time-synced lyrics, check other recording versions, and download available " +
                "lyric or subtitle formats\\.",
            "(?:\\d+\\. )?Artists like .{1,60}? \\d+ similar artists(?: \\d+\\.)?",
            "^all rights reserved\\.?$",
        ).map { Regex(it, IGNORE_CASE) }

    // A chord symbol, a whole word: its letter, an accidental, a quality, a number, a bass note.
    private val CHORD = Regex("[A-G](?:#|♯|b|♭)?(?:maj|min|m|dim|aug|sus|add)?\\d{0,2}(?:/[A-G](?:#|♯|b|♭)?)?")

    /**
     * A site's address in a part (`Ultimate-Guitar.Com`, `open.spotify.com`), a page's word: one
     * class from the start of a word, so a long one is scanned once.
     */
    private val ADDRESS =
        Regex(
            "(?<![\\p{L}\\p{N}.-])[\\p{L}\\p{N}.-]+\\.(?:com|net|org|fm|io|app|co|uk|br|de|fr|in|me|tv)\\b",
            IGNORE_CASE,
        )

    /**
     * The words of a page's furniture, folded: what a video, a streaming or store listing, a chord
     * or lyrics page puts around a track's name, and the sites' names. A part with only these and
     * the subject's says nothing about it.
     */
    internal val PAGE_WORDS: Set<String> =
        (
            "official video videos audio music lyric lyrics chord chords tab tabs song songs track tracks " +
                "artist artists listen watch online stream streaming download play hd hq mv " +
                "visualizer topic provided full feat ft featuring lrc " +
                "youtube vevo spotify apple itunes deezer tidal amazon soundcloud bandcamp audiomack pandora " +
                "napster shazam genius musixmatch azlyrics letras ultimate com www"
        ).split(' ').toSet()
}
