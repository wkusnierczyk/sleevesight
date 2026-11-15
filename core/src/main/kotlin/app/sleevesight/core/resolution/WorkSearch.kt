package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.text.isJsWhitespace

// The work of a recording that links none, nor is lent one, found by a search of its composer's works (#1299).

/**
 * The work of a recording that links none, nor is lent one (#1299): with a composer the title leads with
 * ([NormalizedTrack.composer]), one search of MusicBrainz's works by the title without the lead
 * ([NormalizedTrack.searchTitle]) and the composer's name ([MusicBrainzLookup.searchWorks]), of which
 * [canonicalWork] takes one or none. It is linked as this recording's own performance of it (no cover, not
 * instrumental), and looked up as any work. Without a composer, none, no request made; a search that fails,
 * none with the failure, so the resolution is asked again later.
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
            names
                .canonicalWork(title, composer, found.value)
                ?.let { MbWorkLink(it.mbid, it.title, cover = false, type = it.type) to work(it.mbid) }
                ?: (null to null)
    }
}

/**
 * Of the works a search by [title] and [composer] [found] (#1299, [MusicBrainzLookup.searchWorks]), the one
 * that is the piece itself, not one derived from it, when it is the only one; null otherwise. MusicBrainz
 * keeps a work for each arrangement, version and setting of a much-played piece, most of the same title
 * and composer (Barber's *Adagio for Strings* for organ, *Für Elise* as production music, *Eine kleine
 * Nachtmusik* with lyrics by Georg Kreisler, Ravel's own *Boléro* for two pianos), so a work is taken only
 * when all of these hold:
 * - *It is known by many names*: at least [CANONICAL_ALIASES] aliases, the evidence that it is the piece
 *   itself. A lesser-known piece with fewer is then given no work, which errs the safe way.
 * - *Only the composer composed it*: every `composer` or `writer` relationship names [composer], and one does.
 *   A lyricist alone does not count against it: an art song or an aria has one.
 * - *It is no arrangement*: no `arranger`, `instrument arranger`, `vocal arranger` or `orchestrator`
 *   relationship, but the composer as its `orchestrator` (Barber orchestrated his own Adagio); the
 *   composer's own `arranger` credit is an arrangement (`arr. Ravel for 2 pianos`).
 * - *It is no named version*: its disambiguation names none of its related artists but the composer (by the
 *   last word of their name: `production music version, Rasquin`, `with lyrics by Georg Kreisler`), and
 *   does not mark it a catch-all for arrangements.
 * - *Its title fits the track's* ([titleFits]): segment by segment, or as the part it quotes.
 *
 * With two or more left, nothing tells which is the track's, and none is taken.
 */
fun NameMatcher.canonicalWork(
    title: String,
    composer: String,
    found: List<MbFoundWork>,
): MbFoundWork? =
    found
        .filter { work ->
            work.aliases >= CANONICAL_ALIASES &&
                composedByLeadAlone(work, composer) &&
                !arranged(work, composer) &&
                !namedVersion(work, composer) &&
                titleFits(title, work.title)
        }.singleOrNull()

/**
 * The fewest aliases a work needs to be taken for the piece itself ([canonicalWork], #1299). The pieces
 * looked at have many, in the languages they are known in and their catalogue forms: Barber's *Adagio for
 * Strings* 12, Ravel's *Boléro* 13, Beethoven's *Für Elise* 31, Mozart's *Eine kleine Nachtmusik* 19 to 48
 * (2026-11-15); the arrangements, versions and catch-alls of them none to two.
 */
const val CANONICAL_ALIASES: Int = 3

// Whether every `composer` or `writer` relationship of [work] names [composer], and one does ([canonicalWork]).
private fun NameMatcher.composedByLeadAlone(
    work: MbFoundWork,
    composer: String,
): Boolean {
    val writers = work.artistRelations.filter { it.type in WRITER_TYPES }
    return writers.isNotEmpty() && writers.all { sameName(it.artistName, composer) }
}

// Whether [work] is an arrangement ([canonicalWork]): an arranger of any kind, but [composer] as its
// orchestrator.
private fun NameMatcher.arranged(
    work: MbFoundWork,
    composer: String,
): Boolean =
    work.artistRelations.any {
        it.type in ARRANGER_TYPES && !(it.type == ORCHESTRATOR && sameName(it.artistName, composer))
    }

// Whether [work]'s disambiguation names a version of it ([canonicalWork]): one of its related artists but
// [composer], by the last word of their name, or a catch-all for arrangements (`catch‐all`, `catch-all`,
// `catchall`).
private fun NameMatcher.namedVersion(
    work: MbFoundWork,
    composer: String,
): Boolean {
    val words = key(work.disambiguation.orEmpty()).split(' ').filter { it.isNotEmpty() }
    val others =
        work.artistRelations
            .filterNot { sameName(it.artistName, composer) }
            .mapNotNull { nameKey(it.artistName).split(' ').lastOrNull()?.ifEmpty { null } }
    return CATCH_ALL in words.zipWithNext() || CATCH_ALL_JOINED in words || others.any { it in words }
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

// MusicBrainz's work-artist relationships of someone who wrote the music.
private val WRITER_TYPES = setOf("composer", "writer")

private const val ORCHESTRATOR = "orchestrator"

// MusicBrainz's work-artist relationships of someone who arranged the work.
private val ARRANGER_TYPES = setOf("arranger", "instrument arranger", "vocal arranger", ORCHESTRATOR)

// A catch-all for a piece's arrangements, as its disambiguation's key has it (`catch‐all`, `catch-all`), or
// written as one word.
private val CATCH_ALL = "catch" to "all"
private const val CATCH_ALL_JOINED = "catchall"
