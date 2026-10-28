package app.sleevesight.app.ui.logo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/**
 * The header's logo, on one line (#383): "Sleeve" in ink and "sight" in red, followed by the
 * record, in the stacked mark's type (Roboto Flex regular, 88% wide, the optical size 52.64), as
 * outlines, so the app needs no font file for it ([LogoOutlines], made by
 * `assets/logo/src/make_app_logo.py`). As the product page's one-line logo
 * (`_includes/logo-linear.html`): the record runs from the top of the "h" to the foot of the "g",
 * 0.266 em after the "t", so [height] is the record's. In the light or dark colours as the
 * screen's background is light or dark. In a debug build ([LOGO_BUG], #652), the tester report
 * button's bug takes the record's place ([drawRecordPlace]), as in the intro's landed logo, which
 * reads the same [LOGO_BUG].
 *
 * It says nothing itself: its caller names it (Home's header: "Sleevesight, scroll to top"). If
 * its box is narrower than the logo, the logo shrinks to fit. While the intro plays
 * ([LocalLogoIntro]), it tells the intro where it is, and draws nothing: the intro draws the
 * logo flying onto it and landed, and this one takes over when the intro ends.
 */
@Composable
fun HeaderLogo(
    modifier: Modifier = Modifier,
    height: Dp = HEADER_LOGO_HEIGHT,
) = HeaderLogo(LOGO_BUG, modifier, height)

/**
 * [HeaderLogo] with the bug in the record's place or not, whatever the build ([LOGO_BUG]): for
 * tests, which draw both on the one build they run on. The app's callers use [HeaderLogo] without
 * it, so the header always draws what the intro lands.
 */
@Composable
internal fun HeaderLogo(
    bug: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = HEADER_LOGO_HEIGHT,
) {
    val intro = LocalLogoIntro.current
    val colors = logoColors()
    val bugPath = logoBug(bug)
    val shown by remember(intro) { derivedStateOf { intro?.finished ?: true } }
    val place =
        if (intro == null) {
            modifier
        } else {
            modifier.onGloballyPositioned { intro.target = Rect(it.positionInRoot(), it.size.toSize()) }
        }
    Canvas(place.size(height * (LineBox.width / LineBox.height), height)) {
        if (!shown) return@Canvas
        // As the intro's landing draws it ([headerPlace]), so the two meet exactly.
        at(linePlace(size)) {
            drawSleeve(colors)
            translate(LineShift.x, LineShift.y) {
                drawSight(colors)
                drawRecordPlace(colors, bugPath)
            }
        }
    }
}

/**
 * The header logo's height, the record's: its capitals are then about as tall as a 24 sp
 * headline's, the app's name as the header showed it before.
 */
val HEADER_LOGO_HEIGHT: Dp = 24.dp

/** The logo's colours for the theme's background: dark where it is dark. */
@Composable
internal fun logoColors(): LogoColors =
    if (MaterialTheme.colorScheme.surface.luminance() < HALF) LogoColors.Dark else LogoColors.Light

private const val HALF = .5f
