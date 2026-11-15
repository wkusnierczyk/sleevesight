package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.text.JS_WHITESPACE_CLASS
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// What `RecordingResolver` falls back on when the searches by the title as written find nothing that fits
// (#1289), and how a recording's credit is weighed against the track's artists (#1129).

// Whether [recording] is a performance by [track]'s own artists (#1129): every credit stands for one
// of them, by name, as a band one of them leads, under an honorific variant or in a joint credit, and
// one for a performer ([NormalizedTrack.performers]).
internal fun NameMatcher.performedBy(
    recording: MbRecording,
    track: NormalizedTrack,
): Boolean {
    val credits = recording.credits
    return credits.isNotEmpty() &&
        credits.all(standsFor(credits, track.artists)) &&
        credits.any(standsFor(credits, track.performers))
}

// Whether a credit of [credits] stands for one of [artists]: by any name it goes by ([MbCredit.names], an
// alias looked up among them, #1289), as a band one of them leads, under an honorific variant, or in a
// joint credit ([performedBy]).
private fun NameMatcher.standsFor(
    credits: List<MbCredit>,
    artists: List<String>,
): (MbCredit) -> Boolean {
    val joint = artists.flatMap { jointCredits(credits, it) }.flatten().toSet()
    return { credit ->
        credit in joint ||
            artists.any { artist ->
                credit.names.any { sameName(it, artist) || leads(it, artist) || honorificVariant(it, artist) }
            }
    }
}

/**
 * With a composer the title names (#1289), [searched] with the other names of the artists credited on its
 * recordings that stand for none of the track's artists looked up, for the recordings that would fit but
 * for them ([matches]: title, length and a performer named, but not every credit one of the track's
 * artists, [performedBy]), the closest to the player's [duration] first, at most [ALIAS_LOOKUPS] artists,
 * and only where a performer is left whom the recording does not name: the Berliner Philharmoniker for
 * the player's Berlin Philharmonic. A name of theirs that is a track artist's goes on their credits
 * ([MbCredit.aliases]), which then stand for that artist. Incomplete when a lookup fails, so the
 * resolution is asked again later. Not called when something fits already.
 */
internal suspend fun MusicBrainzLookup.withAliases(
    names: NameMatcher,
    track: NormalizedTrack,
    duration: Duration?,
    searched: Searched,
    matches: (MbRecording) -> Boolean,
): Searched {
    val unknown =
        if (track.composer == null || searched.answer !is Lookup.Found) {
            emptyList()
        } else {
            names.unknownArtists(
                track,
                duration,
                searched.found.filter { matches(it) && !names.performedBy(it, track) },
            )
        }
    if (unknown.isEmpty()) return searched
    var failed = false
    val aliases =
        unknown
            .associateWith { mbid ->
                when (val known = artistNames(mbid)) {
                    is Lookup.Found -> known.value.filter { name -> track.artists.any { names.sameName(name, it) } }
                    Lookup.NotFound -> emptyList()
                    is Lookup.Failed -> emptyList<String>().also { failed = true }
                }
            }.filterValues { it.isNotEmpty() }
    val found =
        searched.found.map { recording ->
            recording.copy(
                credits =
                    recording.credits.map { c ->
                        aliases[c.artistMbid]?.let { c.copy(aliases = it) }
                            ?: c
                    },
            )
        }
    return Searched(Lookup.Found(found), found, searched.incomplete || failed, searched.later)
}

// The ids of the artists credited on [recordings] who stand for none of [track]'s artists, on those of them
// that leave a performer of the track unnamed, the closest to [duration] first: at most [ALIAS_LOOKUPS].
private fun NameMatcher.unknownArtists(
    track: NormalizedTrack,
    duration: Duration?,
    recordings: List<MbRecording>,
): List<String> =
    recordings
        .sortedBy { r ->
            val length = r.lengthMs?.milliseconds
            if (duration == null || length == null) Duration.INFINITE else (length - duration).absoluteValue
        }.filter { r -> track.performers.any { artist -> r.credits.none(standsFor(r.credits, listOf(artist))) } }
        .flatMap { r -> r.credits.filterNot(standsFor(r.credits, track.artists)).map { it.artistMbid } }
        .distinct()
        .take(ALIAS_LOOKUPS)

/**
 * With the player's [duration] and two or more credited artists, when nothing [searched] found [fits]
 * (#1289): one search by the words of the title and every credited artist at once
 * ([MusicBrainzLookup.searchRecordingsLoosely]), of whose recordings only those whose credits stand for
 * two or more of the track's artists (a joint credit for one of them counting once) and for no one else
 * are added. A one-letter misspelling of the title (`Nessun
 * doma`) and a performer credited after the composer and the librettists, beyond the searches by name,
 * are found so; the title is then weighed as for any other recording. Incomplete when it fails, so the
 * resolution is asked again later; unchanged when a search before it failed.
 */
internal suspend fun MusicBrainzLookup.loosely(
    names: NameMatcher,
    track: NormalizedTrack,
    duration: Duration?,
    searched: Searched,
    fits: (MbRecording) -> Boolean,
): Searched {
    val artists = track.artists.filter { it.isNotBlank() }.distinct()
    val words =
        names
            .key(track.searchTitle)
            .split(' ')
            .filter { it.isNotEmpty() }
            .take(LOOSE_WORDS)
    val asked =
        searched.answer !is Lookup.Failed &&
            duration != null &&
            artists.size >= 2 &&
            words.isNotEmpty() &&
            searched.found.none(fits)
    val more = if (asked) searchRecordingsLoosely(words, artists) else null
    val kept =
        (more as? Lookup.Found)?.value.orEmpty().filter { r ->
            names.represented(r, artists) >= 2 &&
                names.performedBy(r, track) &&
                names.sameNumbers(track.searchTitle, r.title)
        }
    val found = (searched.found + kept).distinctBy { it.mbid }
    return when {
        more is Lookup.Failed -> Searched(searched.answer, searched.found, incomplete = true, later = searched.later)
        kept.isEmpty() -> searched
        else -> Searched(Lookup.Found(found), found, searched.incomplete, later = true)
    }
}

// Whether [title] and [recordingTitle] have the same numbers ([NameMatcher.numberWords]: digits, Roman
// numerals, single letters, ordinals, numbers in words), in the same order (`Op. 1 No. 2` is not `Op. 2
// No. 1`), the recording's title read whole or as the part of a work it
// names (#1289): a title found loosely may differ from the track's by little else (`Gymnopédie No. 1`
// and `No. 2`, `Part 1` and `Part 2`), which its similarity alone would let pass. So may a work's title
// a work search finds (#1299, `searchedWork`).
internal fun NameMatcher.sameNumbers(
    title: String,
    recordingTitle: String,
): Boolean {
    val wanted = numberWords(title)
    return listOfNotNull(recordingTitle, quotedPart(recordingTitle)).any { numberWords(it) == wanted }
}

// How many of [artists] the credits of [recording] stand for ([standsFor]): a joint credit standing for one
// artist ("Ann Lee & Bo Day") counts once, however many MusicBrainz artists it credits (#1289).
private fun NameMatcher.represented(
    recording: MbRecording,
    artists: List<String>,
): Int = artists.count { artist -> recording.credits.any(standsFor(recording.credits, listOf(artist))) }

/**
 * MusicBrainz's title of a part of a classical work (#1289): the work, a colon, then, after any act or
 * scene, the part's own title in quotes (`Turandot: Atto III. “Nessun dorma!”`, `Turandot: “Nessun
 * dorma” (Vincerò)`). How alike [title] is to that quoted title: as it is, or without the work, before
 * it after a colon (`Turandot: Nessun dorma`) or after it behind a dash (`Nessun doma - Turandot`), the
 * closest of those. Null for a recording's title of another shape.
 */
internal fun NameMatcher.pieceSimilarity(
    title: String,
    recordingTitle: String,
): Double? {
    val lead = PIECE_LEAD.find(recordingTitle)
    val quoted = quotedPart(recordingTitle)
    return if (lead == null || quoted == null) {
        null
    } else {
        val work = key(lead.groupValues[1])
        val dash = PIECE_DASH.findAll(title).lastOrNull()?.takeIf { key(title.substring(it.range.last + 1)) == work }
        val colon = PIECE_LEAD.find(title)?.takeIf { key(it.groupValues[1]) == work }
        listOfNotNull(
            title,
            dash?.let { title.substring(0, it.range.first) },
            colon?.let {
                title.substring(
                    it.range.last + 1,
                )
            },
        ).filter { it.isNotBlank() }
            .maxOf { titleSimilarity(it, quoted) }
    }
}

// The part's own title in [recordingTitle] of a part of a work's shape ([pieceSimilarity]): the one quoted
// title after the work, with nothing after it but a parenthetical (`Turandot: “Nessun dorma” (Vincerò)`);
// null for another shape, for a title quoting two (`Medley: “Yesterday” / “Michelle”`), and for a title
// longer than [PIECE_TITLE_MAX] (#1304).
private fun quotedPart(recordingTitle: String): String? {
    val lead = if (recordingTitle.length <= PIECE_TITLE_MAX) PIECE_LEAD.find(recordingTitle) else null
    val rest = lead?.let { recordingTitle.substring(it.range.last + 1) } ?: return null
    return firstQuoted(rest)?.let { quoted ->
        rest.substring(quoted.first + 1, quoted.last).takeIf {
            it.isNotBlank() && fitsPieceTail(rest.substring(quoted.last + 1))
        }
    }
}

// The longest recording title read as a part of a work (#1304): far longer than any work, act and part
// together, short enough that reading a hostile one costs nothing to speak of.
private const val PIECE_TITLE_MAX = 300

// At most this many artists' other names looked up for one track (#1289, `withAliases`).
private const val ALIAS_LOOKUPS = 2

// At most this many words of the title in the loose search (#1289, `loosely`).
private const val LOOSE_WORDS = 8

// The work before the first colon of a part's title, and the colon with the whitespace after it.
private val PIECE_LEAD = Regex("^([^:]+):$JS_WHITESPACE_CLASS+")

// A dash between a part's title and its work, with whitespace either side: a hyphen, an en dash or an em dash.
private val PIECE_DASH = Regex("$JS_WHITESPACE_CLASS[-–—]$JS_WHITESPACE_CLASS")
