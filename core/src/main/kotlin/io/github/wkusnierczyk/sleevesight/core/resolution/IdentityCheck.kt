package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist

/**
 * The Wikipedia identity check, step 5 of entity resolution (`design/architecture.md`): an article
 * is accepted only when its Wikidata item confirms it is about this track's song or artist. This
 * is the check that rejects the wrong Reem.
 *
 * A song item ([song]) must be an instance of song, single or musical work ([Wd.SONG_CLASSES]),
 * and then:
 * - a performer (P175) matching a credited artist makes it the track's own song ([Scope.SONG]): by
 *   MusicBrainz artist id (P434) when resolution has one for that artist; by normalized name
 *   otherwise, and then only a performer with no P434, since one MusicBrainz knows is not shown to
 *   be the unresolved artist; a writer (P86, P676) does so only when the item names no performer;
 * - a performer matching the original recording's artist (a cover, step 4) makes it the original
 *   song ([Scope.ORIGINAL_SONG]); the item MusicBrainz links to the recording or its work is the
 *   original song for a cover or when it names performers, none of them the credited artists by
 *   name, else the track's own song;
 * - anything else is rejected, with the reason. A song by someone else found by name alone is
 *   rejected even when its title is the track's: a same-title song and a cover cannot be told
 *   apart without MusicBrainz (Peleboy's "We In Here" is not DMX's), so only MusicBrainz's cover
 *   detection leads to an original song.
 *
 * An artist item ([artist]) is accepted only when its P434 holds the artist's resolved MusicBrainz
 * id: without one, a same-name article cannot be told from the right one.
 *
 * Never throws, apart from cancellation: Wikidata not answering is [Verdict.Unreachable].
 */
class IdentityCheck(
    private val wikidata: WikidataLookup,
    private val names: NameMatcher,
) {
    /** How a candidate item was reached, which decides what may confirm it. */
    enum class Route {
        /** Through MusicBrainz's link to Wikidata, from the resolved recording or work. */
        MUSICBRAINZ,

        /** From an article found by name, through its own Wikidata item. */
        NAME,
    }

    /** The check's answer for one item. */
    sealed interface Verdict {
        /**
         * The item is about the track's song ([Scope.SONG]), its original ([Scope.ORIGINAL_SONG]) or
         * an artist ([Scope.ARTIST]).
         */
        data class Accepted(
            val item: WdEntity,
            val scope: Scope,
            val labels: Map<String, String>,
        ) : Verdict

        data class Rejected(
            val item: String,
            val reason: String,
        ) : Verdict

        data object Unreachable : Verdict
    }

    /** The Wikidata items MusicBrainz links to the recording or its work: its URL links, else the work's P435. */
    suspend fun musicBrainzItems(resolution: Resolution): Lookup<List<String>> {
        val work = resolution.work
        return when {
            resolution.wikidataIds.isNotEmpty() -> Lookup.Found(resolution.wikidataIds)
            work == null -> Lookup.Found(emptyList())
            else -> wikidata.itemsWithStatement(Wd.MUSICBRAINZ_WORK, work.mbid)
        }
    }

    /** The item whose MusicBrainz artist id (P434) is [mbid], if one. */
    suspend fun artistItem(mbid: String): Lookup<String?> =
        when (val found = wikidata.itemsWithStatement(Wd.MUSICBRAINZ_ARTIST, mbid)) {
            is Lookup.Found -> Lookup.Found(found.value.firstOrNull())
            is Lookup.NotFound -> Lookup.Found(null)
            is Lookup.Failed -> found
        }

    /** Checks song item [id], reached by [route], for [track], given what MusicBrainz resolved. */
    suspend fun song(
        id: String,
        track: NormalizedTrack,
        resolution: Resolution,
        route: Route,
    ): Verdict =
        answered {
            val entity = entity(id)
            if (entity == null) {
                Verdict.Rejected(id, "$id is not a Wikidata item")
            } else {
                judgeSong(entity, SongContext(track, resolution, route))
            }
        }

    /** Checks artist item [id] against [artist], as resolution left it. */
    suspend fun artist(
        id: String,
        artist: ResolvedArtist,
    ): Verdict =
        answered {
            val entity = entity(id)
            val name = entity?.label ?: id
            val disambiguation = entity != null && Wd.DISAMBIGUATION in entity.values(Wd.INSTANCE_OF)
            val mbid = artist.mbid
            when {
                entity == null -> Verdict.Rejected(id, "$id is not a Wikidata item")
                disambiguation -> Verdict.Rejected(id, "“$name” is a disambiguation page")
                mbid == null -> Verdict.Rejected(id, "no MusicBrainz id for ${artist.name} to confirm “$name”")
                mbid in
                    claims(
                        id,
                        Wd.MUSICBRAINZ_ARTIST,
                    )
                -> Verdict.Accepted(entity, Scope.ARTIST, labels(evidenceIds(entity)))
                else -> Verdict.Rejected(id, "“$name” is not the MusicBrainz artist ${artist.name}")
            }
        }

    private class SongContext(
        val track: NormalizedTrack,
        val resolution: Resolution,
        val route: Route,
    ) {
        // The track's artists with the ids resolution found; by name alone when it found none.
        val credited: List<ResolvedArtist> =
            resolution.artists.ifEmpty { track.artists.map { ResolvedArtist(it, null) } }
    }

    private suspend fun judgeSong(
        entity: WdEntity,
        context: SongContext,
    ): Verdict {
        val name = entity.label ?: entity.id
        val classes = entity.values(Wd.INSTANCE_OF)
        val performers = entity.values(Wd.PERFORMER)
        val writers = entity.values(Wd.COMPOSER) + entity.values(Wd.LYRICIST)
        val labels = labels((performers + writers + evidenceIds(entity)).distinct())
        val original =
            context.resolution.original
                ?.artists
                .orEmpty()
        val scope =
            when {
                !songLike(classes) -> null
                matches(performers, context.credited, labels, anonymousOnly = true) -> Scope.SONG
                // A writer confirms only a song no one is named performing (an anthem): with
                // performers, the item's facts are theirs (a songwriter's song made famous by others).
                performers.isEmpty() && matches(writers, context.credited, labels) -> Scope.SONG
                original.isNotEmpty() && matches(performers, original, labels) -> Scope.ORIGINAL_SONG
                // MusicBrainz ties this item to the recording, so a name match carries no same-name
                // risk here: the original song for a cover, or when it names performers none of whom
                // is a credited artist by name (MusicBrainz rarely dates enough to infer a cover);
                // otherwise the track's own song.
                context.route == Route.MUSICBRAINZ -> {
                    val byName = context.credited.map { ResolvedArtist(it.name, null) }
                    val others = performers.isNotEmpty() && !matches(performers, byName, labels)
                    if (context.resolution.cover || others) Scope.ORIGINAL_SONG else Scope.SONG
                }
                else -> null
            }
        return if (scope != null) {
            Verdict.Accepted(entity, scope, labels)
        } else {
            Verdict.Rejected(entity.id, songRejection(name, classes, performers, labels, context))
        }
    }

    // Whether any of [people] is one of [artists]: by MusicBrainz id when the artist has one, by name otherwise.
    // With [anonymousOnly], an artist without an id matches by name only a person MusicBrainz does
    // not know (no P434): one it knows is a resolved artist the track's, unresolved, is not shown to be.
    private suspend fun matches(
        people: List<String>,
        artists: List<ResolvedArtist>,
        labels: Map<String, String>,
        anonymousOnly: Boolean = false,
    ): Boolean =
        artists.any { artist ->
            val mbid = artist.mbid
            people.any { person ->
                if (mbid != null) {
                    mbid in claims(person, Wd.MUSICBRAINZ_ARTIST)
                } else {
                    val named = labels[person]?.let { names.sameName(it, artist.name) } ?: false
                    named && !(anonymousOnly && claims(person, Wd.MUSICBRAINZ_ARTIST).isNotEmpty())
                }
            }
        }

    private suspend fun entity(id: String): WdEntity? =
        when (val found = wikidata.entities(listOf(id))) {
            is Lookup.Found -> found.value[id]
            is Lookup.NotFound -> null
            is Lookup.Failed -> throw Unanswered()
        }

    private suspend fun labels(ids: List<String>): Map<String, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            when (val found = wikidata.labels(ids)) {
                is Lookup.Found -> found.value
                is Lookup.NotFound -> emptyMap()
                is Lookup.Failed -> throw Unanswered()
            }
        }

    private suspend fun claims(
        id: String,
        property: String,
    ): List<String> =
        when (val found = wikidata.claimValues(id, property)) {
            is Lookup.Found -> found.value
            is Lookup.NotFound -> emptyList()
            is Lookup.Failed -> throw Unanswered()
        }

    private suspend fun answered(check: suspend () -> Verdict): Verdict =
        try {
            check()
        } catch (_: Unanswered) {
            Verdict.Unreachable
        }

    // Wikidata did not answer a lookup the check needs.
    private class Unanswered : Exception()

    private companion object {
        fun songLike(classes: List<String>): Boolean =
            Wd.DISAMBIGUATION !in classes && classes.any { it in Wd.SONG_CLASSES }

        private fun songRejection(
            name: String,
            classes: List<String>,
            performers: List<String>,
            labels: Map<String, String>,
            context: SongContext,
        ): String {
            val by = performers.mapNotNull { labels[it] }
            val credited = EvidenceText.list(context.credited.map { it.name })
            return when {
                Wd.DISAMBIGUATION in classes -> "“$name” is a disambiguation page"
                classes.none { it in Wd.SONG_CLASSES } -> "“$name” is not a song, single or musical work"
                by.isEmpty() -> "“$name” names no performer or writer credited on the track"
                else -> "“$name” is by ${EvidenceText.list(by)}, not by $credited"
            }
        }

        // The items the evidence names: genres, labels, awards.
        fun evidenceIds(entity: WdEntity): List<String> =
            WikidataEvidence.NAMED_PROPERTIES.flatMap { entity.values(it) }
    }
}
