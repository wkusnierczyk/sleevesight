package app.sleevesight.core.genres

// The genres past each row's cap (#1281, `GenreRow.extras`), as chips shown on a tap.

/**
 * The genres past the rows' cap as *Genres*' chips show them on a tap (#1281): the
 * [TrackGenres.extraLabels], each placed on the first row whose extras have it, in [names]' order as
 * the [TrackGenres.chips] are. Weak only as Instrumental is weak ([pastCapWeakness]), not for their
 * votes: they are of weaker evidence already, and said so.
 */
fun TrackGenres.extraChips(names: Comparator<String> = TrackGenres.CASE_FOLDED): List<PlacedLabel> {
    val extra = extraLabels.map { it.genre.identity }.toSet()
    val byName = compareBy<PlacedLabel, String>(names) { it.label.genre.label }
    return rows
        .flatMap { row -> row.extras.map { PlacedLabel(row, it) } }
        .distinctBy { it.label.genre.identity }
        .filter { it.label.genre.identity in extra }
        .map { it.copy(weak = pastCapWeakness(it) != null) }
        .sortedWith(byName.thenBy { it.label.genre.identity })
}

/**
 * Why [placed], a chip past the rows' cap ([extraChips], #1281), is weak, as a chip of the five would
 * be for Instrumental, weighed with every row's extras as its labels (so the rows that back it are
 * those whose extras have it, their votes as they are): a sign of vocals (#996, a featured artist's
 * among them), else Wikidata's statement alone (#1292), else LRCLIB's mark alone (#1055); never its
 * few votes. Null for any other genre. What prompts read, and what starts the check of the track's
 * vocals, still read the five alone.
 */
internal fun TrackGenres.pastCapWeakness(placed: PlacedLabel): WeakEvidence? {
    val whole = copy(rows = rows.map { it.copy(labels = it.labels + it.extras, extras = emptyList()) })
    val row = whole.rows.first { it.level == placed.row.level && it.artist == placed.row.artist }
    val chip = PlacedLabel(row, placed.label)
    return whole.signOfVocals(placed.label.genre.identity, row) ?: whole.statedAlone(chip) ?: whole.libraryMark(chip)
}
