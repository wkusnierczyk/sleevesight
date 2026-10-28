package app.sleevesight.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.flow.StateFlow

/**
 * Home's theme button (#636) while it shows: [theme], *Theme* as stored, and [onCycle], which moves
 * it one step on ([ThemeChoice.next]). The button shows the theme a tap gives, not the current one
 * ([icon], [label]).
 */
class ThemeButtonState(
    val theme: ThemeChoice,
    val onCycle: () -> Unit,
) {
    /** The symbol of the theme a tap gives: a moon for *Dark*, automatic brightness for *System*, a sun for *Light*. */
    @get:DrawableRes
    val icon: Int
        get() =
            when (theme.next) {
                ThemeChoice.DARK -> R.drawable.ic_dark_mode
                ThemeChoice.SYSTEM -> R.drawable.ic_brightness_auto
                ThemeChoice.LIGHT -> R.drawable.ic_light_mode
            }

    /** What a screen reader says, and the tooltip: the theme a tap gives ("Switch to dark theme"). */
    @get:StringRes
    val label: Int
        get() =
            when (theme.next) {
                ThemeChoice.DARK -> R.string.home_theme_to_dark
                ThemeChoice.SYSTEM -> R.string.home_theme_to_system
                ThemeChoice.LIGHT -> R.string.home_theme_to_light
            }
}

/**
 * What Home's theme button reads: the settings as last read ([settings], null before the first
 * read), for *Theme button* and *Theme*, and [cycle], which saves the next theme from the stored one
 * (`SettingsStore.themeCycler`).
 */
class ThemeButtonFeed(
    val settings: StateFlow<Settings?>,
    val cycle: () -> Unit,
)

/**
 * Home's theme button as [feed] has it: null with no feed, before the settings' first read (so a
 * button switched off never flashes on), and while *Theme button* is off.
 */
@Composable
fun themeButtonOf(feed: ThemeButtonFeed?): ThemeButtonState? {
    if (feed == null) return null
    val settings by feed.settings.collectAsState()
    val theme = settings?.takeIf { it.themeButton }?.theme
    return remember(theme, feed) { theme?.let { ThemeButtonState(it, feed.cycle) } }
}
