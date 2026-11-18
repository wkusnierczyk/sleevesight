package app.sleevesight.core.genres

// The genres past each row's cap (#1281, `GenreRow.extras`), as chips shown on a tap.

/**
 * The genres past the rows' cap as *Genres*' chips show them on a tap (#1281): the
 * [TrackGenres.extraLabels], each placed on the first row whose extras have it, in [names]' order as
 * the [TrackGenres.chips] are. Weak only as #996 leaves Instrumental weak ([TrackGenres.signOfVocals],
 * LRCLIB's mark alone, #1055), not for their votes: they are of weaker evidence already, and said so.
 */
fun TrackGenres.extraChips(names: Comparator<String> = TrackGenres.CASE_FOLDED): List<PlacedLabel> {
    val extra = extraLabels.map { it.genre.identity }.toSet()
    val byName = compareBy<PlacedLabel, String>(names) { it.label.genre.label }
    return rows
        .flatMap { row -> row.extras.map { PlacedLabel(row, it) } }
        .distinctBy { it.label.genre.identity }
        .filter { it.label.genre.identity in extra }
        .map { it.copy(weak = signOfVocals(it.label.genre.identity, it.row) != null || libraryMark(it) != null) }
        .sortedWith(byName.thenBy { it.label.genre.identity })
}
