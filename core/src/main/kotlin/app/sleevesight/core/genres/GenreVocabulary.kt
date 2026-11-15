package app.sleevesight.core.genres

import app.sleevesight.core.ResourceReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fixed genre vocabulary (`design/design.md`, *Genre labels*): the tags that become genre
 * labels, in one list (the simulator's families and their hues are dropped, #1020: nothing read
 * them once #439 drew the labels plain). Tags outside it (places, decades, "seen live", noise) are
 * dropped. It is source-agnostic: Last.fm's tags go through it, and from Phase 1.0 MusicBrainz's (D12, #84;
 * [MusicBrainzLabels]), whose labels [combineLabels] joins to Last.fm's; the track's, the artists'
 * and the album's alike (#439; [GenreLevel]).
 *
 * The data is the resource [RESOURCE], first taken from the simulator's `GENRE_FAMILIES`.
 */
class GenreVocabulary(
    tags: List<String>,
    private val labelOverrides: Map<String, String> = emptyMap(),
    synonyms: List<List<String>> = emptyList(),
    musicBrainz: Map<String, String> = emptyMap(),
    wikipedia: Map<String, String> = emptyMap(),
    broader: Map<String, String> = emptyMap(),
) {
    // The tags, in the vocabulary's order.
    private val known: Set<String> =
        buildSet {
            for (tag in tags) {
                require(tag == key(tag)) { "tag '$tag' is not lowercase and trimmed" }
                require(add(tag)) { "tag '$tag' is listed twice" }
            }
        }

    /** The vocabulary's tags, in its order. */
    val tags: Set<String> get() = known

    private val synonymOf: Map<String, String> =
        buildMap {
            for (group in synonyms) {
                require(group.all { it in known }) { "synonyms $group are not all in the vocabulary" }
                for (tag in group) {
                    require(
                        put(tag, spelling(group.first())) == null,
                    ) { "tag '$tag' is in two synonym groups" }
                }
            }
        }

    // Each genre's MusicBrainz genre, by identity: the first of its tags to have one.
    private val musicBrainzOf: Map<String, String> =
        buildMap {
            for ((tag, mbid) in musicBrainz) {
                val genre = requireNotNull(identity(tag)) { "a MusicBrainz genre for '$tag', not in the vocabulary" }
                putIfAbsent(genre, mbid)
            }
        }

    // Each genre's English Wikipedia article, by identity, for a genre MusicBrainz cannot define (#852).
    private val articleOf: Map<String, String> =
        buildMap {
            for ((tag, title) in wikipedia) {
                val genre = requireNotNull(identity(tag)) { "a Wikipedia article for '$tag', not in the vocabulary" }
                require(put(genre, title) == null) { "two Wikipedia articles for '$genre'" }
            }
        }

    // Each genre's broader genre, by identity, as the tag that names it (#1127).
    private val broaderOf: Map<String, String> =
        buildMap {
            for ((tag, wider) in broader) {
                val genre = requireNotNull(identity(tag)) { "a broader genre for '$tag', not in the vocabulary" }
                val of = requireNotNull(identity(wider)) { "broader genre '$wider' of '$tag', not in the vocabulary" }
                require(of != genre) { "'$tag' is its own broader genre" }
                require(put(genre, key(wider)) == null) { "two broader genres for '$genre'" }
            }
        }

    init {
        // No genre is broader than itself through others: the panel's link would go round.
        for (start in broaderOf.keys) {
            val seen = mutableSetOf(start)
            var at = broaderOf[start]?.let(::identity)
            while (at != null) {
                require(seen.add(at)) { "broader genres go round from '$start'" }
                at = broaderOf[at]?.let(::identity)
            }
        }
    }

    /**
     * The broader genre of the genre [identity] stands for ([identity]), as its label (#1127: a style
     * of it, *Contemporary R&B* of *R&B*), from MusicBrainz's genre relationships (*subgenre of*,
     * CC0) as the vocabulary keeps them; null for a genre with none the vocabulary holds.
     */
    fun broader(identity: String): GenreLabel? = broaderOf[identity]?.let { labelsFor(listOf(it)).firstOrNull() }

    /**
     * The MusicBrainz genre (its MBID, CC0) of the genre [identity] stands for ([identity]), through
     * which its definition is found (#523: the genre's Wikidata item, then its English Wikipedia
     * article), unless the vocabulary names an article for it ([wikipediaArticle], #852); null for a
     * genre that is not one of MusicBrainz's.
     */
    fun musicBrainzGenre(identity: String): String? = musicBrainzOf[identity]

    /**
     * The English Wikipedia article, by title, that defines the genre [identity] stands for, named
     * in the vocabulary for a genre with no MusicBrainz genre that fits or whose genre has no
     * English article (#852: *acoustic* is *Acoustic music*); null for the rest, defined through
     * [musicBrainzGenre]. A genre with one is defined by it alone.
     */
    fun wikipediaArticle(identity: String): String? = articleOf[identity]

    /**
     * What a vocabulary tag stands for, so spelling variants count as one genre: its letters and
     * digits (`hip-hop` and `hip hop` are `hiphop`), or its synonym group's (`r&b` and `rnb`).
     * Null for a tag outside the vocabulary.
     */
    fun identity(tag: String): String? = key(tag).takeIf { it in known }?.let { synonymOf[it] ?: spelling(it) }

    /**
     * The genre labels for [tags], in their order (the source's, most applied first), at most
     * [MAX_LABELS] while [capped]: each tag lowercased and trimmed, kept if it is in the vocabulary, once per
     * [identity]: two spellings of one genre (`hip-hop` and `hip hop`, `r&b` and `rnb`) give one
     * label, the first. As the simulator's `genresFrom`, except for that. [url] is the page the tags
     * are from (the track's, artist's or album's, at the source), for the label's credit; null when
     * there is none. [level] is what the tags were given to, and [artist] the credited artist they
     * describe, for an artist's ([GenreLabel.artist]). [votes] are the tags' votes, by tag, where the
     * source counts them (MusicBrainz's recording tags, #799), for each label's [GenreLabel.votes].
     * [counts] are the tags' counts at the source, by tag, where it gives them (#1019: MusicBrainz's
     * votes, Last.fm's weight out of 100), all of them, those outside the vocabulary too: each
     * label's [GenreLabel.weight] is its tag's count over the most any tag has, or over [fullCount]
     * where that is more, so a source's top tag counted by few weighs less than 1. With [capped] false
     * every label of the tags is given, for a label source's, whose cap [combineLabels] applies once
     * the sources' labels are weighed together.
     */
    @Suppress("LongParameterList") // The tags, where they are from and what of, their votes and counts, the cap.
    fun labelsFor(
        tags: List<String>,
        url: String? = null,
        level: GenreLevel = GenreLevel.ARTIST,
        artist: String? = null,
        votes: Map<String, Int> = emptyMap(),
        counts: Map<String, Int> = emptyMap(),
        capped: Boolean = true,
        fullCount: Int = 1,
    ): List<GenreLabel> {
        val seen = mutableSetOf<String>()
        val votesOf = votes.mapKeys { key(it.key) }
        val countOf = counts.mapKeys { key(it.key) }
        val top =
            countOf.values
                .maxOrNull()
                ?.takeIf { it > 0 }
                ?.coerceAtLeast(fullCount)
        val labels =
            tags
                .asSequence()
                .map(::key)
                .filter { it in known }
                .map { tag -> tag to (synonymOf[tag] ?: spelling(tag)) }
                .filter { (_, identity) -> seen.add(identity) }
                .map { (tag, identity) ->
                    val label = labelOverrides[tag] ?: titleCase(tag)
                    val weight = top?.let { most -> countOf[tag]?.let { it.toDouble() / most } }
                    GenreLabel(tag, label, identity, url, level, artist, votesOf[tag], weight)
                }
        return (if (capped) labels.take(MAX_LABELS) else labels).toList()
    }

    /**
     * As [labelsFor] for a label source's [tags] with their counts ([CountedTag]), uncapped: every
     * label, each with its [GenreLabel.weight], for [combineLabels] to weigh and cap (#1019); see
     * [labelsFor] for [fullCount].
     */
    fun sourceLabels(
        tags: List<CountedTag>,
        url: String?,
        level: GenreLevel,
        artist: String? = null,
        fullCount: Int = 1,
    ): List<GenreLabel> =
        labelsFor(
            tags.map { it.name },
            url,
            level,
            artist,
            counts = tags.mapNotNull { tag -> tag.count?.let { tag.name to it } }.toMap(),
            capped = false,
            fullCount = fullCount,
        )

    // The tags of each spelling (letters and digits, case aside), in the vocabulary's order, for
    // [namedIn]; and the most words a tag has.
    private val bySpelling: Map<String, List<String>> by lazy { known.groupBy { spelling(it) } }
    private val mostWords: Int by lazy { known.maxOfOrNull { TAG_WORD.findAll(it).count() } ?: 0 }

    /**
     * The genres [text] names (#1000), in its order: each run of words whose spelling ([identity]'s
     * folding: case, punctuation and spacing aside) is a vocabulary tag's, on word boundaries, a
     * name of several words whole, the longest name first where names overlap ("indie rock", not
     * "indie" and "rock"). A word is letters and digits, with hyphens, apostrophes or `&` inside it:
     * "post-punk" and "R&B" are one word each, so "electro-acoustic" names no *Acoustic* and
     * "folklore" no *Folk*. A run of several words names only a tag of several words itself ("dance
     * hall" names no *Dancehall*, "high life" no *Highlife*). The words of a name of several are
     * spaced (or joined by a spaced `&`, or spaced with apostrophes beside them: "rock 'n' roll"), so
     * "hip hop", "hip-hop" and "Hiphop" all name *Hip-Hop*. Each with the [IntRange] of [text] it spans.
     */
    fun namedIn(text: String): List<Pair<GenreLabel, IntRange>> {
        val words = NAME_WORD.findAll(text).toList()
        val found = mutableListOf<Pair<GenreLabel, IntRange>>()
        var at = 0
        while (at < words.size) {
            val span =
                (minOf(mostWords, words.size - at) downTo 1).firstNotNullOfOrNull { count ->
                    val run = words.subList(at, at + count)
                    val spaced =
                        run.zipWithNext().all { (a, b) -> GAP.matches(text.substring(a.range.last + 1, b.range.first)) }
                    // A run of several words names only a tag of several words ("hip hop", not
                    // "dance hall" for Dancehall); one word, any tag of its spelling.
                    val tags = bySpelling[run.joinToString("") { spelling(key(it.value)) }].orEmpty()
                    val tag = tags.firstOrNull { count == 1 || TAG_WORD.findAll(it).count() > 1 }
                    tag?.takeIf { spaced }?.let { count to it }
                }
            if (span == null) {
                at++
            } else {
                val (count, tag) = span
                val range = words[at].range.first..words[at + count - 1].range.last
                labelsFor(listOf(tag)).firstOrNull()?.let { found += it to range }
                at += count
            }
        }
        return found
    }

    companion object {
        // A word of a tag, for counting them.
        private val TAG_WORD = Regex("[\\p{L}\\p{N}]+")

        // A word of text as [namedIn] reads it: letters and digits, with hyphens, apostrophes or `&` inside.
        private val NAME_WORD = Regex("[\\p{L}\\p{N}]+(?:[-‐‑'’&][\\p{L}\\p{N}]+)*")

        // What may stand between the words of one name in text: spaces, with apostrophes beside them
        // ("rock 'n' roll", straight or curly), or a spaced `&` ("R & B").
        private val GAP = Regex("[\\s'‘’]*\\s[\\s'‘’]*|\\s*&\\s*")

        /** The vocabulary's path among `:core`'s resources. */
        const val RESOURCE: String = "sleevesight/genre-vocabulary.json"

        /** The most labels a row of a track's genres shows, as in the simulator ([combineLabels]). */
        const val MAX_LABELS: Int = 5

        private val json = Json { ignoreUnknownKeys = false }

        /** The vocabulary bundled with `:core`. */
        fun load(resources: ResourceReader): GenreVocabulary =
            parse(checkNotNull(resources.text(RESOURCE)) { "no genre vocabulary at $RESOURCE" })

        /** A vocabulary in the resource's format. */
        fun parse(text: String): GenreVocabulary {
            val file = json.decodeFromString<VocabularyFile>(text)
            return GenreVocabulary(
                file.tags,
                file.labels,
                file.synonyms,
                file.musicbrainz,
                file.wikipedia,
                file.broader,
            )
        }

        private fun key(tag: String): String = tag.trim().lowercase()

        private fun spelling(tag: String): String = tag.filter(Char::isLetterOrDigit)

        /** Each word's first letter or digit uppercased, as the simulator's `\b\w` replacement does. */
        private fun titleCase(tag: String): String =
            buildString(tag.length) {
                tag.forEachIndexed { i, c ->
                    val startsWord = i == 0 || !tag[i - 1].isLetterOrDigit()
                    append(if (startsWord) c.uppercaseChar() else c)
                }
            }
    }
}

/**
 * A genre label: the vocabulary [tag] it came from and the [label] shown.
 *
 * @property identity the genre it stands for ([GenreVocabulary.identity]), by which labels from two
 *   sources, or two artists, are the same label (`hip-hop` and `hip hop`).
 * @property url the page whose tags gave it, at the source (the track's, artist's or album's, on
 *   Last.fm or MusicBrainz), which the label links for its credit; null when the source gave none.
 * @property level what the tags were given to: the track, an artist or the album (#439).
 * @property artist for an artist's label, the credited artist, as the track names it, whose tags
 *   gave it; null for the track's and the album's.
 * @property votes the net votes on the tag that gave it, where the source counts votes: MusicBrainz's
 *   recording tags (#799), by which a track's label is backed among the chips rather than weak
 *   ([TrackGenres.chips], #875). Null where it does not: Last.fm's counts are weights out of 100, not
 *   votes, and an artist's MusicBrainz tags are kept without theirs.
 * @property weight its tag's count at the source over the most any of the page's tags has, so 1 for
 *   the top tag, unless that is counted by fewer than its source's full count
 *   ([MusicBrainzLabels.FULL_WEIGHT_VOTES]), over which it is taken instead (#1019): MusicBrainz's
 *   votes, Last.fm's weight out of 100 ([GenreVocabulary.labelsFor]'s `counts`), by which
 *   [combineLabels] weighs the sources' labels together. Null where the source gave no counts
 *   (Last.fm's five tags of `artist.getInfo`, a fake's tags): weighed by its place.
 */
data class GenreLabel(
    val tag: String,
    val label: String,
    val identity: String,
    val url: String? = null,
    val level: GenreLevel = GenreLevel.ARTIST,
    val artist: String? = null,
    val votes: Int? = null,
    val weight: Double? = null,
)

/**
 * A tag as a label source gives it (#1019): its [name], and its [count] where the source gives one
 * (MusicBrainz's net votes, Last.fm's weight out of 100 for its top tag), null where it gives none.
 */
@Serializable
data class CountedTag(
    val name: String,
    val count: Int? = null,
)

@Serializable
@Suppress("LongParameterList") // The file's keys, each a part of the vocabulary.
private class VocabularyFile(
    @Suppress("unused") val about: String = "",
    val tags: List<String>,
    val labels: Map<String, String> = emptyMap(),
    val synonyms: List<List<String>> = emptyList(),
    val musicbrainz: Map<String, String> = emptyMap(),
    val wikipedia: Map<String, String> = emptyMap(),
    val broader: Map<String, String> = emptyMap(),
)
