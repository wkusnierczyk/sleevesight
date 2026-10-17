package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.scale
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.score.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colours of the now-playing screen, taken from the album art (design, *User interface*):
 * material-color-utilities picks the art's most suitable colour as the seed of a Material 3
 * scheme, light or dark as the system is. Worked out off the main thread, once per bitmap; until
 * then, and without art, the app's own colours.
 */
@Composable
internal fun ArtTheme(
    art: Bitmap?,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val scheme by produceState<ColorScheme?>(initialValue = null, art, dark) {
        value = art?.let { withContext(Dispatchers.Default) { artScheme(it, dark) } }
    }
    val current = scheme
    if (current == null) content() else MaterialTheme(colorScheme = current, content = content)
}

/** The scheme seeded by [art]'s most suitable colour; null when the art has no pixels. */
internal fun artScheme(
    art: Bitmap,
    dark: Boolean,
): ColorScheme? {
    val seed = artSeed(art) ?: return null
    return SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0).toColorScheme(dark)
}

/**
 * The art's seed colour: its pixels, at most [SAMPLE] a side, quantized to [COLOURS] colours and
 * scored as Android's own wallpaper colours are (`Score`: chroma and proportion, with a fallback
 * when nothing is colourful enough).
 */
internal fun artSeed(art: Bitmap): Int? {
    if (art.width <= 0 || art.height <= 0) return null
    // A hardware bitmap's pixels cannot be read; a copy in memory can.
    val readable = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
    val ratio = minOf(1f, SAMPLE.toFloat() / maxOf(readable.width, readable.height))
    val small =
        if (ratio < 1f) {
            readable.scale(maxOf(1, (readable.width * ratio).toInt()), maxOf(1, (readable.height * ratio).toInt()))
        } else {
            readable
        }
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    if (small !== art) small.recycle()
    if (readable !== art && readable !== small) readable.recycle()
    return Score.score(QuantizerCelebi.quantize(pixels, COLOURS)).firstOrNull()
}

private fun DynamicScheme.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondary = Color(secondary),
        onSecondary = Color(onSecondary),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary),
        onTertiary = Color(onTertiary),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = Color(background),
        onBackground = Color(onBackground),
        surface = Color(surface),
        onSurface = Color(onSurface),
        surfaceVariant = Color(surfaceVariant),
        onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(surfaceTint),
        inverseSurface = Color(inverseSurface),
        inverseOnSurface = Color(inverseOnSurface),
        error = Color(error),
        onError = Color(onError),
        errorContainer = Color(errorContainer),
        onErrorContainer = Color(onErrorContainer),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),
        scrim = Color(scrim),
        surfaceBright = Color(surfaceBright),
        surfaceDim = Color(surfaceDim),
        surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh),
        surfaceContainerHighest = Color(surfaceContainerHighest),
        surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainerLowest = Color(surfaceContainerLowest),
    )
}

private const val SAMPLE = 112
private const val COLOURS = 128
