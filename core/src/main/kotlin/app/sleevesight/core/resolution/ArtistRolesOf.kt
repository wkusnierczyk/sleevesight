package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.sources.ArtistRoles

/**
 * Who of [track]'s credited artists composed [work] and who performs the recording resolved for it
 * (#1180, `design/classical.md`, section 2), [onRecording] the credited artists the recording has (an id
 * from its credit or a performer relationship, or a credit standing for them). A credited artist the
 * work names its composer (by name) and not on the recording is a composer; so is the composer the title
 * leads with ([NormalizedTrack.composer], #1129) unless the recording has them, since a work's composer
 * relationship may name another (an arrangement's arranger). A songwriter who sings their own song is on
 * the recording, so a performer. The others are performers, but an artist the title names beside its
 * composer (an arranger in `(Arr. Busoni)`) whom the recording does not have. Null when the work names no
 * composer: the title's lead then decides ([ArtistRoles.of]).
 */
internal fun NameMatcher.rolesOf(
    track: NormalizedTrack,
    onRecording: Set<String>,
    work: MbWork,
): ArtistRoles? {
    val composers = work.artistRelations.filter { it.type == COMPOSER }
    if (composers.isEmpty()) return null
    val byWork = track.artists.filter { artist -> composers.any { sameName(it.artistName, artist) } }
    val composed =
        track.artists
            .filter { it !in onRecording && (it in byWork || it == track.composer?.name) }
            .distinct()
    val titled = track.titleArtists.toSet()
    val performers = track.artists.filter { it !in composed && (it !in titled || it in onRecording) }
    return ArtistRoles(composed, performers)
}

// A work's relationship naming who composed it.
private const val COMPOSER = "composer"
