package app.sleevesight.core.genres

import app.sleevesight.core.ResourceReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fixed genre vocabulary (`design/design.md`, *Genre labels*): the tags that become genre
 * labels, each in a family (with a hue, for the genre cards; the labels themselves are drawn
 * plain, #439). Tags outside it (places, decades, "seen live", noise) are dropped. It is
 * source-agnostic: Last.fm's tags go through it, and from Phase 1.0 MusicBrainz's (D12, #84;
 * [MusicBrainzLabels]), whose labels [combineLabels] joins to Last.fm's; the track's, the artists'
 * and the album's alike (#439; [GenreLevel]).
 *
 * The data is the resource [RESOURCE], first taken from the simulator's `GENRE_FAMILIES`.
 */
class GenreVocabulary(
    val families: List<GenreFamily>,
    private val labelOverrides: Map<String, String> = emptyMap(),
    synonyms: List<List<String>> = emptyList(),
    musicBrainz: Map<String, String> = emptyMap(),
    wikipedia: Map<String, String> = emptyMap(),
) {
    private val familyOf: Map<String, GenreFamily> =
        buildMap {
            for (family in families) {
                require(family.hue in HUES) { "hue of ${family.id} is out of range" }
                for (tag in family.tags) {
                    require(tag == key(tag)) { "tag '$tag' is not lowercase and trimmed" }
                    require(put(tag, family) == null) { "tag '$tag' is in two families" }
                }
            }
        }

    /** The vocabulary's tags. */
    val tags: Set<String> get() = familyOf.keys

    private val synonymOf: Map<String, String> =
        buildMap {
            for (group in synonyms) {
                require(group.all { it in familyOf }) { "synonyms $group are not all in the vocabulary" }
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
    fun identity(tag: String): String? = key(tag).takeIf { it in familyOf }?.let { synonymOf[it] ?: spelling(it) }

    /**
     * The genre labels for [tags], in their order (the source's, most applied first), at most
     * [MAX_LABELS]: each tag lowercased and trimmed, kept if it is in the vocabulary, once per
     * [identity]: two spellings of one genre (`hip-hop` and `hip hop`, `r&b` and `rnb`) give one
     * label, the first. As the simulator's `genresFrom`, except for that. [url] is the page the tags
     * are from (the track's, artist's or album's, at the source), for the label's credit; null when
     * there is none. [level] is what the tags were given to, and [artist] the credited artist they
     * describe, for an artist's ([GenreLabel.artist]). [votes] are the tags' votes, by tag, where the
     * source counts them (MusicBrainz's recording tags, #799), for each label's [GenreLabel.votes].
     * The genres of [keepBeyondCap] (identities) past the cap follow, [GenreLabel.beyondCap], so that
     * what the tags include is known though no row shows them (#996: an artist's Instrumental).
     */
    @Suppress("LongParameterList") // The tags, where they are from and what of, their votes, what passes the cap.
    fun labelsFor(
        tags: List<String>,
        url: String? = null,
        level: GenreLevel = GenreLevel.ARTIST,
        artist: String? = null,
        votes: Map<String, Int> = emptyMap(),
        keepBeyondCap: Set<String> = emptySet(),
    ): List<GenreLabel> {
        val seen = mutableSetOf<String>()
        val votesOf = votes.mapKeys { key(it.key) }
        return tags
            .asSequence()
            .map(::key)
            .mapNotNull { tag -> familyOf[tag]?.let { tag to it } }
            .map { (tag, family) -> Triple(tag, family, synonymOf[tag] ?: spelling(tag)) }
            .filter { (_, _, identity) -> seen.add(identity) }
            .withIndex()
            .filter { (at, found) -> at < MAX_LABELS || found.third in keepBeyondCap }
            .map { (at, found) ->
                val (tag, family, identity) = found
                val label = labelOverrides[tag] ?: titleCase(tag)
                GenreLabel(
                    tag,
                    family.id,
                    label,
                    identity,
                    url,
                    level,
                    artist,
                    votesOf[tag],
                    beyondCap =
                        at >= MAX_LABELS,
                )
            }.toList()
    }

    // Each tag by its spelling (letters and digits, case aside), the first tag of a spelling in the
    // vocabulary's order, for [namedIn]; and the most words a tag has.
    private val bySpelling: Map<String, String> by lazy {
        buildMap { for (tag in familyOf.keys) putIfAbsent(spelling(tag), tag) }
    }
    private val mostWords: Int by lazy { familyOf.keys.maxOfOrNull { TAG_WORD.findAll(it).count() } ?: 0 }

    /**
     * The genres [text] names (#1000), in its order: each run of words whose spelling ([identity]'s
     * folding: case, punctuation and spacing aside) is a vocabulary tag's, on word boundaries, a
     * name of several words whole, the longest name first where names overlap ("indie rock", not
     * "indie" and "rock"). A word is letters and digits, with hyphens, apostrophes or `&` inside it:
     * "post-punk" and "R&B" are one word each, so "electro-acoustic" names no *Acoustic* and
     * "folklore" no *Folk*. The words of a name of several are spaced (or joined by a spaced `&`),
     * so "hip hop", "hip-hop" and "Hiphop" all name *Hip-Hop*. Each with the [IntRange] of [text] it spans.
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
                    bySpelling[run.joinToString("") { spelling(key(it.value)) }]?.takeIf { spaced }?.let { count to it }
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

        // What may stand between the words of one name in text: spaces, or a spaced `&` ("R & B").
        private val GAP = Regex("\\s+|\\s*&\\s*")

        /** The vocabulary's path among `:core`'s resources. */
        const val RESOURCE: String = "sleevesight/genre-vocabulary.json"

        /** The most labels a track shows, as in the simulator. */
        const val MAX_LABELS: Int = 5

        private const val FULL_CIRCLE = 360
        private val HUES = 0 until FULL_CIRCLE

        private val json = Json { ignoreUnknownKeys = false }

        /** The vocabulary bundled with `:core`. */
        fun load(resources: ResourceReader): GenreVocabulary =
            parse(checkNotNull(resources.text(RESOURCE)) { "no genre vocabulary at $RESOURCE" })

        /** A vocabulary in the resource's format. */
        fun parse(text: String): GenreVocabulary {
            val file = json.decodeFromString<VocabularyFile>(text)
            return GenreVocabulary(
                file.families.map { GenreFamily(it.id, it.hue, it.tags) },
                file.labels,
                file.synonyms,
                file.musicbrainz,
                file.wikipedia,
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

/** A family of genres, with its [hue] (0 to 359), for the genre cards; labels are not drawn in it (#439). */
data class GenreFamily(
    val id: String,
    val hue: Int,
    val tags: List<String>,
)

/**
 * A genre label: the vocabulary [tag] it came from, its [family], and the [label] shown.
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
 * @property beyondCap a tag past the [GenreVocabulary.MAX_LABELS] the source's labels show, kept only
 *   so the track knows its artist's tags include it ([GenreVocabulary.labelsFor]'s `keepBeyondCap`,
 *   #996: Instrumental, for a featured artist); on no row ([combineGenres]).
 */
data class GenreLabel(
    val tag: String,
    val family: String,
    val label: String,
    val identity: String,
    val url: String? = null,
    val level: GenreLevel = GenreLevel.ARTIST,
    val artist: String? = null,
    val votes: Int? = null,
    val beyondCap: Boolean = false,
)

@Serializable
private class VocabularyFile(
    @Suppress("unused") val about: String = "",
    val families: List<FamilyEntry>,
    val labels: Map<String, String> = emptyMap(),
    val synonyms: List<List<String>> = emptyList(),
    val musicbrainz: Map<String, String> = emptyMap(),
    val wikipedia: Map<String, String> = emptyMap(),
)

@Serializable
private class FamilyEntry(
    val id: String,
    val hue: Int,
    val tags: List<String>,
)
