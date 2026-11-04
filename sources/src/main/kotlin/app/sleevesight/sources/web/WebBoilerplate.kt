package app.sleevesight.sources.web

/**
 * Page boilerplate taken out of a web line before it is kept (#905; `design/design.md`, *Web search
 * provider (D11)*, *Page boilerplate*): the text of a page around its facts, which a search
 * engine's highlight often quotes and which, sent as an evidence line, the model wrote about ("an
 * official video is on YouTube", a rights notice called "a useful detail").
 *
 * [clean] works on the line's parts, the passages between the separators ` ... ` the highlights
 * are joined with. Each page kind's rules run only on that kind's sites, by the result's own
 * address ([Site]), since the same words in another page's prose can be a fact ("the verse
 * alternates Am G", a single titled "All Rights Reserved"). By the kinds of page met on thinly
 * documented tracks (the #767 phone round, 2026-11-04), it takes out:
 *
 * - on a video site: the footer (YouTube's "About Press Copyright … Test new features"), the
 *   channel's stats as listed fields ("- Channel: … - Views: … - Likes: …") and the bits of them a
 *   cut leaves (".7K"), and a transcript's markers ("[Music]", "♪");
 * - on a streaming service: navigation and sign-in text ("Skip to main content", "Sign in to see
 *   lyrics and listen to the full track", "Listen on Audiomack");
 * - on Bandcamp: the rights heading ("### license all rights reserved"), the upcoming shows, and
 *   the supporters' user names: the "supported by" label, and a run of eight or more words without
 *   punctuation, two of them handles (`killer_tofu123`), that follows the label, or starts the part
 *   and meets the track list's table or a heading; never a sentence;
 * - on a chord site: the tuning, capo and "Chords used" fields, a practice tip, and chord symbols
 *   between lyrics (a run of two or more, one with more than its letter: `Dm C6`, `Bbmaj7 G`; not
 *   ending in a bare "A", the likelier article, nor before a word in lower case, as prose has them);
 * - on Letras: its credits page's report links and registry line;
 * - on any page: a lyrics site's fixed sentences, whole ("Compare plain and time-synced lyrics, …",
 *   "Artists like … 10 similar artists"), and a rights notice standing on its own: a part that is
 *   only "All rights reserved", or that sentence right after a © or ℗ notice ending the part,
 *   which stays, since it names the label and the year.
 *
 * A passage taken out of the middle of a part leaves the separator in its place, so the line stays
 * a quote with its gaps shown; chord symbols and a transcript's markers leave a space, since the
 * words around them read on. Each part loses the dashes, bars and bullets at its ends.
 *
 * Then a part is kept if it has a digit (a year, a date, a duration), a credit (a label such as
 * "Music:" or "Written by" before a name, the subject's too), or a word that is neither the
 * subject's (the track's title, its work and its artists, or the album and its artist) nor a
 * page's ([PAGE_WORDS]: "official video", "chords", "YouTube", a site's address). A line with no
 * part left is no line. Everything else stays as the engine gave it: dropping a real fact costs
 * more than keeping a little noise, so the rules match the page text seen, where it is seen.
 */
internal object WebBoilerplate {
    /** The separator between a line's parts: the one highlights are joined with. */
    const val SEPARATOR: String = " ... "

    /**
     * [text] (whitespace collapsed) of the page at [url] without its boilerplate, for the [subject]
     * (names, as given), folded by [fold] as the filter folds them; empty when no part is left.
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
                strip(part, site)
                    .split(PART_BREAK)
                    .map { withoutRights(tidy(it)) }
                    .filter { it.isNotEmpty() && keeps(it, site, words, fold) }
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

    // [part] with its site's rules and the fixed sentences of any page applied.
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

    // A piece of a part without the spaces, dashes, bars and bullets at its ends, often left by a passage taken out.
    private fun tidy(piece: String): String =
        piece
            .replace(SPACES, " ")
            .trim()
            .trimStart { it in LEFT_OVER }
            .trimEnd { it in LEFT_OVER || it == '#' }
            .trim()

    // [piece] without a rights notice standing on its own: all of it, or the sentence after a © notice ending it.
    private fun withoutRights(piece: String): String =
        if (ONLY_RIGHTS.matches(piece)) "" else NOTICE_RIGHTS.replace(piece, "$1")

    /**
     * [text] without chord symbols in runs of two or more, one with more than its letter, the run
     * never ending in a bare "A" and not followed by a word in lower case, which is prose about the
     * chords: a chord sheet's chords above its lyrics. Scanned word by word, so a long highlight
     * cannot exhaust the stack.
     */
    internal fun withoutChords(text: String): String {
        val words = text.split(' ')
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var end = i
            while (end < words.size && CHORD.matches(words[end])) end++
            val chords = if (end > i && words[end - 1] == "A") end - 1 else end
            val run = words.subList(i, chords)
            // Prose names chords mid-sentence ("alternates Am G throughout"); a sheet's next lyric starts a line.
            val inSentence = words.getOrNull(chords)?.firstOrNull { it.isLetter() }?.isLowerCase() == true
            when {
                run.size >= 2 && run.any { it.length > 1 } && !inSentence -> i = chords
                end > i -> {
                    out += words.subList(i, end)
                    i = end
                }
                else -> {
                    out += words[i]
                    i++
                }
            }
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

    // Whether [piece] stays: a digit, a credit, or a word beyond the subject's and a page's.
    private fun keeps(
        piece: String,
        site: Site,
        subject: Set<String>,
        fold: (String) -> String,
    ): Boolean =
        when {
            // What a cut leaves of a video's stats: ".7K", "01T01:42:14-08:".
            site == Site.VIDEO && piece.length <= STATS_BIT_LENGTH && STATS_BIT.matches(piece) -> false
            piece.any { it.isDigit() } -> true
            CREDIT.containsMatchIn(piece) -> true
            else -> saysMore(piece, subject, fold)
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

    /** A piece that is only a rights notice's last sentence. */
    private val ONLY_RIGHTS = Regex("all rights reserved\\.?", IGNORE_CASE)

    /** "All rights reserved" right after a © or ℗ notice that starts the piece or a sentence, ending it. */
    private val NOTICE_RIGHTS =
        Regex("((?:^|(?<=[.!?] ))[©℗] ?\\d{4}[^©℗]{0,100}?\\.) all rights reserved\\.?$", IGNORE_CASE)

    /**
     * A credit: a role's label and a name after it, which a part keeps even when the name is the
     * subject's ("Music: Gordon Lightfoot", "Written by Gordon Lightfoot", "Compositor: …").
     */
    private val CREDIT =
        Regex(
            "(?:(?i:music|lyrics|words|writers?|composers?|lyricists?|songwriters?|producers?|arrangers?|" +
                "compositor|writer/composer)\\s*:|" +
                "(?i:written|composed|produced|arranged|mixed|engineered|recorded) by|(?i:escrita|composta) por:?)" +
                "\\s*\\p{Lu}",
        )

    /** A transcript's markers on a video site, taken out leaving a space: the words around them read on. */
    private val MARKERS: List<Regex> =
        listOf("\\[(?:Music|Applause|Laughter|Cheering|Instrumental)]", "♪").map { Regex(it, IGNORE_CASE) }

    /** Each kind of site's page text, taken out leaving the separator. */
    private val PASSAGES: Map<Site, List<Regex>> =
        mapOf(
            Site.VIDEO to
                listOf(
                    "About Press Copyright Contact us Creators Advertise Developers Terms " +
                        "Privacy(?: Policy & Safety)? How YouTube works Test new features" +
                        "(?: NFL Sunday Ticket)?(?: © \\d{4} Google LLC)?",
                    "(?<=^|\\s)- (?:Channel|Length|Duration|Views|Likes|Dislikes|Comments|Subscribers|Published|" +
                        "Uploaded)\\b(?::.*?)?(?=\\s-(?:\\s|$)|$)",
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
                ),
            Site.BANDCAMP to
                listOf(
                    "#+ license all rights reserved\\.?(?=\\s#|$)",
                    "#+ shows\\b.*?(?=\\s#|$)",
                    "supported by(?=\\s#|$)",
                ),
            Site.CHORDS to
                listOf(
                    "Chords used:",
                    "Tuning: ?(?:Standard(?: Tuning)?|Half Step Down|Drop [A-G]|Open [A-G])?(?: ?\\([A-Ga-g#b ]+\\))?",
                    "Capo: ?(?:no capo|\\+?\\d+ ?(?:st|nd|rd|th)? ?(?:fret)?)",
                    "your practice should emphasi[sz]e these chord progressions\\b.*$",
                ),
            Site.LETRAS to
                listOf(
                    "Ver créditos completos\\.?",
                    "¿Los datos están equivocados\\? Avísanos\\.?",
                    "ECAD verificado obra #\\d+ em \\S+",
                ),
        ).mapValues { (_, rules) -> rules.map { Regex(it, IGNORE_CASE) } }

    /** A lyrics site's fixed sentences, whole, which no prose has: taken out on any page. */
    private val ANY_PAGE: List<Regex> =
        listOf(
            "Format: Lyrics \\+ LRC",
            "Plain lyrics Synced LRC",
            "Compare plain and time-synced lyrics, check other recording versions, and download available " +
                "lyric or subtitle formats\\.",
            "(?:\\d+\\. )?Artists like .{1,60}? \\d+ similar artists(?: \\d+\\.)?",
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
                "single album artist artists listen watch online stream streaming download play hd hq mv " +
                "visualizer topic provided full feat ft featuring lrc " +
                "youtube vevo spotify apple itunes deezer tidal amazon soundcloud bandcamp audiomack pandora " +
                "napster shazam genius musixmatch azlyrics letras ultimate com www"
        ).split(' ').toSet()
}
