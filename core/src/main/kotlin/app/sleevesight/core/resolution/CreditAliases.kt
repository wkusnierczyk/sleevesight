package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The other names of the artists a recording credits, looked up when they stand for none of the track's
// artists (#1289, #1330): the Berliner Philharmoniker for the player's Berlin Philharmonic.

/**
 * [searched] with the other names of the artists credited on its recordings that stand for none of the
 * track's artists looked up ([lookedUp]), for the recordings that would fit but for them ([matches]: title,
 * length and a performer named, but not every credit one of the track's artists, [performedBy]): the
 * Berliner Philharmoniker for the player's Berlin Philharmonic. The recordings weighed are those left out
 * for crediting someone else ([Searched.held]: by the searches by the later credited artists, #1330, and by
 * the loose search, [loosely]) that have the title's numbers ([admissible]), and, with a composer the title
 * names (#1289), those found, which count only as the track's artists' performance. Every alias known so far
 * applies, an earlier pass's included. A recording left out is added when the aliases make it the track's
 * artists' ([takenAliased]). Incomplete when a lookup fails, so the resolution is asked
 * again later. Not called when something fits already.
 */
internal suspend fun MusicBrainzLookup.withAliases(
    names: NameMatcher,
    track: NormalizedTrack,
    duration: Duration?,
    searched: Searched,
    matches: (MbRecording) -> Boolean,
): Searched {
    if (searched.answer is Lookup.Failed) return searched
    val held = searched.held.filter { names.admissible(it, track) }
    val weighed = if (track.composer != null) searched.found + held else held
    val looked = lookedUp(names, track, duration, weighed.filter(matches), searched.aliases)
    // Every alias known so far applies, those an earlier pass looked up included.
    val (taken, left) = held.map(looked::on).partition { names.takenAliased(it, track) }
    val found = searched.found.map(looked::on) + taken
    return Searched(
        if (found.isEmpty()) searched.answer else Lookup.Found(found),
        found,
        searched.incomplete || looked.failed,
        searched.later || taken.isNotEmpty(),
        left,
        looked.aliases,
    )
}

// The other names looked up for one track's credits: each artist's id, with those of its names that are a
// track artist's (none for an artist looked up that has none); [failed] when a lookup failed.
internal class Aliases(
    val aliases: Map<String, List<String>>,
    val failed: Boolean = false,
) {
    // [recording] with each credit's names that are a track artist's ([MbCredit.aliases]).
    fun on(recording: MbRecording): MbRecording =
        recording.copy(
            credits =
                recording.credits.map { c ->
                    aliases[c.artistMbid]?.takeIf { it.isNotEmpty() }?.let { c.copy(aliases = it) } ?: c
                },
        )
}

// Beside those [known] already, the other names of the artists credited on [recordings] who stand for none
// of [track]'s artists, on those of them that leave a performer of the track unnamed, the closest to
// [duration] first: at most [ALIAS_LOOKUPS] artists for the track in all, [known] counted.
private suspend fun MusicBrainzLookup.lookedUp(
    names: NameMatcher,
    track: NormalizedTrack,
    duration: Duration?,
    recordings: List<MbRecording>,
    known: Map<String, List<String>>,
): Aliases {
    val unknown =
        recordings
            .map(Aliases(known)::on)
            .filterNot { names.performedBy(it, track) }
            .sortedWith(
                compareBy<MbRecording> { r ->
                    val length = r.lengthMs?.milliseconds
                    if (duration == null || length == null) Duration.INFINITE else (length - duration).absoluteValue
                }.thenBy { it.mbid },
            ).filter { r -> track.performers.any { r.credits.none(names.standsFor(r.credits, listOf(it))) } }
            .flatMap { r -> r.credits.filterNot(names.standsFor(r.credits, track.artists)).map { it.artistMbid } }
            .distinct()
            .filterNot { it in known }
            .take((ALIAS_LOOKUPS - known.size).coerceAtLeast(0))
    var failed = false
    val found =
        unknown.associateWith { mbid ->
            when (val answer = artistNames(mbid)) {
                is Lookup.Found -> answer.value.filter { name -> track.artists.any { names.sameName(name, it) } }
                Lookup.NotFound -> emptyList()
                is Lookup.Failed -> emptyList<String>().also { failed = true }
            }
        }
    return Aliases(known + found, failed)
}

// Whether [recording], its credits' aliases on ([MbCredit.aliases]), is taken as [track]'s, a recording found
// otherwise than by the title's exact words ([taken]). When only an alias makes it stand for two of the
// track's artists, it must stand for two of the performers, the composer never counted among them: the one
// the title names ([NormalizedTrack.performers]), else the first credited, as a classical track credits its
// composer first with no lead in its title (#1180). Otherwise a recording of the work by another conductor,
// credited to the composer and the orchestra, would pass for the track's (#1330).
internal fun NameMatcher.takenAliased(
    recording: MbRecording,
    track: NormalizedTrack,
): Boolean {
    val artists = track.artists.filter { it.isNotBlank() }.distinct()
    val plain = recording.copy(credits = recording.credits.map { it.copy(aliases = emptyList()) })
    val performers = if (track.composer != null) track.performers else artists.drop(1)
    return taken(recording, track) &&
        (represented(plain, artists) >= 2 || represented(recording, performers.filter { it.isNotBlank() }) >= 2)
}

// At most this many artists' other names looked up for one track (#1289, `withAliases`).
private const val ALIAS_LOOKUPS = 2
