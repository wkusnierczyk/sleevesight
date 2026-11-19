package app.sleevesight.app.ui.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus

// Which sentences of a checked reply show, which are dimmed, which end with their run's mark, and
// what the note under the reply says of them (#1437, after #954 and #1036).

/**
 * A checked paragraph's sentences that are dimmed: uncited, or citing lines that do not back them
 * (#1036). One citing a line that does not exist is taken out ([takenOut], #1437).
 */
internal val DIMMED_STATUSES: Set<SentenceStatus> = setOf(SentenceStatus.UNCITED, SentenceStatus.UNSUPPORTED)

/**
 * Whether [sentence] ([runs] as shown) is taken out of the reply, nothing said of it (#1437): it
 * cites a line that does not exist ([SentenceStatus.UNKNOWN]), or, read against [evidence] when there
 * is any, a line [citeKind] finds none (outside the ids the prompt [held], #239, or `AI` while the
 * model's own knowledge was not allowed, [modelAllowed]).
 */
internal fun takenOut(
    sentence: CheckedSentence,
    runs: List<ShownRun>,
    evidence: EvidenceSet?,
    modelAllowed: Boolean,
    held: Set<String>? = null,
): Boolean =
    sentence.status == SentenceStatus.UNKNOWN ||
        evidence != null &&
        runs.any { it is ShownRun.Cite && citeKind(it.id, evidence, modelAllowed, held) == CiteKind.UNKNOWN }

/**
 * What a dimmed [sentence] is backed by, the same for each sentence of a run that shows one mark
 * (#1437): [UNCITED_RUN] for one citing nothing, the set of its citations for one citing lines that
 * do not back it, so `[S1][S2]` and `[S2][S1]`, or `[S1][S1]` and `[S1]`, are one backing; null for a
 * sentence not dimmed.
 */
internal fun backing(sentence: CheckedSentence): Any? =
    when (sentence.status) {
        SentenceStatus.UNCITED -> UNCITED_RUN
        SentenceStatus.UNSUPPORTED -> sentence.cites.toSet()
        else -> null
    }

/** The backing of a dimmed sentence that cites nothing ([backing]). */
internal val UNCITED_RUN: Any = Any()

/**
 * Which of a paragraph's sentences, by their [backings] ([backing]), end with their run's mark
 * (#1437): the last of each run of dimmed sentences with the same backing (all citing nothing, the
 * `?` chip; all citing the same lines, their outlined chips), a sentence not dimmed, one with another
 * backing, or the paragraph's end ending a run.
 */
internal fun runEnds(backings: List<Any?>): List<Boolean> =
    backings.mapIndexed { i, backing -> backing != null && backings.getOrNull(i + 1) != backing }

/**
 * [runs] without their citations (#1437): a sentence of a run of dimmed ones citing the same lines,
 * but the last, which shows them. A space left before a stop, or at the end, goes with its citation.
 */
internal fun withoutChips(runs: List<ShownRun>): List<ShownRun> {
    val kept = mutableListOf<ShownRun>()
    for ((i, run) in runs.withIndex()) {
        val before = kept.lastOrNull() as? ShownRun.Text
        when {
            run is ShownRun.Text -> kept += run
            before != null && closes(runs.drop(i + 1)) ->
                kept[kept.lastIndex] =
                    before.copy(text = before.text.trimEnd())
        }
    }
    return kept.filterNot { it is ShownRun.Text && it.text.isEmpty() }
}

// Whether a citation followed by [after] closes its text: nothing follows, or a space or a closer.
private fun closes(after: List<ShownRun>): Boolean {
    val next = after.firstOrNull { it is ShownRun.Text } as? ShownRun.Text
    return next == null || next.text.firstOrNull()?.let { it.isWhitespace() || it in CLOSERS } != false
}

// What may follow a citation with no space before it: a stop, a closing quote or bracket.
private const val CLOSERS = ".,;:!?…\"'”’)]"

/**
 * What the note under a reply says of its dimmed text (#1437): the lines cited by sentences they do
 * not back ([lines], each once, as *Sources* shows it, sorted by [sortedIds]) and whether any
 * sentence cites nothing ([uncited]).
 */
internal data class Unbacked(
    val lines: List<String>,
    val uncited: Boolean,
)

/**
 * Where a reply's citations point, for its note's chips (#1437): its [evidence], the ids its prompt
 * [held] (#239), each line as *Sources* shows it ([label]; one it names nothing, #1434, left out),
 * and what a tap on one does ([onCite], given the id [label] shows).
 */
internal class NoteCites(
    val evidence: EvidenceSet?,
    val held: Set<String>? = null,
    val label: (String) -> String? = { it },
    val onCite: (String) -> Unit = {},
)

/**
 * What the note under [grounded] says ([Unbacked]), its sentences read against [evidence] and [held]
 * (#239), those taken out ([takenOut]) and those that show as nothing left out; null with no dimmed
 * text. Its lines by their own ids, each once, in the order they first come, not yet as *Sources*
 * shows them ([shownAs]): *Sources* may number them anew while the reply and its evidence stay as they
 * are (#1437), so a caller keeping this names them each time it draws the note.
 */
internal fun unbackedIds(
    grounded: GroundedText,
    evidence: EvidenceSet?,
    held: Set<String>?,
): Unbacked? {
    val shown =
        grounded.paragraphs.flatMap { paragraph ->
            paragraph.zip(shown(paragraph.map { it.text }, checked = true)).filter { (sentence, runs) ->
                !runs.isBlank() && !takenOut(sentence, runs, evidence, grounded.allowModel, held)
            }
        }
    val lines =
        shown
            .filter { (sentence, _) -> sentence.status == SentenceStatus.UNSUPPORTED }
            .flatMap { (_, runs) -> runs.filterIsInstance<ShownRun.Cite>().map { it.id } }
            .filter { citeKind(it, evidence, grounded.allowModel, held) == CiteKind.LINE }
            .distinct()
    val uncited = shown.any { (sentence, _) -> sentence.status == SentenceStatus.UNCITED }
    return if (lines.isEmpty() && !uncited) null else Unbacked(lines, uncited)
}

/**
 * These lines as *Sources* shows them ([label]; one it names nothing, #1434, left out), each once,
 * sorted ([sortedIds]); null when none is left and nothing is uncited.
 */
internal fun Unbacked.shownAs(label: (String) -> String?): Unbacked? {
    val shown = lines.mapNotNull(label).distinct()
    return if (shown.isEmpty() && !uncited) null else Unbacked(sortedIds(shown), uncited)
}

/** [ids] ("S8", "W2", "S10") in order of their letters, then their number: "S8", "S10", "W2". */
internal fun sortedIds(ids: List<String>): List<String> =
    ids.sortedWith(
        compareBy<String>({ it.takeWhile { c -> !c.isDigit() } }, { idNumber(it) }, { it }),
    )

// The number of an id after its letters, 0 for none.
private fun idNumber(id: String): Long =
    id
        .dropWhile { !it.isDigit() }
        .takeWhile { it.isDigit() }
        .take(MAX_DIGITS)
        .toLongOrNull() ?: 0

private const val MAX_DIGITS = 18

/**
 * A run of a sentence's [text], its title in italics and its bold in bold (#555), in [flagged] when
 * the sentence is dimmed ([dimmedText], upright, so a title in it stays in italics, #954). Also
 * *Explain the song*'s (#908).
 */
internal fun AnnotatedString.Builder.appendText(
    text: ShownRun.Text,
    flagged: SpanStyle?,
) {
    val marked =
        SpanStyle(
            fontStyle = if (text.italic) FontStyle.Italic else null,
            fontWeight = if (text.bold) FontWeight.Bold else null,
        )
    val style = flagged?.merge(marked) ?: marked
    if (style == SpanStyle()) append(text.text) else withStyle(style) { append(text.text) }
}
