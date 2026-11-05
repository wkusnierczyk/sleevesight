package app.sleevesight.sources.language

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.language.Language
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.IdentityCheck.Route
import app.sleevesight.core.resolution.IdentityCheck.Verdict
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.LocalName
import app.sleevesight.core.sources.Resolution

/**
 * The search in the track's language over Wikidata, MusicBrainz and the language's Wikipedia (#902,
 * [LanguageSearch]). Wikidata is asked by identifiers only, as for the English lookup: the items
 * MusicBrainz links to the recording or its work ([IdentityCheck.musicBrainzItems]), the works they
 * name as what they come from ([Wd.COMES_FROM]), and the artists' items by their MusicBrainz ids.
 * [web] is the web search in the language, or none.
 */
class LanguageSources(
    private val wikidata: WikidataLookup,
    private val check: IdentityCheck,
    private val musicBrainz: MusicBrainzLookup,
    override val wikipedia: LanguageAdapter?,
    override val web: LanguageAdapter?,
) : LanguageSearch {
    override suspend fun workLanguages(resolution: Resolution): Lookup<List<String>> {
        val linked = check.musicBrainzItems(resolution)
        val ids = (linked as? Lookup.Found)?.value.orEmpty()
        val songs = if (ids.isEmpty()) emptyMap() else entities(ids)
        val comes = ids.flatMap { comesFrom(songs?.get(it)) }.distinct()
        val works = if (comes.isEmpty()) emptyMap() else entities(comes)
        if (linked is Lookup.Failed || songs == null || works == null) return Lookup.Failed(UNANSWERED)
        val languages =
            ids.flatMap { songs[it]?.values(Wd.LANGUAGE).orEmpty() } +
                comes.flatMap { id ->
                    works[id]?.let { it.values(Wd.LANGUAGE) + it.values(Wd.ORIGINAL_LANGUAGE) }.orEmpty()
                }
        return Lookup.Found(languages.distinct())
    }

    override suspend fun releaseLanguages(resolution: Resolution): Lookup<List<String>> {
        val mbid = resolution.recordingMbid ?: return Lookup.Found(emptyList())
        return when (val found = musicBrainz.recordingReleases(mbid)) {
            is Lookup.Found -> {
                val languages = found.value.releases.mapNotNull { it.language }
                // Most releases first; a tie in the order MusicBrainz lists them.
                Lookup.Found(languages.distinct().sortedByDescending { code -> languages.count { it == code } })
            }
            is Lookup.NotFound -> Lookup.Found(emptyList())
            is Lookup.Failed -> found
        }
    }

    override suspend fun items(query: LanguageQuery): LanguageItems {
        val language = query.language
        val song = song(query)
        val artists = artists(query)
        val songId = song.value?.item?.id
        val songs = songId?.let { entities(listOf(it), language) }
        val comes = comesFrom(songId?.let { songs?.get(it) })
        val others = (comes + artists.value.values).distinct()
        val named = if (others.isEmpty()) emptyMap() else entities(others, language)
        return LanguageItems(
            song = songId?.let { local(it, songs?.get(it), language) },
            songScope = song.value?.scope ?: Scope.SONG,
            comes = comes.map { local(it, named?.get(it), language) },
            artists = artists.value.mapValues { (_, item) -> local(item, named?.get(item), language) },
            unreachable = song.unreachable || artists.unreachable || (songId != null && songs == null) || named == null,
        )
    }

    /** What a check found, and whether a lookup it needed failed. */
    private class Checked<T>(
        val value: T,
        val unreachable: Boolean,
    )

    // The first item MusicBrainz links that the identity check confirms as the song or its original.
    private suspend fun song(query: LanguageQuery): Checked<Verdict.Accepted?> {
        val linked = check.musicBrainzItems(query.resolution)
        var unreachable = linked is Lookup.Failed
        var song: Verdict.Accepted? = null
        val ids = (linked as? Lookup.Found)?.value.orEmpty().iterator()
        while (song == null && ids.hasNext()) {
            when (val verdict = check.song(ids.next(), query.track, query.resolution, Route.MUSICBRAINZ)) {
                is Verdict.Accepted -> song = verdict
                is Verdict.Unreachable -> unreachable = true
                is Verdict.Rejected -> Unit
            }
        }
        return Checked(song, unreachable)
    }

    // Each resolved artist's item the identity check confirms, by the name the track credits.
    private suspend fun artists(query: LanguageQuery): Checked<Map<String, String>> {
        val artists = mutableMapOf<String, String>()
        var unreachable = false
        for (artist in query.resolution.artists) {
            val found = artist.mbid?.let { check.artistItem(it) }
            if (found is Lookup.Failed) unreachable = true
            val item = (found as? Lookup.Found)?.value
            val verdict = item?.let { check.artist(it, artist) }
            if (verdict is Verdict.Accepted && item != null) artists[artist.name] = item
            if (verdict is Verdict.Unreachable) unreachable = true
        }
        return Checked(artists, unreachable)
    }

    private suspend fun entities(ids: List<String>): Map<String, WdEntity>? =
        when (val found = wikidata.entities(ids)) {
            is Lookup.Found -> found.value
            is Lookup.NotFound -> emptyMap()
            is Lookup.Failed -> null
        }

    private suspend fun entities(
        ids: List<String>,
        language: Language,
    ): Map<String, WdEntity>? =
        when (val found = wikidata.entities(ids, language)) {
            is Lookup.Found -> found.value
            is Lookup.NotFound -> emptyMap()
            is Lookup.Failed -> null
        }

    private companion object {
        const val UNANSWERED = "Wikidata"

        // The works [entity] says it comes from: the film it was made for, what it is part of, where published.
        fun comesFrom(entity: WdEntity?): List<String> =
            entity
                ?.let { e ->
                    Wd.COMES_FROM.flatMap { e.values(it) }
                }.orEmpty()
                .filter { it.startsWith("Q") }
                .distinct()

        fun local(
            item: String,
            entity: WdEntity?,
            language: Language,
        ): LocalName = LocalName(item, entity?.labels?.get(language.label), entity?.sitelinks?.get(language.sitelink))
    }
}
