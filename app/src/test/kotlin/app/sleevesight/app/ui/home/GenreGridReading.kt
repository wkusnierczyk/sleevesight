package app.sleevesight.app.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick

/**
 * How a screen reader reads this row of a genre's tags (#786): its one reading, the merged node's
 * content description ("Track, Pop, Rock, selected"); null for none.
 */
internal fun SemanticsNodeInteraction.reading(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

/**
 * This genre chip's genre unfolded: a tap, unless it is open already, as the genre the panel opens on
 * (#875), where a tap would fold it.
 */
internal fun SemanticsNodeInteraction.unfold(): SemanticsNodeInteraction {
    if (fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) != true) performClick()
    return this
}
