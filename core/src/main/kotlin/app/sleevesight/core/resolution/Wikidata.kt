package app.sleevesight.core.resolution

import app.sleevesight.core.language.Language

/**
 * What the identity check and the Wikidata evidence ask Wikidata (`design/architecture.md`,
 * *Entity resolution*, step 5): `:core` decides over this interface, `:sources` implements it over
 * `:net` (`WikidataClient`). Wikidata is CC0.
 */
interface WikidataLookup {
    /** The items [ids] with their claims, English label and English Wikipedia sitelink; missing ids are absent. */
    suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>>

    /**
     * The items [ids] as [entities] reads them, with their labels and Wikipedia sitelinks in English and
     * in [language] too ([WdEntity.labels], [WdEntity.sitelinks]), for the search in the track's
     * language (#902). By default [entities]'s, with neither.
     */
    suspend fun entities(
        ids: List<String>,
        language: Language,
    ): Lookup<Map<String, WdEntity>> = entities(ids)

    /** The English labels of [ids]; an id with no item or no English label is absent. */
    suspend fun labels(ids: List<String>): Lookup<Map<String, String>>

    /** The values of [property] on item [id], such as its MusicBrainz artist ids (P434). */
    suspend fun claimValues(
        id: String,
        property: String,
    ): Lookup<List<String>>

    /** The items with the statement [property] = [value], such as the item whose P435 is a MusicBrainz work id. */
    suspend fun itemsWithStatement(
        property: String,
        value: String,
    ): Lookup<List<String>>
}

/**
 * A Wikidata item, reduced to what the check and the evidence read.
 *
 * @property claims each property's values, in Wikidata's order, deprecated ones left out: an item's
 *   id (`Q7366`), a string as it is, a time as Wikidata writes it (`+1974-03-25T00:00:00Z`).
 * @property enwiki the title of its English Wikipedia article, if any.
 * @property labels its labels by language (`en`, `hi`), as asked for ([WikidataLookup.entities] with a
 *   language, #902); empty otherwise.
 * @property sitelinks the titles of its Wikipedia articles by site (`enwiki`, `hiwiki`), as asked for;
 *   empty otherwise.
 */
data class WdEntity(
    val id: String,
    val label: String?,
    val enwiki: String?,
    val claims: Map<String, List<String>>,
    val labels: Map<String, String> = emptyMap(),
    val sitelinks: Map<String, String> = emptyMap(),
) {
    /** The values of [property], or none. */
    fun values(property: String): List<String> = claims[property].orEmpty()
}

/** The Wikidata properties and classes the check and the evidence use. */
object Wd {
    const val INSTANCE_OF: String = "P31"
    const val PERFORMER: String = "P175"
    const val COMPOSER: String = "P86"
    const val LYRICIST: String = "P676"
    const val GENRE: String = "P136"
    const val RECORD_LABEL: String = "P264"
    const val AWARD_RECEIVED: String = "P166"
    const val INCEPTION: String = "P571"
    const val PUBLICATION_DATE: String = "P577"
    const val MUSICBRAINZ_ARTIST: String = "P434"
    const val MUSICBRAINZ_WORK: String = "P435"

    /** Language of work or name, and the original language of a film or show (#902). */
    const val LANGUAGE: String = "P407"
    const val ORIGINAL_LANGUAGE: String = "P364"

    /**
     * What a song comes from (#902): the work it was made for (a film's song), the work it is part
     * of (an album, a soundtrack), and where it was published.
     */
    val COMES_FROM: List<String> = listOf("P9899", "P361", "P1433")

    /**
     * Song, single, musical work/composition, and the older musical composition and musical work
     * classes some items still use. An item is a song for the check if it is an instance of one,
     * directly: subclasses are not followed, so a new kind of song item is added here.
     */
    val SONG_CLASSES: Set<String> = setOf("Q7366", "Q134556", "Q105543609", "Q207628", "Q2188189")

    /** Wikimedia disambiguation page. */
    const val DISAMBIGUATION: String = "Q4167410"

    /** The item's page on Wikidata. */
    fun url(id: String): String = "https://www.wikidata.org/wiki/$id"
}
