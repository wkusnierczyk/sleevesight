package app.sleevesight.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.flow.StateFlow

/**
 * Home's theme button (#636, #771) while it shows: [theme], *Theme* as stored, and [onChoose],
 * which stores the theme picked in its menu. The button shows the current theme ([icon], [label]).
 */
class ThemeButtonState(
    val theme: ThemeChoice,
    val onChoose: (ThemeChoice) -> Unit,
) {
    /** The current theme's symbol ([themeIcon]). */
    @get:DrawableRes
    val icon: Int
        get() = themeIcon(theme)

    /** What a screen reader says, and the tooltip: the current theme ("Theme: dark"). */
    @get:StringRes
    val label: Int
        get() =
            when (theme) {
                ThemeChoice.LIGHT -> R.string.home_theme_light
                ThemeChoice.DARK -> R.string.home_theme_dark
                ThemeChoice.SYSTEM -> R.string.home_theme_system
            }
}

/** The symbol of [theme]: a sun for *Light*, a moon for *Dark*, automatic brightness for *System*. */
@DrawableRes
fun themeIcon(theme: ThemeChoice): Int =
    when (theme) {
        ThemeChoice.LIGHT -> R.drawable.ic_light_mode
        ThemeChoice.DARK -> R.drawable.ic_dark_mode
        ThemeChoice.SYSTEM -> R.drawable.ic_brightness_auto
    }

/** The theme menu's items, in its order: *Light*, *Dark*, *System*. */
val THEME_MENU: List<ThemeChoice> = listOf(ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.SYSTEM)

/** The test tag of the theme menu's item for [theme]. */
fun themeMenuTag(theme: ThemeChoice): String = "theme-menu-${theme.name}"

/**
 * What Home's theme button reads: the settings as last read ([settings], null before the first
 * read), for *Theme button* and *Theme*, and [choose], which saves the theme picked in the menu
 * (`SettingsStore.themeChooser`).
 */
class ThemeButtonFeed(
    val settings: StateFlow<Settings?>,
    val choose: (ThemeChoice) -> Unit,
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
    return remember(theme, feed) { theme?.let { ThemeButtonState(it, feed.choose) } }
}

/**
 * Home's theme button (#771): the current theme's symbol, named for it, as a header button; a tap
 * opens, under it, the menu of the three themes ([THEME_MENU]), each with its symbol, the current
 * one a highlighted row, read as one choice among them with the current one selected, and under
 * *System* what it follows. Picking one closes the menu and, if it is another theme, stores it;
 * a tap outside the menu, or Back, closes it unchanged.
 */
@Composable
internal fun ThemeMenuButton(state: ThemeButtonState) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        HeaderButton(
            stringResource(state.label),
            state.icon,
            onClick = { open = true },
            // Read as collapsed or expanded, as a menu's button is.
            modifier =
                Modifier.semantics {
                    if (open) {
                        collapse {
                            open = false
                            true
                        }
                    } else {
                        expand {
                            open = true
                            true
                        }
                    }
                },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.selectableGroup()) {
                for (theme in THEME_MENU) {
                    ThemeMenuItem(theme, chosen = theme == state.theme) {
                        open = false
                        if (theme != state.theme) state.onChoose(theme)
                    }
                }
            }
        }
    }
}

// One item of the theme menu: [theme]'s symbol and name, the current one ([chosen]) highlighted.
@Composable
private fun ThemeMenuItem(
    theme: ThemeChoice,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = if (chosen) colors.onSecondaryContainer else colors.onSurface
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(themeName(theme)))
                if (theme == ThemeChoice.SYSTEM) {
                    Text(
                        stringResource(R.string.home_theme_menu_system_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen) ink else colors.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = onClick,
        leadingIcon = { Icon(painterResource(themeIcon(theme)), contentDescription = null) },
        colors =
            MenuDefaults.itemColors(
                textColor = ink,
                leadingIconColor = if (chosen) ink else colors.onSurfaceVariant,
            ),
        modifier =
            Modifier
                .background(if (chosen) colors.secondaryContainer else Color.Transparent)
                .testTag(themeMenuTag(theme))
                .semantics {
                    role = Role.RadioButton
                    selected = chosen
                },
    )
}

// The name of [theme], as *Theme* in the settings names it.
@StringRes
private fun themeName(theme: ThemeChoice): Int =
    when (theme) {
        ThemeChoice.LIGHT -> R.string.settings_theme_light
        ThemeChoice.DARK -> R.string.settings_theme_dark
        ThemeChoice.SYSTEM -> R.string.settings_theme_system
    }
