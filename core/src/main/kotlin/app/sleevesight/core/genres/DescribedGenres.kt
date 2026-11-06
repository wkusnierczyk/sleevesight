package app.sleevesight.core.genres

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId

/**
 * Where an artist's description names a genre (#1000): the [line] it is first found in, by the id
 * *Sources* shows it under, and [quote], the line's sentence that names it, cut to the words around
 * the genre, "…" where cut ([quoteAround]).
 */
data class DescribedIn(
    val line: String,
    val quote: String,
)

/**
 * The genres each credited artist's description names (#1000; `design/design.md`, *Genre labels*):
 * a plain match of [vocabulary]'s names ([GenreVocabulary.namedIn]) against the artist's own lines
 * among [lines] (their Last.fm biography, Wikipedia's article on them, web pages about them: lines of
 * [Scope.ARTIST] from [DESCRIBING], not the counts and lists of what many listeners did), no AI. A
 * row for each artist of [artists] (the track's credit, in its order) whose lines name any, each
 * genre once, in the order its lines name them, citing the first line it is found in
 * ([TrackLabel.describedIn]); its labels are of [GenreLevel.ARTIST], for that artist, credited to no
 * label source. The artist's own names in a line name no genre ("Soul II Soul", the band
 * *Electronic*). A line is the artist's its adapter says it is about ([EvidenceLine.about]); one that
 * does not say is the artist's when the track credits one, or, with several, a web page's is the
 * primary artist's (the artist's web search, #912, looks up the primary alone) and another's the
 * first credited artist it names. A genre name that is an everyday word counts only in a music
 * phrase ([EverydayNames]). [withDescribed] places them beside the tags.
 */
fun describedGenres(
    vocabulary: GenreVocabulary,
    lines: List<EvidenceLine>,
    artists: List<String>,
): List<GenreRow> {
    val found = linkedMapOf<String, LinkedHashMap<String, TrackLabel>>()
    val describing = lines.filter { it.scope == Scope.ARTIST && !it.aggregate && it.source in DESCRIBING }
    for (line in describing) {
        val artist = artistOf(line, artists) ?: continue
        val named = found.getOrPut(artist) { linkedMapOf() }
        val names = artists.flatMap { occurrences(line.text, it) }
        val genres =
            vocabulary.namedIn(line.text).filter { (genre, range) ->
                names.none { it.first <= range.last && range.first <= it.last } &&
                    EverydayNames.named(genre.tag, line.text, range)
            }
        for ((genre, range) in genres) {
            if (genre.identity in named) continue
            val label = genre.copy(url = line.url, level = GenreLevel.ARTIST, artist = artist)
            named[genre.identity] =
                TrackLabel(label, emptyList(), describedIn = DescribedIn(line.id, quoteAround(line.text, range)))
        }
    }
    return artists.mapNotNull { artist ->
        found[artist]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let {
            GenreRow(GenreLevel.ARTIST, artist, it, described = true)
        }
    }
}

/**
 * The genre names that are everyday words too (#1000), which a description's text names as a genre
 * only in a music phrase: "dance floor", "world tour", "a house in Berlin", "country of origin" and
 * "soul of the city" name no genre, "a house producer" and "a soul singer" do. A plain match of the
 * genre list against prose would take every such word for a genre, and the list grows (#1011), so the
 * guard is one list here, by the words, whatever the list holds. A name of several words ("indie
 * rock", matched whole first) and a word that is only a genre ("synthpop", "shoegaze") need no
 * guard. Instrumental is guarded apart: everyday only as "instrumental in" or "to" ("instrumental in
 * founding the label"), and a genre before anything else ("an instrumental electro-acoustic band").
 */
internal object EverydayNames {
    /** The single words that name a genre only when followed by one of [MUSIC_NOUNS]. */
    val WORDS: Set<String> =
        setOf(
            "acoustic",
            "alternative",
            "ambient",
            "country",
            "dance",
            "drill",
            "drone",
            "electro",
            "experimental",
            "folk",
            "goth",
            "gothic",
            "gospel",
            "house",
            "indie",
            "industrial",
            "jungle",
            "latin",
            "metal",
            "noise",
            "opera",
            "pop",
            "psychedelic",
            "punk",
            "rap",
            "score",
            "soul",
            "surf",
            "swing",
            "trap",
        )

    /** What makes a music phrase of one of [WORDS] when it follows it, singular or plural. */
    val MUSIC_NOUNS: Set<String> =
        setOf(
            "music",
            "band",
            "group",
            "act",
            "artist",
            "singer",
            "musician",
            "producer",
            "duo",
            "trio",
            "project",
            "scene",
            "record",
            "album",
            "song",
            "track",
            "style",
        )

    // The words after which Instrumental is the everyday word ("instrumental in", "instrumental to").
    private val NOT_INSTRUMENTAL = setOf("in", "to")

    private val NEXT_WORD = Regex("^\\s+([\\p{L}]+)")

    /**
     * Whether the vocabulary tag [tag], found at [range] of [text], names the genre there: always for
     * a tag of several words or one not in [WORDS]; for one of [WORDS], only when the word after it,
     * with nothing but spaces between, is one of [MUSIC_NOUNS] (or its plural); Instrumental, unless
     * the word after it is "in" or "to".
     */
    fun named(
        tag: String,
        text: String,
        range: IntRange,
    ): Boolean {
        val next =
            NEXT_WORD
                .find(text.substring(range.last + 1))
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        return when {
            tag == TrackGenres.INSTRUMENTAL -> next !in NOT_INSTRUMENTAL
            tag.any(Char::isWhitespace) || tag !in WORDS -> true
            else -> next != null && (next in MUSIC_NOUNS || next.removeSuffix("s") in MUSIC_NOUNS)
        }
    }
}

/**
 * These genres with the genres the artists' descriptions name (#1000, [describedGenres]): [found],
 * a row for each artist, kept in [TrackGenres.described]. Of an artist's, those their own tags' row
 * has already are left out, as is Instrumental when lyrics were found ([Singing.dropsInstrumental]);
 * at most [GenreVocabulary.MAX_LABELS] are kept, a row left with none dropped. In place of any before.
 */
fun TrackGenres.withDescribed(found: List<GenreRow>): TrackGenres {
    val kept =
        found.mapNotNull { row ->
            val own =
                rows
                    .filter { it.level == GenreLevel.ARTIST && it.artist == row.artist }
                    .flatMap { it.labels }
                    .map { it.genre.identity }
                    .toSet()
            val labels =
                row.labels
                    .filter { it.genre.identity !in own }
                    .filter { !singing.dropsInstrumental || it.genre.identity != TrackGenres.INSTRUMENTAL }
                    .take(GenreVocabulary.MAX_LABELS)
            labels.takeIf { it.isNotEmpty() }?.let { row.copy(labels = it, described = true) }
        }
    return copy(described = kept)
}

/**
 * Where an artist's description names the genre [identity] (#1000), when its chip is one of the
 * described genres ([TrackGenres.chips]: no tag and no AI's genre gives it); null otherwise.
 */
fun TrackGenres.describedIn(identity: String): DescribedIn? =
    chips().firstOrNull { it.label.genre.identity == identity }?.label?.describedIn

/** The sources whose artist's lines describe them in words (#1000): not Wikidata's or MusicBrainz's templated facts. */
val DESCRIBING: Set<SourceId> = setOf(SourceId.LASTFM, SourceId.WIKIPEDIA, SourceId.WEB)

// The credited artist of [artists] that [line] is about, as [describedGenres] says; null for none.
private fun artistOf(
    line: EvidenceLine,
    artists: List<String>,
): String? {
    line.about?.let { about -> return artists.firstOrNull { it.equals(about, ignoreCase = true) } }
    return when {
        artists.size == 1 -> artists.single()
        line.source == SourceId.WEB -> artists.firstOrNull()
        else -> artists.firstOrNull { occurrences(line.text, it).isNotEmpty() }
    }
}

// Where [name] stands in [text] as whole words, case aside.
private fun occurrences(
    text: String,
    name: String,
): List<IntRange> {
    if (name.isBlank()) return emptyList()
    val pattern =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(name.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    return pattern.findAll(text).map { it.range }.toList()
}

/**
 * The words of [text] around [range] (#1000), as a genre's note quotes them: the sentence it is in,
 * within the quotation marks it is in when the line quotes its source (Last.fm's "The Last.fm
 * biography of … says: "…""), cut to [WORDS_AROUND] words before the genre's name and as many after,
 * "…" where cut, a comma or the like at a cut end left out.
 */
fun quoteAround(
    text: String,
    range: IntRange,
): String {
    val (from, to) = quoted(text, range) ?: (0 to text.length)
    val start =
        SENTENCE_END
            .findAll(text.substring(from, range.first))
            .lastOrNull()
            ?.let { from + it.range.last + 1 } ?: from
    val end =
        SENTENCE_END
            .find(text.substring(range.last + 1, to))
            ?.let { range.last + 1 + it.range.first + it.value.trimEnd().length } ?: to
    val before =
        text
            .substring(start, range.first)
            .trim()
            .split(SPACE)
            .filter(String::isNotEmpty)
    val after =
        text
            .substring(range.last + 1, end)
            .trim()
            .split(SPACE)
            .filter(String::isNotEmpty)
    val name = text.substring(range)
    val head = before.takeLast(WORDS_AROUND)
    val tail = after.take(WORDS_AROUND)
    val cutHead = head.size < before.size
    val cutTail = tail.size < after.size
    val gapBefore = if (range.first > start && text[range.first - 1].isWhitespace()) " " else ""
    val gapAfter = if (range.last + 1 < end && text[range.last + 1].isWhitespace()) " " else ""
    val shown =
        buildString {
            if (cutHead) append(ELLIPSIS)
            append(head.joinToString(" "))
            if (head.isNotEmpty()) append(gapBefore)
            append(name)
            if (tail.isNotEmpty()) append(gapAfter)
            append(tail.joinToString(" "))
        }
    return if (cutTail) shown.trimEnd { it in CUT_PUNCTUATION } + ELLIPSIS else shown
}

// The span of [text] inside the straight double quotes around [range], when there are such.
private fun quoted(
    text: String,
    range: IntRange,
): Pair<Int, Int>? {
    val open = text.lastIndexOf('"', range.first)
    val close = text.indexOf('"', range.last + 1)
    return if (open >= 0 && close > 0) open + 1 to close else null
}

/** The most words a genre's quote keeps on each side of its name ([quoteAround]). */
const val WORDS_AROUND: Int = 5

private const val ELLIPSIS = "…"
private const val CUT_PUNCTUATION = ",;: —–-"
private val SPACE = Regex("\\s+")

// A sentence's end: its final punctuation, any closing quotes or brackets, then a space.
private val SENTENCE_END = Regex("[.!?…]+[\"'”’)\\]]*\\s+")
