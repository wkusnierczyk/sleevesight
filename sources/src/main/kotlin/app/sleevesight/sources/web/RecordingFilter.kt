package app.sleevesight.sources.web

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.normalizer.AccentFolder

/**
 * Keeps web search results about the right recording (D11; `design/design.md`, *Web search
 * provider (D11)*), whatever the provider. Phrases are matched whole, after [fold]ing case, accents
 * and punctuation, in the result's title and snippet together. A result is kept when:
 *
 * - it names the song: the track's title, and the work it is from when the player gave one
 *   ([NormalizedTrack.from]: `Theme` from Coronation Street, not any London Music Works theme);
 * - it names a credited artist, a *distinctive* one when the track has one ([requiredArtists]:
 *   Van Morrison for Them and Van Morrison, since "them" is in any text);
 * - its own title does not name such an artist without the song. A page titled after the artist
 *   alone is about the artist or another of their recordings (Yanjoco's "Caravan", whose page
 *   lists "I Will Follow You Into The Dark" among the artist's other songs), so a mention in its
 *   snippet does not make it about this recording.
 *
 * This is what turns away a different song with the same title (the Alphaville "Forever Young"
 * for dearoldfriend's) and a different artist's song (Juice WRLD's "Bad Energy" for Reem's).
 * Results from sources the pipeline already reads, with their own entity checks
 * ([OWN_SOURCE_HOSTS]), are dropped too, so an unverified copy of their text cannot slip in here.
 *
 * Its limits: an artist whose every word is common ("To Twelve", "a balladeer") is matched by any
 * text with those words, so for such an artist the title does most of the work; a page on another
 * recording that names the song and the artist in passing still passes; and a listing titled with
 * several artists, this one among them, but not the song ("Below soundtrack: Ray Johnson And The
 * Sou' Westers, Die Hexen and others") is dropped by the third rule, even when it lists the song.
 */
@Suppress("TooManyFunctions") // The rules for a recording, an album and an artist, and the folding they share.
class RecordingFilter(
    private val accentFolder: AccentFolder,
) {
    /**
     * Whether a result with [title], [snippet] and [url] is about [track]. Without [wholeWords] (the
     * search in a language whose script puts no spaces between words, #902: Thai, Japanese, Chinese),
     * a name counts wherever it stands in the text, not only between word boundaries.
     */
    fun isAbout(
        track: NormalizedTrack,
        title: String?,
        snippet: String,
        url: String,
        wholeWords: Boolean = true,
    ): Boolean = names(listOfNotNull(track.title, track.from), requiredArtists(track), title, snippet, url, wholeWords)

    /**
     * Whether a result with [title], [snippet] and [url] is about the [album] by [artist] (#885,
     * `ExaAlbumSearch`), by the rules for a recording, the album's title in the song's place: it
     * names the album and the artist, and its own title does not name the artist without the album.
     * Its limit beside a recording's: a page on the album's title track names both too.
     */
    fun isAboutAlbum(
        album: String,
        artist: String,
        title: String?,
        snippet: String,
        url: String,
    ): Boolean = names(listOf(album), requiredArtists(listOf(artist)), title, snippet, url)

    /**
     * Whether a result with [title], [snippet] and [url] is about the [artist] (#912,
     * `ExaArtistSearch`): it names the artist, whole, in its title or snippet, and is not from a
     * source the pipeline reads itself ([OWN_SOURCE_HOSTS]), whose artist pages have their own
     * checks. A page titled after the artist is the artist's own, as wanted here. Its limit: an
     * artist whose name is made of common words ("The National") is named by pages on other things.
     */
    fun isAboutArtist(
        artist: String,
        title: String?,
        snippet: String,
        url: String,
    ): Boolean {
        val name = fold(artist)
        return !isOwnSource(url) && name.isNotEmpty() && " $name " in padded("${title.orEmpty()} $snippet")
    }

    /** Whether the [artist] can be checked at all: its name folds to something. Else it is not searched. */
    fun canCheckArtist(artist: String): Boolean = fold(artist).isNotEmpty()

    // Whether the result names each of [song] (the title, and the work it is from) and one of
    // [artists] (folded), and its title does not name such an artist without the song.
    @Suppress("LongParameterList") // What it must name, the result's three parts, and how.
    private fun names(
        song: List<String>,
        artists: List<String>,
        title: String?,
        snippet: String,
        url: String,
        wholeWords: Boolean = true,
    ): Boolean {
        if (isOwnSource(url)) return false
        val words: (String) -> String = if (wholeWords) ::padded else ::fold
        val heading = words(title.orEmpty())
        val text = words("${title.orEmpty()} $snippet")
        val phrases = song.map(words)
        val required = artists.map { if (wholeWords) " $it " else it }
        val namesSong = phrases.none { it.isBlank() } && phrases.all { it in text }
        val namesArtist = required.any { it in text }
        val titledAfterOther = required.any { it in heading } && !phrases.all { it in heading }
        return namesSong && namesArtist && !titledAfterOther
    }

    /**
     * Whether [track] can be checked at all: its title, its work when it has one, and at least one
     * credited artist fold to something. A track that cannot be checked is not searched.
     */
    fun canCheck(track: NormalizedTrack): Boolean =
        fold(track.title).isNotEmpty() &&
            track.from?.let { fold(it).isNotEmpty() } != false &&
            requiredArtists(track).isNotEmpty()

    /** Whether the [album] by [artist] can be checked at all: both fold to something. Else it is not searched. */
    fun canCheckAlbum(
        album: String,
        artist: String,
    ): Boolean = fold(album).isNotEmpty() && requiredArtists(listOf(artist)).isNotEmpty()

    /**
     * The credited artists a result must name one of, folded: the distinctive ones, those with a
     * word outside [COMMON_WORDS], or all of them when none is distinctive.
     */
    internal fun requiredArtists(track: NormalizedTrack): List<String> = requiredArtists(track.artists)

    private fun requiredArtists(artists: List<String>): List<String> {
        val all = artists.map(::fold).filter { it.isNotEmpty() }
        return all.filter { name -> name.split(' ').any { it !in COMMON_WORDS } }.ifEmpty { all }
    }

    // [phrase] folded, with a space either side, so `in` matches whole words only.
    private fun padded(phrase: String): String = " ${fold(phrase)} "

    /**
     * [text] lowercased, decomposed, without the Latin combining marks (accents: U+0300 to U+036F),
     * the marks of other scripts kept in their word (#902: an Indic or Thai vowel sign, a virama, so
     * "तुम ही हो" is not "तुम ही है"), with `&` read as `and`, apostrophes
     * (`'`, `’`, `ʼ`) dropped so "Don't" and "Dont" agree, except before a possessive `s`, which
     * stays a word of its own so "Lightfoot's" still names Lightfoot, every other run of characters
     * other than letters and digits (in any script) made one space, and trimmed.
     */
    internal fun fold(text: String): String {
        val decomposed = accentFolder.decompose(text.lowercase())
        val out = StringBuilder(decomposed.length)
        var space = false
        for ((i, c) in decomposed.withIndex()) {
            when {
                c in COMBINING_MARKS -> {
                    continue
                }

                c in APOSTROPHES -> {
                    if (isPossessive(decomposed, i)) space = true
                }

                c == '&' -> {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append("and")
                    space = true
                }

                c.isLetterOrDigit() || joinsWord(c, out, space) -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    out.append(c)
                    space = false
                }
                else -> space = true
            }
        }
        return out.toString()
    }

    // Whether [c] is another script's mark following a letter in [out], kept with it in its word.
    private fun joinsWord(
        c: Char,
        out: StringBuilder,
        space: Boolean,
    ): Boolean = c.category in SCRIPT_MARKS && out.isNotEmpty() && !space

    // Whether the apostrophe at [i] is followed by an `s` that ends the word: `lightfoot's`. [text]
    // is decomposed, so an accented `s` is an `s` and a combining mark, which does not end it.
    private fun isPossessive(
        text: String,
        i: Int,
    ): Boolean =
        text.getOrNull(i + 1) == 's' &&
            text.getOrNull(i + 2).let { it == null || !(it.isLetterOrDigit() || it in COMBINING_MARKS) }

    companion object {
        /**
         * Hosts of sources the pipeline reads through their own adapters, which verify their
         * matches (architecture, *Entity resolution*). A host here matches itself and its
         * subdomains. Wikiwand is a mirror of Wikipedia. The same list goes to the provider as an
         * exclusion where it takes one.
         */
        val OWN_SOURCE_HOSTS: List<String> =
            listOf("wikipedia.org", "wikiwand.com", "wikidata.org", "musicbrainz.org", "last.fm", "discogs.com")

        private val COMBINING_MARKS = '\u0300'..'\u036F'

        // The categories of the marks another script's letters carry, kept with the letter they follow.
        private val SCRIPT_MARKS =
            setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

        // ASCII apostrophe, right single quotation mark, modifier letter apostrophe.
        private val APOSTROPHES = setOf('\'', '\u2019', '\u02BC')

        /**
         * Words too common to identify an artist on their own: articles, pronouns, prepositions,
         * conjunctions and small numbers. An artist named only with these ("Them", "To Twelve")
         * is not distinctive.
         */
        internal val COMMON_WORDS: Set<String> =
            (
                "a an the and or of to in on at by for with from into over under up down out off " +
                    "i me my we us our you your he him his she her it its they them their " +
                    "this that these those is are be been no not all so as one two three four five " +
                    "six seven eight nine ten eleven twelve"
            ).split(' ').toSet()

        /** Whether [url]'s host is one of [OWN_SOURCE_HOSTS] or below one. */
        internal fun isOwnSource(url: String): Boolean {
            val host = hostOf(url) ?: return false
            return OWN_SOURCE_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** The authority of an absolute URL, without user info or port, lowercased; null if none. */
        internal fun hostOf(url: String): String? {
            val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isEmpty()) return null
            val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
            val host =
                authority
                    .substringAfterLast('@')
                    .substringBefore(':')
                    .trimEnd('.')
                    .lowercase()
            return host.ifEmpty { null }
        }
    }
}
