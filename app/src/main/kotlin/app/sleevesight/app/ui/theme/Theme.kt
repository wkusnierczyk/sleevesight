package app.sleevesight.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.sleevesight.app.R
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.StateFlow

/**
 * Roboto (#377), bundled in `res/font`: Roboto 3.016 from `googlefonts/roboto-3-classic`, its
 * `android/static` build, under the SIL Open Font License 1.1 (`assets/licences/roboto-ofl.txt`,
 * and the credits). Regular and Medium, the weights Material 3's type scale uses (400 and 500),
 * and the citations' `FontWeight.Medium`. No bold and no italics: the app sets neither, and one
 * set later is synthesized until its file is added.
 *
 * A family of the two files rather than a `res/font` family XML: Compose picks the weight among
 * the fonts of a family it is given, while an XML family reaches it as one `Typeface`, whose
 * weights only Android 9 and later can select by number (the minimum is Android 8, where Medium
 * would be drawn as Regular or synthesized).
 *
 * A character Roboto lacks (Chinese, Japanese, Korean, Arabic, Hebrew, Thai and other scripts in a
 * track's title) is drawn in the system's fallback fonts, as with the system font.
 */
internal val Roboto: FontFamily =
    FontFamily(
        Font(R.font.roboto_regular, FontWeight.Normal),
        Font(R.font.roboto_medium, FontWeight.Medium),
    )

/** *Font*'s default, the app's own. */
private val DEFAULT_FONT: FontChoice = Settings().font

/**
 * Material 3's type scale in [font]: Roboto for the app's font, and Material's own default for the
 * system font, which draws in the font chosen in the phone's settings.
 */
internal fun typographyOf(font: FontChoice): Typography =
    when (font) {
        FontChoice.SYSTEM_FONT -> Typography()
        FontChoice.APP_FONT -> Typography().inFamily(Roboto)
    }

private fun Typography.inFamily(family: FontFamily): Typography {
    fun TextStyle.inFamily() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.inFamily(),
        displayMedium = displayMedium.inFamily(),
        displaySmall = displaySmall.inFamily(),
        headlineLarge = headlineLarge.inFamily(),
        headlineMedium = headlineMedium.inFamily(),
        headlineSmall = headlineSmall.inFamily(),
        titleLarge = titleLarge.inFamily(),
        titleMedium = titleMedium.inFamily(),
        titleSmall = titleSmall.inFamily(),
        bodyLarge = bodyLarge.inFamily(),
        bodyMedium = bodyMedium.inFamily(),
        bodySmall = bodySmall.inFamily(),
        labelLarge = labelLarge.inFamily(),
        labelMedium = labelMedium.inFamily(),
        labelSmall = labelSmall.inFamily(),
    )
}

/**
 * Material 3, light or dark as the system is, with the system's dynamic colours where Android has
 * them (12 and later), and its text in [font] (*Font*, #377). The colours taken from the album art
 * (design, *User interface*) replace these on the now-playing screen, and keep the type.
 */
@Composable
fun SleevesightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    font: FontChoice = DEFAULT_FONT,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
    val typography = remember(font) { typographyOf(font) }
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}

/**
 * [SleevesightTheme] in the font [fonts] holds, following each change to it, and starting in its
 * current value, so the first frame is already in it (`AppGraph.font`).
 */
@Composable
fun SleevesightTheme(
    fonts: StateFlow<FontChoice>,
    content: @Composable () -> Unit,
) {
    val font by fonts.collectAsState()
    SleevesightTheme(font = font, content = content)
}
