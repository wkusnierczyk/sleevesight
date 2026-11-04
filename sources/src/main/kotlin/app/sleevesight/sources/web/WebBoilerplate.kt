package app.sleevesight.sources.web

/**
 * Page boilerplate taken out of a web line before it is kept (#905; `design/design.md`, *Web search
 * provider (D11)*, *Page boilerplate*): the text of a page around its facts, which a search
 * engine's highlight often quotes and which, sent as an evidence line, the model wrote about ("an
 * official video is on YouTube", a rights notice called "a useful detail").
 *
 * [clean] works on the line's parts, the passages between the separators ` ... ` the highlights
 * are joined with. In each part it takes out, by the kinds of page met on thinly documented tracks
 * (the #767 phone round, 2026-11-04):
 *
 * - video sites: the footer (YouTube's "About Press Copyright … Test new features") and the
 *   channel's stats as listed fields ("- Channel: … - Views: … - Likes: …"); a transcript's
 *   markers ("[Music]", "♪");
 * - streaming services: navigation and sign-in text ("Skip to main content", "Sign in to see
 *   lyrics and listen to the full track", "Listen on Audiomack");
 * - stores: Bandcamp's rights line ("license all rights reserved"), its list of upcoming shows,
 *   its "supported by" label, and a run of user names (eight or more words without punctuation,
 *   two of them handles such as `killer_tofu123` or `ju5tintime`, not a duration's `5m 16s`);
 * - chord sites: the tuning, capo and "Chords used" fields, a practice tip, and chord symbols
 *   between lyrics (a run of two or more, one with more than its letter: `Dm C6`, `Bbmaj7 G`;
 *   not ending in a bare "A", the likelier article);
 * - lyrics sites: their format and "similar artists" lines, and a credits page's report links;
 * - any page: "All rights reserved".
 *
 * A passage taken out of the middle of a part leaves the separator in its place, so the line stays
 * a quote with its gaps shown; chord symbols and a transcript's markers leave a space, since the
 * words around them read on. Each part loses the dashes, bars and bullets at its ends.
 * A part left with fewer than two letters, or naming nothing but the subject (the track's title,
 * the work it is from and its artists, or the album and its artist) in a page's words ([PAGE_WORDS]:
 * "official video", "chords", "YouTube", a site's address), is left out. A line with no part left
 * is no line. A © or ℗ notice stays: it names the label and the year. Everything else stays as the
 * engine gave it, so a part with a fact keeps it; a line that names only the track with a page's
 * furniture goes. Dropping a real fact costs more than keeping a little noise, so the rules match
 * the page text seen, not whatever looks like it.
 */
internal object WebBoilerplate {
    /** The separator between a line's parts: the one highlights are joined with. */
    const val SEPARATOR: String = " ... "

    /**
     * [text] (whitespace collapsed) without its boilerplate, for the [subject] (names, as given),
     * folded by [fold] as the filter folds them; empty when no part is left.
     */
    fun clean(
        text: String,
        subject: List<String>,
        fold: (String) -> String,
    ): String {
        val words = subject.flatMap { fold(it).split(' ') }.filter { it.isNotEmpty() }.toSet()
        val parts = text.split(PART_BREAK).map { it.trim() }
        val kept =
            parts.withIndex().flatMap { (i, part) ->
                // A separator the line starts or ends with stays: it shows the quote goes on.
                if (part.isEmpty()) return@flatMap if (i == 0 || i == parts.lastIndex) listOf(part) else emptyList()
                strip(part).split(PART_BREAK).map(::tidy).filter { saysMore(it, words, fold) }
            }
        if (kept.none { it.isNotEmpty() }) return ""
        return kept.joinToString(SEPARATOR).trim()
    }

    // [part] with every rule applied: a passage out of its middle leaves the separator.
    private fun strip(part: String): String {
        var text = part
        for (rule in MARKERS) text = rule.replace(text, " ")
        for (rule in PASSAGES) text = rule.replace(text, SEPARATOR)
        // Before the invisible spaces go: a chord sheet puts one before the lyric after a chord.
        text = CHORD_RUN.replace(text) { if (it.value.split(' ').any { c -> c.length > 1 }) " " else it.value }
        text = INVISIBLE.replace(text, "")
        text = WORD_RUN.replace(text) { if (it.value.split(' ').count(::isHandle) >= 2) SEPARATOR else it.value }
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

    /**
     * Whether [part] says more than [subject] (folded words) in a page's words: at least two
     * letters, and a word (a run of letters or digits, folded) that is neither the subject's, a
     * common word ([RecordingFilter.COMMON_WORDS]), a page's ([PAGE_WORDS]), nor a single letter. A
     * number is a word, so a year or a date keeps its part. A site's address is a page's word.
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
    // not a number with its unit (`5m`, `16s`, `16bit`), which a listing's duration or format has.
    private fun isHandle(word: String): Boolean = '_' in word || HANDLE_DIGIT.containsMatchIn(word)

    private val HANDLE_DIGIT = Regex("\\p{L}\\p{N}")

    // The separator between parts: three dots with a space or the line's end on each side.
    private val PART_BREAK = Regex("(?<=^|\\s)\\.\\.\\.(?=\\s|$)")

    private val SPACES = Regex(" {2,}")

    // Left at a piece's ends once a passage is taken out: list dashes, table bars, bullets, commas.
    private const val LEFT_OVER = " -–—|•·,;"

    // Zero-width spaces and byte-order marks, which chord sheets put between chords and lyrics.
    private val INVISIBLE = Regex("[\\u200B\\uFEFF]")

    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    /** A transcript's markers, taken out leaving a space: the words around them read on. */
    private val MARKERS: List<Regex> =
        listOf("\\[(?:Music|Applause|Laughter|Cheering|Instrumental)]", "♪").map { Regex(it, IGNORE_CASE) }

    /** The page text taken out, each passage leaving the separator. */
    private val PASSAGES: List<Regex> =
        listOf(
            // Video sites: YouTube's footer, the channel's stats.
            "About Press Copyright Contact us Creators Advertise Developers Terms Privacy(?: Policy & Safety)? " +
                "How YouTube works Test new features(?: NFL Sunday Ticket)?(?: © \\d{4} Google LLC)?",
            "(?<=^|\\s)- (?:Channel|Length|Duration|Views|Likes|Dislikes|Comments|Subscribers|Published|Uploaded)\\b" +
                "(?::.*?)?(?=\\s-(?:\\s|$)|$)",
            // Streaming services: navigation and sign-in.
            "Skip to main content",
            "Premium ?Support ?Download",
            "Sign ?up ?Log ?in",
            "Resize main navigation",
            "Sign in to see lyrics and listen to the full track",
            "Recommended Based on this song",
            "Listen on (?:Spotify|Apple Music|Audiomack|Deezer|SoundCloud|YouTube Music|Tidal|Amazon Music|Pandora)",
            "Listen to \\S.{0,80}? online\\.?",
            // Stores: Bandcamp's rights line, its shows, its supporters' label.
            "(?:#+ )?license all rights reserved\\.?",
            "(?<=^|\\s)all rights reserved\\.?",
            "#+ shows\\b.*?(?=\\s#|$)",
            "supported by(?=\\s#|$)",
            // Chord sites: the sheet's fields and a practice tip.
            "Chords used:",
            "Tuning: ?(?:Standard(?: Tuning)?|Half Step Down|Drop [A-G]|Open [A-G])?(?: ?\\([A-Ga-g#b ]+\\))?",
            "Capo: ?(?:no capo|\\+?\\d+ ?(?:st|nd|rd|th)? ?(?:fret)?)",
            "your practice should emphasi[sz]e these chord progressions\\b.*$",
            // Lyrics sites: formats, similar artists, a credits page's report links.
            "Format: Lyrics \\+ LRC",
            "Plain lyrics Synced LRC",
            "Compare plain and time-synced lyrics[^.]*\\.?",
            "(?:\\d+\\. )?Artists like .{1,60}? \\d+ similar artists",
            "Ver créditos completos\\.?",
            "¿Los datos están equivocados\\? Avísanos\\.?",
            "ECAD verificado obra #\\d+ em \\S+",
        ).map { Regex(it, IGNORE_CASE) }

    // A chord symbol, alone between spaces: its letter, an accidental, a quality, a number, a bass note.
    private const val CHORD =
        "[A-G](?:#|♯|b|♭)?(?:maj|min|m|dim|aug|sus|add)?\\d{0,2}(?:/[A-G](?:#|♯|b|♭)?)?"

    /**
     * Two or more chord symbols in a row, taken out when one has more than its letter (not "A E"),
     * never ending in a bare "A", which is likelier the article of the lyric after it ("C A quiet").
     */
    private val CHORD_RUN = Regex("(?<=^|\\s)(?:$CHORD )+(?!A(?=\\s|$))$CHORD(?=\\s|$)")

    /** Eight or more words in a row without punctuation; taken out when two are handles (user names). */
    private val WORD_RUN = Regex("(?<=^|\\s)(?:[\\p{L}\\p{N}_]+ ){7,}[\\p{L}\\p{N}_]+(?=\\s|$)")

    /** A site's address in a part (`Ultimate-Guitar.Com`, `open.spotify.com`), a page's word. */
    private val ADDRESS =
        Regex(
            "[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:com|net|org|fm|io|app|co|uk|br|de|fr|in|me|tv)\\b",
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
