package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.text.isJsWhitespace

// The work of a recording that links none, nor is lent one, found by a search of its composer's works (#1299).

/**
 * The work of a recording that links none, nor is lent one (#1299): with a composer the title leads with
 * ([NormalizedTrack.composer]), one search of MusicBrainz's works by the title without the lead
 * ([NormalizedTrack.searchTitle]) and the composer's name ([MusicBrainzLookup.searchWorks]). A work found
 * is a candidate only when:
 * - its composer relationship names that composer;
 * - it is no arrangement ([arranged]): no arranger or orchestrator but the composer among its artist
 *   relationships, and no disambiguation marking it a catch-all for arrangements. MusicBrainz keeps a work
 *   for each arrangement of a much-arranged piece (Barber's *Adagio for Strings* for organ, for clarinet
 *   choir, and a catch-all for the rest), of the same title and composer;
 * - its title fits the track's ([titleFits]): segment by segment, or as the part it quotes.
 *
 * Only a single candidate is taken: with two or more, nothing tells which is the track's, and none is. It
 * is linked as this recording's own performance of it (no cover, not instrumental), and looked up as any
 * work. Without a composer, none, no request made; a search that fails, none with the failure, so the
 * resolution is asked again later.
 */
internal suspend fun MusicBrainzLookup.searchedWork(
    names: NameMatcher,
    track: NormalizedTrack,
): Pair<MbWorkLink?, Lookup<MbWork>?> {
    val composer = track.composer?.name ?: return null to null
    val title = track.searchTitle
    return when (val found = searchWorks(title, composer)) {
        is Lookup.Failed -> null to found
        Lookup.NotFound -> null to null
        is Lookup.Found ->
            found.value
                .filter { work ->
                    work.artistRelations.any { it.type == COMPOSER && names.sameName(it.artistName, composer) } &&
                        !names.arranged(work, composer) &&
                        names.titleFits(title, work.title)
                }.singleOrNull()
                ?.let { MbWorkLink(it.mbid, it.title, cover = false, type = it.type) to work(it.mbid) }
                ?: (null to null)
    }
}

// Whether [work] is an arrangement ([searchedWork]): an arranger or an orchestrator other than [composer]
// among its artist relationships, or a disambiguation marking it a catch-all for arrangements (`catch‐all
// for arrangements`). No other words of a disambiguation count: `original orchestration` and `original
// version, not an arrangement` name arrangements and are none.
private fun NameMatcher.arranged(
    work: MbFoundWork,
    composer: String,
): Boolean {
    val words = key(work.disambiguation.orEmpty()).split(' ')
    return CATCH_ALL in words.zipWithNext() ||
        CATCH_ALL_JOINED in words ||
        work.artistRelations.any { it.type in ARRANGER_TYPES && !sameName(it.artistName, composer) }
}

// Whether a work's title [workTitle] fits [title] ([searchedWork]), either way:
// - Segment by segment ([segments]): as many, each fitting its own as a recording's title does
//   ([NameMatcher.titleSimilarity] at the threshold) with the same numbers ([NameMatcher.numberWords]).
//   Not the titles joined: a whole work and its unnumbered movement (`Variations on an Original Theme, op.
//   36: “Enigma”` and the same with `: Nimrod`) are as alike as titles that fit, with the same numbers.
// - As the part a part of a work quotes ([NameMatcher.pieceSimilarity]: `Turandot: Atto III. “Nessun
//   dorma”` for `Nessun dorma`), with the same numbers ([NameMatcher.sameNumbers]).
private fun NameMatcher.titleFits(
    title: String,
    workTitle: String,
): Boolean {
    val ours = segments(title)
    val theirs = segments(workTitle)
    val bySegment =
        ours.size == theirs.size &&
            ours.zip(theirs).all { (a, b) ->
                titleSimilarity(a, b) >= ResolutionThresholds.TITLE_SIMILARITY && numberWords(a) == numberWords(b)
            }
    val asPiece =
        (pieceSimilarity(title, workTitle) ?: 0.0) >= ResolutionThresholds.TITLE_SIMILARITY &&
            sameNumbers(title, workTitle)
    return bySegment || asPiece
}

/**
 * The segments of [title] (#1299): its text between the separators of a part of a work's title (`Work:
 * Part`, `Work: Act: Part`), each a colon with whitespace after it, trimmed. A colon in a catalogue number
 * (`Hob. XVI:52`, `BWV 1068:2`) has none, and separates nothing. A scan that reads [title] once.
 */
internal fun segments(title: String): List<String> {
    val found = mutableListOf<String>()
    var from = 0
    for (at in 0 until title.length - 1) {
        if (title[at] == ':' && isJsWhitespace(title[at + 1])) {
            found += title.substring(from, at)
            from = at + 1
        }
    }
    found += title.substring(from)
    return found.map { it.trim { c -> isJsWhitespace(c) } }
}

private const val COMPOSER = "composer"

// MusicBrainz's work-artist relationships of someone who arranged the work.
private val ARRANGER_TYPES = setOf("arranger", "instrument arranger", "vocal arranger", "orchestrator")

// A catch-all for a piece's arrangements, as its disambiguation's key has it (`catch‐all`, `catch-all`), or
// written as one word.
private val CATCH_ALL = "catch" to "all"
private const val CATCH_ALL_JOINED = "catchall"
