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
 * title names beside its composer (an arranger in `(Arr. Busoni)`) whom the recording does not have, and
 * a credited artist the work names its lyricist or librettist (by name) who does not play on it, shown
 * as for a composer (#1289: Spotify credits *Nessun dorma*'s librettists before Pavarotti). On a
 * classical work the performers the recording has come first, in the track's order, so the lead
 * ([ArtistRoles.lead]) is one who plays on it, not a name the player lists before them.
 * Whether the work is of a classical form is kept with them ([ArtistRoles.classical], #1199), and on
 * such a work the composers it names whom the track does not credit ([ArtistRoles.uncredited], #1298:
 * Spotify credits *Nessun Dorma* to Pavarotti alone), unless a credited composer is one the work does not
 * name by name (a title's lead spelt otherwise), who may be one of them. A composer is credited when a
 * credited artist is them by name, by the id it resolved to, or by an id of the joint credit it was
 * accepted under ([resolved] holds those after the track's own, `Resolution.jointArtists`), or when a
 * credit plausibly names them, its words all theirs or theirs all its ("Puccini", or "Giacomo Puccini &
 * Luciano Pavarotti" unresolved): someone named twice is worse than someone left out.
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
    val wrote = wordsOnly(track, work, if (classical) performing else onRecording)
    // On a classical work, its composers the track does not credit, when every credited one is one it names.
    val uncredited = if (classical) uncredited(track, resolved, composers, composed - byWork.toSet()) else emptyList()
    return ArtistRoles(
        composed,
        performersOf(track, composed.toSet() + wrote, onRecording, classical),
        classical,
        uncredited,
    )
}

// The work's [composers] whom [track] does not credit (#1298), by MusicBrainz's names, once each: none
// of its artists is one by name, nor resolved to their id ([resolved]). MusicBrainz's special-purpose
// artists (`[traditional]`, `[unknown]`) are no one to name. None while a credited composer is one the
// work does not name by name ([unnamed]: a title's lead spelt otherwise), who may be one of them.
private fun NameMatcher.uncredited(
    track: NormalizedTrack,
    resolved: List<ResolvedArtist>,
    composers: List<MbArtistRelation>,
    unnamed: List<String>,
): List<String> {
    if (unnamed.isNotEmpty()) return emptyList()
    val ids = resolved.mapNotNull { it.mbid }.toSet()
    return composers
        .distinctBy { it.artistMbid }
        .filter { relation ->
            !relation.artistName.startsWith("[") &&
                relation.artistMbid !in ids &&
                track.artists.none { sameName(relation.artistName, it) || mayName(it, relation.artistName) }
        }.map { it.artistName }
        .distinct()
}

// Whether [credit] may name [composer] (#1298): folded, its words all among theirs ("Puccini" for
// "Giacomo Puccini"), or theirs all among its (a joint credit, "Giacomo Puccini & Luciano Pavarotti").
private fun NameMatcher.mayName(
    credit: String,
    composer: String,
): Boolean {
    val words = key(credit).split(' ').filter(String::isNotEmpty).toSet()
    val theirs = key(composer).split(' ').filter(String::isNotEmpty).toSet()
    return words.isNotEmpty() && theirs.isNotEmpty() && (theirs.containsAll(words) || words.containsAll(theirs))
}

// [track]'s performers: its credited artists but those who [wrote] the work and an artist the title names
// whom the recording does not have ([onRecording]); on a [classical] work, those it has first (#1289).
private fun performersOf(
    track: NormalizedTrack,
    wrote: Set<String>,
    onRecording: Set<String>,
    classical: Boolean,
): List<String> {
    val titled = track.titleArtists.toSet()
    return track.artists
        .filter { it !in wrote && (it !in titled || it in onRecording) }
        .sortedBy { classical && it !in onRecording }
}

// The credited artists of [track] whom [work] names its lyricist or librettist (#1289), but those of [playing].
private fun NameMatcher.wordsOnly(
    track: NormalizedTrack,
    work: MbWork,
    playing: Set<String>,
): Set<String> {
    val writers = work.artistRelations.filter { it.type in WORDS }
    return track.artists
        .filter { artist ->
            artist !in playing && writers.any { sameName(it.artistName, artist) }
        }.toSet()
}

// A work's relationship naming who composed it.
private const val COMPOSER = "composer"

// A work's relationships naming who wrote its words alone (#1289): a `writer` wrote the music too.
private val WORDS = setOf("lyricist", "librettist")

// MusicBrainz's work types, lower case, of a song and of a song cycle (a Lied of *Winterreise*).
private const val SONG = "song"

private const val SONG_CYCLE = "song-cycle"
