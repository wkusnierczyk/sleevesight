package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.text.JS_WHITESPACE_CLASS
import kotlin.time.Duration

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
internal fun NameMatcher.standsFor(
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
 * How a recording found weighs for [RecordingResolver.resolve]'s fallbacks: whether it is [ours] (with a
 * composer the title names, a performance by the track's own artists, [performedBy]), whether it fits as a
 * recording read so in any form, a weaker one included ([fitsAtAll]), and whether its title, length and a
 * credit by name or as a band fit, read as a part of a work included ([matches]).
 */
internal class Weight(
    val ours: Boolean,
    val fitsAtAll: Boolean,
    val matches: Boolean,
)

/**
 * The fallbacks (#1289) on what the searches by name found ([further]), each only when nothing found before
 * it fits in any form, a weaker one included ([weigh]), so a track that resolved before makes none of their
 * requests and keeps its choice: with a composer the title names, the aliases of what was found
 * ([withAliases]), as before #1330; then the loose search ([loosely]); then, when nothing found matches even
 * read as a part of a work (`Nessun doma - Turandot`), the aliases of what was left out (#1330).
 */
internal suspend fun MusicBrainzLookup.fallbacks(
    names: NameMatcher,
    track: NormalizedTrack,
    duration: Duration?,
    further: Searched,
    weigh: (MbRecording) -> Weight,
): Searched {
    val fitsAtAll: (MbRecording) -> Boolean = { weigh(it).let { w -> w.ours && w.fitsAtAll } }
    val matches: (MbRecording) -> Boolean = { weigh(it).matches }
    val aliased =
        if (track.composer == null || further.found.any(fitsAtAll)) {
            further
        } else {
            withAliases(names, track, duration, further, matches)
        }
    val loose = loosely(names, track, duration, aliased, fitsAtAll)
    return if (loose.found.any { weigh(it).let { w -> w.ours && (w.fitsAtAll || w.matches) } }) {
        loose
    } else {
        withAliases(names, track, duration, loose, matches)
    }
}

// Whether a recording the loose search found, or one left out ([Searched.held]), is taken as [track]'s: its
// credits stand for two or more of the track's artists (a joint credit for one of them counting once) and
// for no one else ([performedBy]).
internal fun NameMatcher.taken(
    recording: MbRecording,
    track: NormalizedTrack,
): Boolean =
    represented(recording, track.artists.filter { it.isNotBlank() }.distinct()) >= 2 &&
        performedBy(recording, track)

/**
 * With the player's [duration] and two or more credited artists, when nothing [searched] found [fits]
 * (#1289): one search by the words of the title and every credited artist at once
 * ([MusicBrainzLookup.searchRecordingsLoosely]), of whose recordings only those whose credits stand for
 * two or more of the track's artists (a joint credit for one of them counting once) and for no one else
 * are added. A one-letter misspelling of the title (`Nessun
 * doma`) and a performer credited after the composer and the librettists, beyond the searches by name,
 * are found so; the title is then weighed as for any other recording. Those with the title's numbers left
 * out for crediting someone else are [Searched.held], for [withAliases] (#1330). Incomplete when it fails,
 * so the resolution is asked again later; unchanged when a search before it failed.
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
    val (kept, left) =
        (more as? Lookup.Found)
            ?.value
            .orEmpty()
            .filter { names.sameNumbers(track.searchTitle, it.title) }
            .partition { names.taken(it, track) }
    val found = (searched.found + kept).distinctBy { it.mbid }
    val held = (searched.held + left).distinctBy { it.mbid }.filter { h -> found.none { it.mbid == h.mbid } }
    val aliases = searched.aliases
    return when {
        more is Lookup.Failed -> Searched(searched.answer, searched.found, true, searched.later, searched.held, aliases)
        kept.isEmpty() -> Searched(searched.answer, searched.found, searched.incomplete, searched.later, held, aliases)
        else -> Searched(Lookup.Found(found), found, searched.incomplete, true, held, aliases)
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
internal fun NameMatcher.represented(
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

// At most this many words of the title in the loose search (#1289, `loosely`).
private const val LOOSE_WORDS = 8

// The work before the first colon of a part's title, and the colon with the whitespace after it.
private val PIECE_LEAD = Regex("^([^:]+):$JS_WHITESPACE_CLASS+")

// A dash between a part's title and its work, with whitespace either side: a hyphen, an en dash or an em dash.
private val PIECE_DASH = Regex("$JS_WHITESPACE_CLASS[-–—]$JS_WHITESPACE_CLASS")
