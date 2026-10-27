package app.sleevesight.app.ui.logo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.vectorResource
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.R

/**
 * Whether this build's one-line logo draws the bug in the record's place (#652): a debug build's
 * does, so it can be told from the Play build inside the app too, as its launcher icon, label and
 * version tell it apart outside. A release build's draws the record.
 */
internal val LOGO_BUG: Boolean = BuildConfig.DEBUG

/**
 * The tester report button's bug (`ic_bug_report`, Material Symbols' 960-unit box) as one path, for
 * the one-line logo to draw in the record's place; null when [shown] is false, where it draws the
 * record. Taken from the drawable itself, so the logo's bug is the button's.
 */
@Composable
internal fun logoBug(shown: Boolean = LOGO_BUG): Path? {
    if (!shown) return null
    val vector = ImageVector.vectorResource(R.drawable.ic_bug_report)
    return remember(vector) { Path().apply { addPaths(vector.root) } }
}

private fun Path.addPaths(group: VectorGroup) {
    for (node in group) {
        when (node) {
            is VectorPath -> {
                addPath(PathParser().addPathNodes(node.pathData).toPath())
                fillType = node.pathFillType
            }
            is VectorGroup -> addPaths(node)
        }
    }
}

/**
 * Draws the record's place in the one-line logo: the record ([drawRecord]) at [pose], or, with a
 * [bug] ([logoBug]), the bug in the record's box, as tall as the record (its ink from the top of
 * its feelers to its foot) and centred on the record's centre, in the words' ink. The pose's
 * radius, turn and opacity act on either as on the record, so the intro turns either in.
 */
internal fun DrawScope.drawRecordPlace(
    colors: LogoColors,
    bug: Path?,
    pose: RecordPose = RecordPose(RecordCentre),
) {
    if (bug == null) {
        drawRecord(colors, pose.centre, pose.radius, pose.degrees, pose.alpha)
        return
    }
    if (pose.alpha <= 0f || pose.radius <= 0f) return
    val k = 2 * pose.radius / BUG_INK_HEIGHT
    translate(pose.centre.x, pose.centre.y) {
        rotate(pose.degrees, pivot = Offset.Zero) {
            scale(k, k, pivot = Offset.Zero) {
                translate(-BUG_INK_CENTRE, -BUG_INK_CENTRE) { drawPath(bug, colors.ink, pose.alpha) }
            }
        }
    }
}

/**
 * The bug's ink in its 960-unit box: from 120 (its feelers) to 840 (its foot) down, and 160 to 800
 * across, so centred on (480, 480).
 */
private const val BUG_INK_HEIGHT = 720f
private const val BUG_INK_CENTRE = 480f
