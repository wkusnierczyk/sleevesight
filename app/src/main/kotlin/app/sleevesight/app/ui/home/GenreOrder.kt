package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.PlacedLabel
import java.text.Collator
import java.util.Locale

/**
 * The order of the genres' names on *Genres*' chips (#875, `TrackGenres.chips`), in the language
 * the app is shown in ([genreOrder]); kept while the language stays, and used on the main thread
 * alone, as a collator is not safe to share between threads.
 */
@Composable
internal fun rememberGenreOrder(): Comparator<String> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { genreOrder(locale) }
}

/**
 * Names in alphabetical order as [locale]'s language sorts them, case aside (an accented letter
 * with its base letter: *Électro* among the E's, not after *Zydeco*).
 */
internal fun genreOrder(locale: Locale): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * [rows] with each row's labels in the order of [chips], *Genres*' chips (#1159,
 * `TrackGenres.chips`): a genre sits in the same place in the grid as among the chips, sorted by the
 * chips' own order rather than a second one, so the two cannot drift apart. A label whose genre has
 * no chip, which should not happen, goes last, in its row's order.
 */
internal fun inChipOrder(
    rows: List<GenreRow>,
    chips: List<PlacedLabel>,
): List<GenreRow> {
    val place = chips.withIndex().associate { (index, chip) -> chip.label.genre.identity to index }
    return rows.map { row -> row.copy(labels = row.labels.sortedBy { place[it.genre.identity] ?: Int.MAX_VALUE }) }
}
