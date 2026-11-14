package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.ResolvedArtist

/**
 * Who of [track]'s credited artists composed [work] and who performs the recording resolved for it
 * (#1180, `design/classical.md`, section 2). On the recording: a credited artist with an id from its
 * credit or a performer relationship ([resolved], in the track's order), or with a credit standing for
 * them ([credited]: by name, as a band they lead, …); playing on it: one its performer relationships
 * ([relations]: `vocal`, `instrument`, `performer`, `performing orchestra`, `conductor`) name, by id or
 * by name.
 *
 * A credited artist the work names its composer (by name), or the composer the title leads with
 * ([NormalizedTrack.composer], #1129, since a work's composer relationship may name another, an
 * arrangement's arranger), is a composer unless they play on the recording. For a work of a classical
 * form (its type, or with none its parent's, in [RecordingResolver.COMPOSED_FORMS]; a `Song` of a song
 * cycle) and for the composer the title leads with on a work of no type, only a performer relationship
 * shows that they play: MusicBrainz often credits the composer on a classical recording, its release
 * imported from a streaming service's artists, composer first. For any other work (a `Song`, …) the
 * recording's credit shows it too, so a songwriter who sings their own song is a performer, and so is
 * the band of `Band: Song`. The others are performers, but an artist the
 * title names beside its composer (an arranger in `(Arr. Busoni)`) whom the recording does not have.
 * Null when the work names no composer: the title's lead then decides ([ArtistRoles.of]).
 */
internal fun NameMatcher.rolesOf(
    track: NormalizedTrack,
    resolved: List<ResolvedArtist>,
    credited: (String) -> Boolean,
    relations: List<Credit>,
    work: MbWork,
): ArtistRoles? {
    val composers = work.artistRelations.filter { it.type == COMPOSER }
    if (composers.isEmpty()) return null
    val onRecording =
        track.artists.filterIndexed { i, artist -> resolved.getOrNull(i)?.mbid != null || credited(artist) }.toSet()
    val performing =
        track.artists
            .filterIndexed { i, artist ->
                relations.any { it.mbid == resolved.getOrNull(i)?.mbid || sameName(it.name, artist) }
            }.toSet()
    val type = work.type?.lowercase()
    val parents = work.parents.mapNotNull { it.type?.lowercase() }
    val classical =
        when (type) {
            in RecordingResolver.COMPOSED_FORMS -> true
            SONG -> SONG_CYCLE in parents
            null -> parents.any { it in RecordingResolver.COMPOSED_FORMS }
            else -> false
        }
    val lead = track.composer?.name
    // Only a performer relationship shows that a composer plays: for a classical work, and for the composer
    // the title leads with on a work of no type, which is no sign of a song either (as `composed` reads it).
    val strict = { artist: String -> classical || (type == null && artist == lead) }
    val byWork = track.artists.filter { artist -> composers.any { sameName(it.artistName, artist) } }
    val composed =
        track.artists
            .filter { it in byWork || it == lead }
            .filterNot { if (strict(it)) it in performing else it in onRecording }
            .distinct()
    val titled = track.titleArtists.toSet()
    val performers = track.artists.filter { it !in composed && (it !in titled || it in onRecording) }
    return ArtistRoles(composed, performers)
}

// A work's relationship naming who composed it.
private const val COMPOSER = "composer"

// MusicBrainz's work types, lower case, of a song and of a song cycle (a Lied of *Winterreise*).
private const val SONG = "song"

private const val SONG_CYCLE = "song-cycle"
