package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
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
