package app.sleevesight.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The zoom of the guide's picture full screen (#673): how much it is magnified ([scale], from 1, the
 * picture fitted to the screen, to [MAX_SCALE]) and where it is ([offset], its top left corner in
 * the screen's box). At any scale the picture stays in sight: while it is smaller than the box on an
 * axis it is centred on it, and while larger its edges never come inside the box's, so a drag stops
 * at the picture's edge. Plain state, so the screen redraws as it changes.
 */
internal class GuideZoom {
    /** How much the picture is magnified, 1 when fitted. */
    var scale: Float by mutableStateOf(1f)
        private set

    /** The picture's top left corner, in the box. */
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    private var box = Size.Zero
    private var picture = Size.Zero

    /**
     * The [box] the picture is shown in and the [picture]'s size fitted to it, at scale 1, as laid
     * out; on a change the picture is kept in sight at its scale.
     */
    fun bounds(
        box: Size,
        picture: Size,
    ) {
        if (box == this.box && picture == this.picture) return
        this.box = box
        this.picture = picture
        offset = kept(offset, scale)
    }

    /**
     * A pinch or a drag: magnified by [zoom] about [centroid], the point under the fingers staying
     * under them, and moved by [pan].
     */
    fun transform(
        centroid: Offset,
        pan: Offset,
        zoom: Float,
    ) {
        val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
        // The picture's point under the fingers, in the picture's own measure.
        val under = (centroid - offset) / scale
        offset = kept(centroid - under * next + pan, next)
        scale = next
    }

    /**
     * A double tap at [at]: magnified to [DOUBLE_TAP_SCALE] about it, or, while magnified at all,
     * back to the picture fitted to the screen.
     */
    fun toggle(at: Offset) {
        if (scale > 1f) {
            scale = 1f
            offset = kept(offset, 1f)
        } else {
            transform(at, Offset.Zero, DOUBLE_TAP_SCALE)
        }
    }

    // [at] for the picture at [scale]: centred on an axis where it is smaller than the box, else
    // with no gap between its edges and the box's.
    private fun kept(
        at: Offset,
        scale: Float,
    ): Offset {
        fun axis(
            start: Float,
            length: Float,
            room: Float,
        ): Float = if (length <= room) (room - length) / 2 else start.coerceIn(room - length, 0f)
        return Offset(
            axis(at.x, picture.width * scale, box.width),
            axis(at.y, picture.height * scale, box.height),
        )
    }

    companion object {
        /** The most the picture is magnified. */
        const val MAX_SCALE: Float = 5f

        /** How much a double tap magnifies it. */
        const val DOUBLE_TAP_SCALE: Float = 2.5f
    }
}
