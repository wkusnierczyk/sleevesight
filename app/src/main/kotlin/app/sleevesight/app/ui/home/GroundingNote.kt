package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus

/**
 * The line under a written insight: with [details] (*Show grounding details*, a developer option,
 * #437) the grounding check's counts in full ([Meta]), else a note only when there is something
 * to say ([Note]). [modifier] goes to the line's text. With no note, nothing is drawn, and the
 * modifier with it: a caller that sets the line in a row beside other content fills the space
 * itself, so that content keeps its place (a weighted `Box` around the line, a
 * `Spacer(Modifier.weight(1f))`, or `Arrangement.End`).
 */
@Composable
internal fun GroundingLine(
    grounded: GroundedText,
    cached: Boolean,
    details: Boolean,
    modifier: Modifier = Modifier,
) {
    // The full counts show only in developer mode, and are marked as such (#536).
    if (details) DeveloperMark { Meta(grounded, cached, modifier) } else Note(grounded, cached, modifier)
}

/**
 * The note under the insight (#437), shown only when there is something to say: the sentences the
 * sources do not back, which are underlined in red (uncited, or citing missing evidence), named by
 * that mark ("The sentence underlined in red is not backed by the sources.", #664), and that the
 * insight was kept from before. A sentence from the model's own knowledge is not counted: it is
 * not underlined, and its own chip says where it is from. A sentence the sources back
 * needs no word: its citations show it. The full counts are [Meta]'s, behind *Show grounding details*.
 * A sentence that shows as nothing once its Markdown is cleaned (a rule on its own, #555) is not
 * counted: the reader could not find it.
 */
@Composable
private fun Note(
    grounded: GroundedText,
    cached: Boolean,
    modifier: Modifier,
) {
    val unbacked = remember(grounded) { unbacked(grounded) }
    val parts =
        buildList {
            if (unbacked > 0) add(pluralStringResource(R.plurals.home_note_unbacked, unbacked, unbacked))
            if (cached) add(stringResource(R.string.home_note_kept))
        }
    if (parts.isNotEmpty()) MetaLine(parts.joinToString(" "), modifier)
}

// The sentences the sources do not back that show on the card, underlined.
private fun unbacked(grounded: GroundedText): Int =
    grounded.paragraphs.sumOf { paragraph ->
        paragraph.zip(shown(paragraph.map { it.text })).count { (sentence, runs) ->
            sentence.status in UNDERLINED && !runs.isBlank()
        }
    }

// The statuses `CheckedProse` underlines in red, which the note counts.
private val UNDERLINED = setOf(SentenceStatus.UNCITED, SentenceStatus.UNKNOWN)

/**
 * The statuses of a sentence the sources do not back, which reading aloud marks "Unverified:" (#325):
 * the ones underlined in red ([UNDERLINED], which the note counts) and the model's own knowledge.
 */
internal val UNBACKED = setOf(SentenceStatus.UNCITED, SentenceStatus.UNKNOWN, SentenceStatus.MODEL)

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
            if (grounded.withdrawn != null) add(stringResource(R.string.home_meta_self_corrected))
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
