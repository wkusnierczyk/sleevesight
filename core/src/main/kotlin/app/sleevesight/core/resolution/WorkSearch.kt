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
 *   relationships, and no disambiguation calling it an arrangement, a transcription or a catch-all for
 *   them. MusicBrainz keeps a work for each arrangement of a much-arranged piece (Barber's *Adagio for
 *   Strings* for organ, for clarinet choir, and a catch-all for the rest), of the same title and composer;
 * - its title fits the track's ([titleFits]): the same structure (a part of a work, `Work: Part`, or a
 *   whole), then alike as a recording's title is, with the same numbers.
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
// among its artist relationships, or a disambiguation that says so (`arr. John O'Reilly for woodwind band`,
// `catch‐all for arrangements`, `transcription for piano`).
private fun NameMatcher.arranged(
    work: MbFoundWork,
    composer: String,
): Boolean {
    val said = key(work.disambiguation.orEmpty())
    return CATCH_ALL in said ||
        said.split(' ').any { it in ARRANGEMENT_WORDS } ||
        work.artistRelations.any { it.type in ARRANGER_TYPES && !sameName(it.artistName, composer) }
}

// Whether a work's title [workTitle] fits [title] ([searchedWork]): a whole work's a whole one's, alike as a
// recording's title is ([NameMatcher.titleSimilarity]); a part's a part's, so too or as a part of a work
// ([NameMatcher.pieceSimilarity]); a part's, a title that is no part's only as the part it quotes
// (`Turandot: Atto III. “Nessun dorma”` for `Nessun dorma`), never as the whole; a whole work's, never a
// part's. Then with the same numbers ([NameMatcher.sameNumbers]). A whole work and one of its unnumbered
// movements (`Variations on an Original Theme, op. 36 “Enigma”` and the same with `: Nimrod`) are as alike
// as that, and have the same numbers.
private fun NameMatcher.titleFits(
    title: String,
    workTitle: String,
): Boolean {
    val piece = pieceSimilarity(title, workTitle) ?: 0.0
    val similarity =
        when {
            isPart(title) == isPart(workTitle) -> maxOf(titleSimilarity(title, workTitle), piece)
            isPart(workTitle) -> piece
            else -> 0.0
        }
    return similarity >= ResolutionThresholds.TITLE_SIMILARITY && sameNumbers(title, workTitle)
}

/**
 * Whether [title] is of a part of a work's shape (#1299): `Work: Part`, a colon with whitespace after it.
 * A colon in a catalogue number (`Hob. XVI:52`, `BWV 1068:2`) has none. A scan that reads [title] once.
 */
internal fun isPart(title: CharSequence): Boolean =
    (0 until title.length - 1).any { title[it] == ':' && isJsWhitespace(title[it + 1]) }

private const val COMPOSER = "composer"

// MusicBrainz's work-artist relationships of someone who arranged the work.
private val ARRANGER_TYPES = setOf("arranger", "instrument arranger", "vocal arranger", "orchestrator")

// The words of a disambiguation, as keys, that call a work an arrangement ([arranged]).
private val ARRANGEMENT_WORDS =
    setOf(
        "arr",
        "arrangement",
        "arrangements",
        "arranged",
        "transcription",
        "transcribed",
        "orchestration",
        "orchestrated",
        "catchall",
    )

// A catch-all for a piece's arrangements, as its disambiguation's key has it (`catch‐all`, `catch-all`).
private const val CATCH_ALL = "catch all"
