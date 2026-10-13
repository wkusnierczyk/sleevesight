package io.github.wkusnierczyk.sleevesight.core.genres

import io.github.wkusnierczyk.sleevesight.core.ResourceReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fixed genre vocabulary (`design/design.md`, *Genre labels*): the tags that become genre
 * labels, each in a family with a fixed hue. Tags outside it (places, decades, "seen live", noise)
 * are dropped. It is source-agnostic: Last.fm's artist tags go through it, and MusicBrainz's would
 * if D12 adopts them.
 *
 * The data is the resource [RESOURCE], taken from the simulator's `GENRE_FAMILIES`; a test checks
 * the two agree.
 */
class GenreVocabulary(
    val families: List<GenreFamily>,
    private val labelOverrides: Map<String, String> = emptyMap(),
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

    /**
     * The genre labels for [tags], in their order (the source's, most applied first), at most
     * [MAX_LABELS]: each tag lowercased and trimmed, kept if it is in the vocabulary, once. Two tags
     * that differ only in punctuation or spacing (`hip-hop`, `hip hop`) give one label, the first.
     * As the simulator's `genresFrom`, except for that.
     */
    fun labelsFor(tags: List<String>): List<GenreLabel> {
        val seen = mutableSetOf<String>()
        return tags
            .asSequence()
            .map(::key)
            .mapNotNull { tag -> familyOf[tag]?.let { tag to it } }
            .filter { (tag, _) -> seen.add(tag.filter(Char::isLetterOrDigit)) }
            .take(MAX_LABELS)
            .map { (tag, family) -> GenreLabel(tag, family.id, family.hue, labelOverrides[tag] ?: titleCase(tag)) }
            .toList()
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
            return GenreVocabulary(file.families.map { GenreFamily(it.id, it.hue, it.tags) }, file.labels)
        }

        private fun key(tag: String): String = tag.trim().lowercase()

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
 */
data class GenreLabel(
    val tag: String,
    val family: String,
    val hue: Int,
    val label: String,
)

@Serializable
private class VocabularyFile(
    @Suppress("unused") val about: String = "",
    val families: List<FamilyEntry>,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
private class FamilyEntry(
    val id: String,
    val hue: Int,
    val tags: List<String>,
)
