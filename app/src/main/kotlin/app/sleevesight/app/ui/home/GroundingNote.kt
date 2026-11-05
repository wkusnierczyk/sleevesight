package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.DeveloperMark
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus

/**
 * The grounding line under a written insight or a genre's reason: with [details] (*Show grounding
 * details*, a developer option, #437) the grounding check's counts in full ([Meta]), else a note
 * only when there is something to say ([Note]). Whether it was kept from before is not its to say:
 * the insight's footer says so (*As written previously.*, #671), a genre's reason its own line
 * (`KeptLine`, #851).
 * [modifier] goes to the line's text. With no note, nothing is drawn, and the modifier with it: a
 * caller that sets the line in a row beside other content fills the space itself, so that content
 * keeps its place (a weighted `Box` around the line, a `Spacer(Modifier.weight(1f))`, or
 * `Arrangement.End`).
 */
@Composable
internal fun GroundingLine(
    grounded: GroundedText,
    details: Boolean,
    modifier: Modifier = Modifier,
) {
    // The full counts show only in developer mode, and are marked as such (#536).
    if (details) DeveloperMark { Meta(grounded, modifier) } else Note(grounded, modifier)
}

/**
 * The note under the insight (#437), shown only when there is something to say: the sentences the
 * sources do not back, which are dimmed (uncited, or citing missing evidence; #819), named by
 * that mark (#664), in one text for any number of them ("Dimmed text is not backed by the
 * sources.", #854). A sentence from the
 * model's own knowledge is not counted: it is not dimmed, and its own chip says where it is from. A
 * sentence the sources back
 * needs no word: its citations show it. The full counts are [Meta]'s, behind *Show grounding details*.
 * A sentence that shows as nothing once its Markdown is cleaned (a rule on its own, #555) is not
 * counted: the reader could not find it.
 */
@Composable
private fun Note(
    grounded: GroundedText,
    modifier: Modifier,
) {
    val unbacked = remember(grounded) { unbacked(grounded) }
    if (unbacked > 0) MetaLine(stringResource(R.string.home_note_unbacked), modifier)
}

// The sentences the sources do not back that show on the card, dimmed.
private fun unbacked(grounded: GroundedText): Int =
    grounded.paragraphs.sumOf { paragraph ->
        paragraph.zip(shown(paragraph.map { it.text })).count { (sentence, runs) ->
            sentence.status in DIMMED && !runs.isBlank()
        }
    }

// The statuses `CheckedProse` dims, which the note counts.
private val DIMMED = setOf(SentenceStatus.UNCITED, SentenceStatus.UNKNOWN)

/**
 * The statuses of a sentence the sources do not back, which reading aloud marks "Unverified:" (#325):
 * the ones dimmed ([DIMMED], which the note counts) and the model's own knowledge.
 */
internal val UNBACKED = setOf(SentenceStatus.UNCITED, SentenceStatus.UNKNOWN, SentenceStatus.MODEL)

/**
 * Under an insight, a song's, an artist's or an album's insight or a genre's reason that cites lines
 * found in the track's language (#902, `EvidenceLine.language`), after the grounding note: which
 * citations it translated, *Translated from Hindi: W2 and S1.* ([R.string.home_note_translated]), one
 * line per language, the citations joined as *Sources*' lists are ([listText]). The citations are
 * those of [grounded] that point at a line of [evidence], in the order they first come, a citation
 * outside [held] (the ids its prompt held, when it left some out, #239) not one, as its chip shows
 * it; the chips themselves stay as they are. Nothing is drawn when none is in another language.
 */
@Suppress("SpreadOperator") // A handful of arguments, passed straight through.
@Composable
internal fun TranslatedLine(
    grounded: GroundedText,
    evidence: EvidenceSet?,
    held: Set<String>? = null,
    modifier: Modifier = Modifier,
) {
    val translated = remember(grounded, evidence, held) { translatedCites(grounded, evidence, held) }
    if (translated.isEmpty()) return
    val resources = LocalResources.current
    val words = Words { id, args -> resources.getString(id, *args) }
    for ((language, ids) in translated) {
        MetaLine(
            stringResource(R.string.home_note_translated, languageName(language), listText(ids, words)),
            modifier.testTag(TRANSLATED_PREFIX + language),
        )
    }
}

/**
 * The citations of [grounded] that point at a line of [evidence] found in another language (#902),
 * by its language's code, each language and each citation in the order they first come; a citation
 * outside [held], when given, is no line's ([citeKind]).
 */
internal fun translatedCites(
    grounded: GroundedText,
    evidence: EvidenceSet?,
    held: Set<String>? = null,
): List<Pair<String, List<String>>> {
    if (evidence == null) return emptyList()
    return grounded.paragraphs
        .flatten()
        .flatMap { it.cites }
        .distinct()
        .mapNotNull { id ->
            evidence[id]?.takeIf { held == null || id in held }?.language?.let { it to id }
        }.groupBy({ it.first }, { it.second })
        .toList()
}

/** The test tag of the line on what an insight translated (#902), before the language's code. */
const val TRANSLATED_PREFIX: String = "translated:"

/**
 * The grounding check's counts in full, as the simulator gives them (*Show grounding details*).
 * Not whether the insight was kept from before: its footer's first line says that (#671).
 */
@Composable
private fun Meta(
    grounded: GroundedText,
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
