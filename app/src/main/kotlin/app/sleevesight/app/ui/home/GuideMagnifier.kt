package app.sleevesight.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sleevesight.app.R
import kotlin.math.min

/**
 * The magnifier on the guide's picture (#673), at the drawn cover's bottom right corner, clear of
 * the arrows: a small round button that opens the picture full screen ([onClick]), as a tap on the
 * picture does; a screen reader reads it as *Magnify the picture*, a button, its 48 dp touch target
 * around the drawn 32 dp.
 */
@Composable
internal fun GuideMagnifierButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.home_guide_magnify)
    Box(
        modifier
            .testTag(GUIDE_MAGNIFY_TAG)
            .size(TARGET)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(DRAWN)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_zoom_in),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(ICON),
            )
        }
    }
}

/**
 * The guide's picture full screen (#673): the drawn Home ([MiniHome]) alone, without the notes,
 * fitted to the screen under a bar with its name and *Close* ([onClose]), which Back does too.
 * Pinch, or double-tap, to magnify it, double-tap again to fit it back, and drag to move it
 * ([zoom], which its opener keeps across a rotation: state saved inside a dialog is not restored).
 * A screen reader reads the picture as one image, *Home while
 * a track plays*.
 */
@Composable
internal fun GuideMagnified(
    onClose: () -> Unit,
    zoom: GuideZoom = remember { GuideZoom() },
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag(GUIDE_MAGNIFIED_TAG)) {
            Column(Modifier.safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_guide_magnified),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = onClose, modifier = Modifier.testTag(GUIDE_MAGNIFIED_CLOSE_TAG)) {
                        Text(stringResource(R.string.page_close))
                    }
                }
                Zoomed(zoom, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

// The drawn Home, fitted to the box and zoomed as [GuideZoom] says, in the box's bounds.
@Composable
private fun Zoomed(
    zoom: GuideZoom,
    modifier: Modifier,
) {
    val described = stringResource(R.string.home_guide_magnified)
    Layout(
        content = {
            MiniHome(
                Modifier.testTag(GUIDE_MAGNIFIED_PICTURE_TAG).clearAndSetSemantics {
                    contentDescription = described
                    role = Role.Image
                },
                report = { _, _ -> },
            )
        },
        modifier =
            modifier
                .clipToBounds()
                .pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::toggle) }
                .pointerInput(zoom) {
                    detectTransformGestures {
                        centroid,
                        pan,
                        factor,
                        _,
                        ->
                        zoom.transform(centroid, pan, factor)
                    }
                },
    ) { measurables, constraints ->
        val margin = 2 * MARGIN.roundToPx()
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val picture =
            measurables.single().measure(
                Constraints(maxWidth = (constraints.maxWidth - margin).coerceAtLeast(0)),
            )
        // Fitted: as wide as the box less its margins, or less where that would be taller than it;
        // centred in the box, by the zoom, as long as it is not magnified past it.
        val fit = min(1f, (box.height - margin) / picture.height.coerceAtLeast(1))
        zoom.bounds(box, Size(picture.width * fit, picture.height * fit))
        layout(constraints.maxWidth, constraints.maxHeight) {
            picture.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = fit * zoom.scale
                scaleY = fit * zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            }
        }
    }
}

private val TARGET = 48.dp
private val DRAWN = 32.dp
private val ICON = 20.dp

/** Around the picture full screen, at its fitted size, on each side. */
private val MARGIN = 16.dp

/** The test tag of the magnifier at the picture's corner. */
const val GUIDE_MAGNIFY_TAG: String = "home-guide-magnify"

/** The test tags of the picture full screen, of the picture in it, and of its *Close*. */
const val GUIDE_MAGNIFIED_TAG: String = "home-guide-magnified"
const val GUIDE_MAGNIFIED_PICTURE_TAG: String = "home-guide-magnified-picture"
const val GUIDE_MAGNIFIED_CLOSE_TAG: String = "home-guide-magnified-close"
