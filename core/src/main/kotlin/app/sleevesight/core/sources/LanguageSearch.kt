package app.sleevesight.core.sources

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.language.Language
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup

/**
 * The search in the track's language (#902; `design/architecture.md`, *Search in the track's
 * language*): the hints Wikidata and MusicBrainz record of a track's language, and the lookups made in
 * it, beside the English ones. `:core` decides whether and in which language (`TrackLanguage`,
 * `TrackRun`), `:sources` implements it over `:net`. [wikipedia] is that language's Wikipedia, with
 * Wikidata's labels and sitelinks in it; [web], a web search in it, or none without an engine and its
 * key. Each obeys its own source's switch, which the pipeline reads.
 */
interface LanguageSearch {
    /**
     * Wikidata's language items (`Q1568`) of the track's song, as resolved: P407 on the items
     * MusicBrainz links to the recording or its work, then P407 and P364 on the work each comes from
     * (the film it was made for, the album it is part of). Asked by identifiers only.
     */
    suspend fun workLanguages(resolution: Resolution): Lookup<List<String>>

    /**
     * The languages (ISO 639-3) MusicBrainz records for the tracklists of the resolved recording's
     * releases, most releases first; none when no recording was accepted.
     */
    suspend fun releaseLanguages(resolution: Resolution): Lookup<List<String>>

    /**
     * What Wikidata names the track's song, the works it comes from and its artists by in
     * [query]'s language, with their articles on its Wikipedia, as [LanguageItems] holds them.
     */
    suspend fun items(query: LanguageQuery): LanguageItems

    val wikipedia: LanguageAdapter?
    val web: LanguageAdapter?
}

/** What a lookup in a language is about: the [track], as [resolution] resolved it, and the [language]. */
class LanguageQuery(
    val track: NormalizedTrack,
    val resolution: Resolution,
    val language: Language,
)

/**
 * One lookup in a language (#902): as a [SourceAdapter], with the [LanguageItems] found for the
 * query, so the two lookups share one Wikidata request. Its result's lines carry the language
 * (`EvidenceDraft.language`) and are kept in the retrieval cache under a key with it.
 */
interface LanguageAdapter {
    val id: SourceId

    /** As [SourceAdapter.extractionVersion]. */
    val extractionVersion: Int get() = 1

    /** As [SourceAdapter.consults]. */
    val consults: Set<SourceId> get() = setOf(id)

    suspend fun lookup(
        query: LanguageQuery,
        items: LanguageItems,
    ): SourceResult
}

/**
 * An item in a language: its Wikidata [item], its [label] in the language and the title of its
 * [article] on the language's Wikipedia, each null when it has none.
 */
data class LocalName(
    val item: String?,
    val label: String?,
    val article: String?,
)

/**
 * What the track's song, the works it comes from and its artists are named in a language, from
 * Wikidata (#902): the [song] item the identity check confirmed, with its [songScope] (the track's
 * own song, or the original of a cover); the works it [comes] from (a film, an album); and each
 * credited artist the check confirmed, by the name the track credits ([artists]). [unreachable] when
 * Wikidata did not answer a lookup it needed. [NONE] with nothing found.
 */
data class LanguageItems(
    val song: LocalName? = null,
    val songScope: Scope = Scope.SONG,
    val comes: List<LocalName> = emptyList(),
    val artists: Map<String, LocalName> = emptyMap(),
    val unreachable: Boolean = false,
) {
    companion object {
        val NONE: LanguageItems = LanguageItems()
    }
}
