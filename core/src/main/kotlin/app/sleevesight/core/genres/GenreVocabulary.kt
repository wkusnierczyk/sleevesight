package app.sleevesight.core.genres

import app.sleevesight.core.ResourceReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fixed genre vocabulary (`design/design.md`, *Genre labels*): the tags that become genre
 * labels, each in a family with a fixed hue. Tags outside it (places, decades, "seen live", noise)
 * are dropped. It is source-agnostic: Last.fm's artist tags go through it, and from Phase 1.0
 * MusicBrainz's (D12, #84; [MusicBrainzLabels]), whose labels [combineLabels] joins to Last.fm's.
 *
 * The data is the resource [RESOURCE], taken from the simulator's `GENRE_FAMILIES`; a test checks
 * the two agree.
 */
class GenreVocabulary(
    val families: List<GenreFamily>,
    private val labelOverrides: Map<String, String> = emptyMap(),
    synonyms: List<List<String>> = emptyList(),
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
     * are from (the artist's, at the source), for the label's credit; null when there is none.
     */
    fun labelsFor(
        tags: List<String>,
        url: String? = null,
    ): List<GenreLabel> {
        val seen = mutableSetOf<String>()
        return tags
            .asSequence()
            .map(::key)
            .mapNotNull { tag -> familyOf[tag]?.let { tag to it } }
            .map { (tag, family) -> Triple(tag, family, synonymOf[tag] ?: spelling(tag)) }
            .filter { (_, _, identity) -> seen.add(identity) }
            .take(MAX_LABELS)
            .map { (tag, family, identity) ->
                GenreLabel(tag, family.id, family.hue, labelOverrides[tag] ?: titleCase(tag), identity, url)
            }.toList()
    }

    companion object {
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

/** A family of genres, which gives its labels their [hue] (0 to 359). */
data class GenreFamily(
    val id: String,
    val hue: Int,
    val tags: List<String>,
)

/**
 * A genre label: the vocabulary [tag] it came from, its [family] and [hue], and the [label] shown.
 * Labels are artist-level: they come from the artist's tags, not the recording's.
 *
 * @property identity the genre it stands for ([GenreVocabulary.identity]), by which labels from two
 *   sources, or two artists, are the same label (`hip-hop` and `hip hop`).
 * @property url the page of the artist whose tags gave it, at the source (Last.fm's artist page,
 *   MusicBrainz's), which the label links for its credit; null when the source gave none.
 */
data class GenreLabel(
    val tag: String,
    val family: String,
    val hue: Int,
    val label: String,
    val identity: String,
    val url: String? = null,
)

@Serializable
private class VocabularyFile(
    @Suppress("unused") val about: String = "",
    val families: List<FamilyEntry>,
    val labels: Map<String, String> = emptyMap(),
    val synonyms: List<List<String>> = emptyList(),
)

@Serializable
private class FamilyEntry(
    val id: String,
    val hue: Int,
    val tags: List<String>,
)
