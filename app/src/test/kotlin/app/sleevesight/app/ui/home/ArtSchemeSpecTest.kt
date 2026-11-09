package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The art's colours when material-color-utilities moved to 6.0.0 (#1111). A seed's schemes stay as
 * 5.0.1 gave them, whose default colour spec was the 2021 one: 6.0.0 defaults to the 2025 spec, which
 * changes nearly every role of this seed's schemes, light and dark. The seed some art gives does
 * change, to Google's own (see [manyColouredArtGivesGooglesSeed]).
 */
@RunWith(RobolectricTestRunner::class)
class ArtSchemeSpecTest {
    @Test
    fun aSeedGivesThe2021LightScheme() {
        assertEquals(LIGHT, roles(seedScheme(SEED, dark = false)))
    }

    @Test
    fun aSeedGivesThe2021DarkScheme() {
        assertEquals(DARK, roles(seedScheme(SEED, dark = true)))
    }

    /**
     * The seed of a many-coloured art, as 6.0.0 picks it, which differs from 5.0.1 for some art. 6.0.0's
     * `QuantizerWsmeans` assigns the pixels' first clusters with `JavaRandom`, the `java.util.Random`
     * sequence that Google's Java library and Android's own wallpaper colours use; 5.0.1 departed from
     * them with `kotlin.random.Random`. This art's seed was `FF334C4F` under 5.0.1, a teal where 6.0.0's is
     * a blue; the spec above stays 2021's either way.
     */
    @Test
    fun manyColouredArtGivesGooglesSeed() {
        assertEquals(0xFF323D4F.toInt(), artSeed(stripes(size = 112, stripe = 18, band = 37)))
    }

    /** A many-coloured art whose seed is the same under 5.0.1 and 6.0.0 (scaled down first). */
    @Test
    fun manyColouredArtGivesTheSameSeed() {
        assertEquals(0xFF8C2F39.toInt(), artSeed(stripes(size = 120, stripe = 20, band = 40)))
    }

    /**
     * Stripes of each of [ART_COLOURS], [stripe] pixels wide, shifted by one colour every [band] rows so
     * their shares differ. `artSeed` scales art over `SAMPLE` (112) a side, so a [size] of 112 is read
     * pixel for pixel.
     */
    private fun stripes(
        size: Int,
        stripe: Int,
        band: Int,
    ): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until size) {
                for (x in 0 until size) setPixel(x, y, ART_COLOURS[(x / stripe + y / band) % ART_COLOURS.size])
            }
        }

    /** Every role [ArtTheme] takes from the art, as ARGB hex, so a failure names the roles that moved. */
    private fun roles(scheme: ColorScheme): Map<String, String> =
        with(scheme) {
            listOf(
                "primary" to primary,
                "onPrimary" to onPrimary,
                "primaryContainer" to primaryContainer,
                "onPrimaryContainer" to onPrimaryContainer,
                "inversePrimary" to inversePrimary,
                "secondary" to secondary,
                "onSecondary" to onSecondary,
                "secondaryContainer" to secondaryContainer,
                "onSecondaryContainer" to onSecondaryContainer,
                "tertiary" to tertiary,
                "onTertiary" to onTertiary,
                "tertiaryContainer" to tertiaryContainer,
                "onTertiaryContainer" to onTertiaryContainer,
                "background" to background,
                "onBackground" to onBackground,
                "surface" to surface,
                "onSurface" to onSurface,
                "surfaceVariant" to surfaceVariant,
                "onSurfaceVariant" to onSurfaceVariant,
                "surfaceTint" to surfaceTint,
                "inverseSurface" to inverseSurface,
                "inverseOnSurface" to inverseOnSurface,
                "error" to error,
                "onError" to onError,
                "errorContainer" to errorContainer,
                "onErrorContainer" to onErrorContainer,
                "outline" to outline,
                "outlineVariant" to outlineVariant,
                "scrim" to scrim,
                "surfaceBright" to surfaceBright,
                "surfaceDim" to surfaceDim,
                "surfaceContainer" to surfaceContainer,
                "surfaceContainerHigh" to surfaceContainerHigh,
                "surfaceContainerHighest" to surfaceContainerHighest,
                "surfaceContainerLow" to surfaceContainerLow,
                "surfaceContainerLowest" to surfaceContainerLowest,
            ).associate { (name, colour) -> name to "%08X".format(colour.toArgb()) }
        }

    private companion object {
        const val SEED = 0xFF6A3D9A.toInt()
        val ART_COLOURS =
            listOf(0xFF1B3A5C, 0xFFD9A441, 0xFF8C2F39, 0xFF2E6B4F, 0xFFFFFFFF, 0xFF404040).map { it.toInt() }

        val LIGHT =
            mapOf(
                "primary" to "FF6E528B",
                "onPrimary" to "FFFFFFFF",
                "primaryContainer" to "FFEFDBFF",
                "onPrimaryContainer" to "FF553B71",
                "inversePrimary" to "FFDABAF9",
                "secondary" to "FF655A6F",
                "onSecondary" to "FFFFFFFF",
                "secondaryContainer" to "FFECDDF6",
                "onSecondaryContainer" to "FF4D4357",
                "tertiary" to "FF805158",
                "onTertiary" to "FFFFFFFF",
                "tertiaryContainer" to "FFFFD9DD",
                "onTertiaryContainer" to "FF653A41",
                "background" to "FFFFF7FF",
                "onBackground" to "FF1E1A20",
                "surface" to "FFFFF7FF",
                "onSurface" to "FF1E1A20",
                "surfaceVariant" to "FFE9E0EB",
                "onSurfaceVariant" to "FF4A454E",
                "surfaceTint" to "FF6E528B",
                "inverseSurface" to "FF332F35",
                "inverseOnSurface" to "FFF6EEF6",
                "error" to "FFBA1A1A",
                "onError" to "FFFFFFFF",
                "errorContainer" to "FFFFDAD6",
                "onErrorContainer" to "FF93000A",
                "outline" to "FF7B757E",
                "outlineVariant" to "FFCCC4CF",
                "scrim" to "FF000000",
                "surfaceBright" to "FFFFF7FF",
                "surfaceDim" to "FFDFD8DF",
                "surfaceContainer" to "FFF3EBF3",
                "surfaceContainerHigh" to "FFEEE6EE",
                "surfaceContainerHighest" to "FFE8E0E8",
                "surfaceContainerLow" to "FFF9F1F9",
                "surfaceContainerLowest" to "FFFFFFFF",
            )

        val DARK =
            mapOf(
                "primary" to "FFDABAF9",
                "onPrimary" to "FF3E2459",
                "primaryContainer" to "FF553B71",
                "onPrimaryContainer" to "FFEFDBFF",
                "inversePrimary" to "FF6E528B",
                "secondary" to "FFD0C1DA",
                "onSecondary" to "FF362D3F",
                "secondaryContainer" to "FF4D4357",
                "onSecondaryContainer" to "FFECDDF6",
                "tertiary" to "FFF3B7BF",
                "onTertiary" to "FF4B252B",
                "tertiaryContainer" to "FF653A41",
                "onTertiaryContainer" to "FFFFD9DD",
                "background" to "FF151218",
                "onBackground" to "FFE8E0E8",
                "surface" to "FF151218",
                "onSurface" to "FFE8E0E8",
                "surfaceVariant" to "FF4A454E",
                "onSurfaceVariant" to "FFCCC4CF",
                "surfaceTint" to "FFDABAF9",
                "inverseSurface" to "FFE8E0E8",
                "inverseOnSurface" to "FF332F35",
                "error" to "FFFFB4AB",
                "onError" to "FF690005",
                "errorContainer" to "FF93000A",
                "onErrorContainer" to "FFFFDAD6",
                "outline" to "FF968E98",
                "outlineVariant" to "FF4A454E",
                "scrim" to "FF000000",
                "surfaceBright" to "FF3C383E",
                "surfaceDim" to "FF151218",
                "surfaceContainer" to "FF221E24",
                "surfaceContainerHigh" to "FF2C292E",
                "surfaceContainerHighest" to "FF373339",
                "surfaceContainerLow" to "FF1E1A20",
                "surfaceContainerLowest" to "FF100D12",
            )
    }
}
