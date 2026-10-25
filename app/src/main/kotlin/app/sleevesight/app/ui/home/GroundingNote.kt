package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.synthesis.GroundedText

/**
 * The line under a written insight: with [details] (*Show grounding details*, a developer option,
 * #437) the grounding check's counts in full ([Meta]), else a note only when there is something
 * to say ([Note]). [modifier] goes to the line's text, so a caller that sets the line in a row
 * beside other content (a weight) keeps that content whether or not a note shows.
 */
@Composable
internal fun GroundingLine(
    grounded: GroundedText,
    cached: Boolean,
    details: Boolean,
    modifier: Modifier = Modifier,
) {
    if (details) Meta(grounded, cached, modifier) else Note(grounded, cached, modifier)
}

/**
 * The note under the insight (#437), shown only when there is something to say: how many sentences
 * the sources do not back (uncited, citing missing evidence, or from the model's own knowledge),
 * and that the insight was kept from before, which explains *Rewrite*. A sentence the sources back
 * needs no word: its citations show it. The full counts are [Meta]'s, behind *Show grounding details*.
 */
@Composable
private fun Note(
    grounded: GroundedText,
    cached: Boolean,
    modifier: Modifier,
) {
    val counts = grounded.counts
    val unbacked = counts.uncited + counts.unknown + counts.model
    val parts =
        buildList {
            if (unbacked > 0) add(pluralStringResource(R.plurals.home_note_unbacked, unbacked, unbacked))
            if (cached) add(stringResource(R.string.home_note_kept))
        }
    if (parts.isNotEmpty()) MetaLine(parts.joinToString(" "), modifier)
}

/** The grounding check's counts in full, as the simulator gives them (*Show grounding details*). */
@Composable
private fun Meta(
    grounded: GroundedText,
    cached: Boolean,
    modifier: Modifier,
) {
    val counts = grounded.counts
    val parts =
        buildList {
            val sound = counts.ok + counts.hedge
            add(pluralStringResource(R.plurals.home_meta_grounded, counts.total, sound, counts.total))
            if (counts.model > 0) add(stringResource(R.string.home_meta_model, counts.model))
            if (counts.uncited > 0) add(stringResource(R.string.home_meta_uncited, counts.uncited))
            if (counts.unknown > 0) {
                add(pluralStringResource(R.plurals.home_meta_unknown, counts.unknown, counts.unknown))
            }
            if (cached) add(stringResource(R.string.home_meta_kept))
        }
    MetaLine(parts.joinToString(" · "), modifier)
}

@Composable
private fun MetaLine(
    text: String,
    modifier: Modifier,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
