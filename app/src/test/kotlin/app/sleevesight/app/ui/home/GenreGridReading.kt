package app.sleevesight.app.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction

/**
 * How a screen reader reads this row of a genre's tags (#786): its one reading, the merged node's
 * content description ("Track, Rock, selected, Pop"); null for none.
 */
internal fun SemanticsNodeInteraction.reading(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
