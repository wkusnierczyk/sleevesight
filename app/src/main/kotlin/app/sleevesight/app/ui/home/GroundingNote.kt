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
 * The grounding line under a written insight or a genre's reason: a note only when there is
 * something to say ([UnbackedNote]: the one the dimmed sentences' marks go to, #954, #1437, its lines'
 * chips read as [cites] reads them), and under it, with
 * [details] (*Show grounding details*, a developer option, #437), the grounding check's counts in
 * full ([Meta]). Whether it was kept from before is not its to say:
 * the insight's credit says who wrote it (#1162), a genre's reason its own line
 * (`KeptLine`, #851).
 * [modifier] goes to the note. With no note, nothing is drawn, and the modifier with it: a
 * caller that sets the line in a row beside other content fills the space itself, so that content
 * keeps its place (a weighted `Box` around the line, a `Spacer(Modifier.weight(1f))`, or
 * `Arrangement.End`).
 */
@Composable
internal fun GroundingLine(
    grounded: GroundedText,
    details: Boolean,
    cites: NoteCites,
    modifier: Modifier = Modifier,
) {
    // Shown only when there is something to say (#437): text the sources do not back, which is
    // dimmed. A sentence from the model's own knowledge is not counted: it is not dimmed, and its own
    // chip says where it is from; nor is one taken out, or one that shows as nothing (#555).
    val unbacked = remember(grounded, cites.evidence, cites.held) { unbacked(grounded, cites) }
    unbacked?.let { UnbackedNote(it, modifier, cites.onCite) }
    // The full counts show only in developer mode, and are marked as such (#536); the note stays (#954).
    if (details) DeveloperMark { Meta(grounded, Modifier) }
}

/**
 * The statuses of a sentence the sources do not back, which reading aloud marks "Unverified:" (#325):
 * the ones dimmed ([DIMMED_STATUSES], which the note names, citing lines that do not back them, #1036,
 * among them) and the model's own knowledge. One citing a line that does not exist is not read at all
 * (#1437), as the card takes it out.
 */
internal val UNBACKED = DIMMED_STATUSES + SentenceStatus.MODEL

/**
 * Under an insight, a song's, an artist's or an album's insight or a genre's reason that cites lines
 * found in the track's language (#902, `EvidenceLine.language`), after the grounding note: which
 * citations it translated, *Translated from Hindi: W2 and S1.* ([R.string.home_note_translated]), one
 * line per language, the citations joined as *Sources*' lists are ([listText]). The citations are
 * those of [grounded] that point at a line of [evidence], in the order they first come, a citation
 * outside [held] (the ids its prompt held, when it left some out, #239) not one, as its chip shows
 * it; the chips themselves stay as they are. Each citation is named by [label], as its chip names it:
 * the id *Sources* shows its line under, for the song's, the artist's and the album's insights,
 * whose own ids may differ (`shownAs`). Nothing is drawn when none is in another language.
 */
@Suppress("SpreadOperator") // A handful of arguments, passed straight through.
@Composable
internal fun TranslatedLine(
    grounded: GroundedText,
    evidence: EvidenceSet?,
    held: Set<String>? = null,
    label: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    val translated = remember(grounded, evidence, held) { translatedCites(grounded, evidence, held) }
    if (translated.isEmpty()) return
    val resources = LocalResources.current
    val words = Words { id, args -> resources.getString(id, *args) }
    for ((language, ids) in translated) {
        // The language is read from the line itself, then the citation named as its chip names it.
        val shown = ids.map(label).distinct()
        MetaLine(
            stringResource(R.string.home_note_translated, languageName(language), listText(shown, words)),
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
            if (counts.unsupported > 0) add(unsupported(grounded))
            if (counts.unknown > 0) {
                add(pluralStringResource(R.plurals.home_meta_unknown, counts.unknown, counts.unknown))
            }
            if (grounded.withdrawn != null) add(stringResource(R.string.home_meta_self_corrected))
        }
    MetaLine(parts.joinToString(" · "), modifier)
}

/**
 * The sentences whose lines do not back them (#1036), with the lines they cite: "1 unsupported (W2)".
 */
@Composable
private fun unsupported(grounded: GroundedText): String {
    val sentences = grounded.paragraphs.flatten().filter { it.status == SentenceStatus.UNSUPPORTED }
    val cites = sentences.flatMap { it.cites }.distinct().joinToString(", ")
    return stringResource(R.string.home_meta_unsupported, sentences.size, cites)
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
