package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.sleevesight.app.R
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.GroundedText
import app.sleevesight.core.synthesis.SentenceStatus

// The insight's prose as text, with its citations as chips: plain text only (architecture,
// *Synthesis and grounding*), its one interactive part the chips the app builds from the evidence
// ids it assigned. Markdown the model writes anyway is cleaned, its titles set in italics and its
// bold in bold ([shown], #555); the screen reader hears the text without the marks.

// A paragraph of the insight as shown (its citations as chips) and as a screen reader reads it
// (each chip by what it points at, `home_cite`, `home_cite_model` or `home_cite_unknown`, rather
// than its bare id, and a run of sentences citing nothing said to be so, as its `?` chip shows it),
// with the lines its chips scroll to, in order, each once, at most [MAX_LINE_ACTIONS]; and the way to
// the note on its dimmed text, when it has any ([note], #954, #1437).
private class ProseText(
    val text: AnnotatedString,
    val spoken: String,
    val lines: List<String>,
    val label: (String) -> String? = { it },
    val note: ToNote? = null,
)

// The way to the note ([focus], #954) from a paragraph's outlined chips, of the lines [unsupported]
// (#1437), and from its `?` chips, when it has any ([uncited]).
private class ToNote(
    val focus: NoteFocus,
    val unsupported: List<String>,
    val uncited: Boolean,
)

// A checked paragraph's sentences as shown ([shown]: each with its runs, those of a dimmed one with its
// chips only at its run's end, #1437, and whether it starts a line), which end with their run's mark
// ([marked]), and how its citations read and show ([policy], [styles]).
private class Marked(
    val shown: List<Triple<CheckedSentence, List<ShownRun>, Boolean>>,
    val marked: List<Boolean>,
    val policy: CitePolicy,
    val styles: CiteStyles,
)

/**
 * A checked paragraph of [grounded] (#1437, after #954 and #1036). A sentence citing a line that does
 * not exist is taken out, nothing said of it ([takenOut]). A sentence uncited, or citing lines that do
 * not back it ([SentenceStatus.UNSUPPORTED]), is dimmed: upright, in the text's colour at 60 %
 * ([dimmedText]), its titles in italics as anywhere. A run of dimmed sentences with the same backing
 * ([backing]: all citing nothing, or all citing the same lines) shows one mark, after its last sentence
 * ([runEnds]), never both on one sentence: citing nothing, a `?` chip ([CiteStyles.unbacked]) before
 * its final stop; citing lines, those lines' chips as a dim outline with no fill
 * ([CiteStyles.unsupported]), the other sentences of the run showing none. Both go to the note under
 * the reply ([UnbackedNote], by [LocalNoteFocus]); with no note to go to, an outlined chip opens its
 * line. A screen reader hears a `?` chip as "(not cited)" and an outlined chip as "W2: does not say
 * this", and has each as an action of the paragraph: "Not cited, see the note", "W2: does not say
 * this, see the note". Its citations are read as the check read them,
 * against the card's own evidence and with the model's own knowledge as it was allowed when the
 * card was written ([GroundedText.allowModel]), not as the settings now allow it: a card kept stays
 * as it was checked.
 * A line outside [held], the ids its prompt held when it left some out (#239), reads as no line.
 * A citation shows, and is read, by [label] of its id: the id *Sources* shows its line under, where
 * that differs (the song's, the artist's and the album's insights, #522, cite their own evidence's
 * ids); its own id by default. A line's citation [label] has no id for (#1434: *Sources* lists no line
 * for it) is not shown, read, nor offered as an action at all.
 */
@Suppress("LongParameterList") // The paragraph, what it is read against, and how its citations show.
@Composable
internal fun CheckedProse(
    paragraph: List<CheckedSentence>,
    grounded: GroundedText,
    evidence: EvidenceSet?,
    onCite: (String) -> Unit,
    held: Set<String>? = null,
    label: (String) -> String? = { it },
) {
    val styles = citeStyles()
    val resources = LocalResources.current
    val flagged = dimmedText()
    val note = LocalNoteFocus.current
    val policy = CitePolicy(evidence, grounded.allowModel, held, label)
    // A sentence of Markdown alone (a rule, a heading's `#`) shows as nothing, and is not read out,
    // nor is one taken out (#1437); a line break before it passes to the sentence shown next (#574).
    // Each shown sentence with its runs and whether it starts a line.
    val shown =
        remember(paragraph, evidence, held) {
            var newLine = false
            paragraph.zip(shown(paragraph.map { it.text }, checked = true)).mapNotNull { (sentence, runs) ->
                newLine = newLine || sentence.afterLineBreak
                val out = runs.isBlank() || takenOut(sentence, runs, evidence, grounded.allowModel, held)
                if (out) null else Triple(sentence, runs, newLine).also { newLine = false }
            }
        }
    if (shown.isEmpty()) return
    // The sentences that end with their run's mark (#1437): its `?` chip, or its outlined chips; a
    // dimmed one's chips show only at its run's end.
    val ends = runEnds(shown.map { (sentence, _, _) -> backing(sentence) })
    val marked =
        Marked(
            shown.mapIndexed { i, (sentence, runs, newLine) ->
                val shownRuns =
                    if (sentence.status == SentenceStatus.UNSUPPORTED &&
                        !ends[i]
                    ) {
                        withoutChips(runs)
                    } else {
                        runs
                    }
                Triple(sentence, shownRuns, newLine)
            },
            ends,
            policy,
            styles,
        )
    val dimmed = shown.any { (sentence, _, _) -> sentence.status in DIMMED_STATUSES }
    val text = markedText(marked, flagged, note.takeIf { dimmed }, onCite)
    Prose(markedProse(marked, text, note.takeIf { dimmed }, resources), onCite)
}

// [marked] as a screen reader hears it, with the lines its chips open and its way to the note
// ([note], #1437): its outlined chips' lines, then whether it has a `?` chip; with no note to go to,
// its outlined chips open their lines.
private fun markedProse(
    marked: Marked,
    text: AnnotatedString,
    note: NoteFocus?,
    resources: Resources,
): ProseText {
    val uncited = resources.getString(R.string.home_cite_none)
    val status = { i: Int -> marked.shown[i].first.status }
    val said =
        marked.shown.indices.joinToString(" ") { i ->
            val unsupported = status(i) == SentenceStatus.UNSUPPORTED
            val words = spoken(marked.shown[i].second, marked.policy, resources, unsupported)
            if (status(i) == SentenceStatus.UNCITED && marked.marked[i]) notCited(words, uncited) else words
        }
    val runsOf = { outlined: Boolean ->
        marked.shown.indices
            .filter { (status(it) == SentenceStatus.UNSUPPORTED) == outlined }
            .flatMap { marked.shown[it].second }
    }
    val backed = lines(runsOf(false), marked.policy)
    val unsupported = lines(runsOf(true), marked.policy).take(MAX_LINE_ACTIONS - 1)
    val toNote =
        note?.let {
            ToNote(
                it,
                unsupported,
                marked.shown.indices.any { status(it) == SentenceStatus.UNCITED },
            )
        }
    val lines = if (toNote == null) (backed + unsupported).distinct() else backed
    return ProseText(text, said, lines, marked.policy.label, toNote)
}

// [marked] as shown: a dimmed sentence in [flagged], one citing nothing at its run's end with a `?`
// chip before its final stop (#954), one citing lines that do not back it with their chips outlined,
// going to the [note] when there is one (#1437); any other chip opening its line ([onCite]).
private fun markedText(
    marked: Marked,
    flagged: SpanStyle,
    note: NoteFocus?,
    onCite: (String) -> Unit,
): AnnotatedString {
    val toNote: (String) -> Unit = note?.let { focus -> { _: String -> focus.go() } } ?: onCite
    val (policy, styles) = marked.policy to marked.styles
    return buildAnnotatedString {
        marked.shown.forEachIndexed { i, (sentence, runs, newLine) ->
            // A sentence the reply began on a new line (a list's item, a heading) shows on one (#574).
            if (i > 0) append(if (newLine) '\n' else ' ')
            when {
                sentence.status == SentenceStatus.UNCITED && marked.marked[i] -> {
                    val (before, after) = unbackedAt(runs)
                    appendSentence(before, policy, styles, onCite, flagged)
                    appendUnbacked(before, styles, note)
                    appendSentence(after, policy, styles, onCite, flagged)
                }

                sentence.status == SentenceStatus.UNSUPPORTED ->
                    appendSentence(runs, policy, styles, toNote, flagged, unsupported = true)

                else ->
                    appendSentence(
                        runs,
                        policy,
                        styles,
                        onCite,
                        flagged.takeIf { sentence.status in DIMMED_STATUSES },
                    )
            }
        }
    }
}

/**
 * A paragraph still being written: its citations show, read as the settings allow them now, under
 * which it is written, and against the ids its prompt [held]; the dimming waits for the check. A
 * citation shows, and is read, by [label] of its id, as in [CheckedProse].
 */
@Composable
internal fun UncheckedProse(
    paragraph: String,
    context: InsightContext,
    onCite: (String) -> Unit,
    held: Set<String>? = null,
    label: (String) -> String? = { it },
) {
    val styles = citeStyles()
    val policy = CitePolicy(context.track?.evidence, context.modelAllowed, held, label)
    val shown = remember(paragraph) { shown(listOf(paragraph)).single() }
    if (shown.isBlank()) return
    val text = buildAnnotatedString { appendSentence(shown, policy, styles, onCite, null) }
    Prose(
        ProseText(text, spoken(shown, policy, LocalResources.current), lines(shown, policy), label),
        onCite,
    )
}

// A paragraph with citation chips, read by a screen reader with each chip said in words, and each
// line a chip scrolls to offered as an action too: a description read in place of the text may hide
// the links in it. An unsupported sentence's chips are outlined (#1036), drawn behind the text from
// the layout the same frame measured, as `MarkedText` draws a link's mark.
@Composable
private fun Prose(
    prose: ProseText,
    onCite: (String) -> Unit,
) {
    val resources = LocalResources.current
    val outlined = remember(prose.text) { outlinedChips(prose.text) }
    val shown = remember(prose.text) { withoutIsolates(prose.text) }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        prose.text,
        style = citedTextStyle(MaterialTheme.typography.bodyLarge),
        onTextLayout = { layout.value = it },
        modifier =
            Modifier.drawChipOutlines(outlined, layout, ink).semantics {
                text = shown
                contentDescription = prose.spoken
                // Each line's, then the outlined chips' and the `?` chips', which go to the note (#1437).
                val toNote =
                    prose.note
                        ?.let { toNote ->
                            val focus = toNote.focus
                            toNote.unsupported.mapNotNull { id -> prose.label(id) }.map { shownAs ->
                                val said = resources.getString(R.string.home_cite_unsupported_see_note, shownAs)
                                CustomAccessibilityAction(said) {
                                    focus.go()
                                    true
                                }
                            } +
                                listOfNotNull(
                                    CustomAccessibilityAction(resources.getString(R.string.home_cite_see_note)) {
                                        focus.go()
                                        true
                                    }.takeIf { toNote.uncited },
                                )
                        }.orEmpty()
                val actions =
                    prose.lines.mapNotNull { id -> prose.label(id)?.let { id to it } }.map { (id, shownAs) ->
                        CustomAccessibilityAction(resources.getString(R.string.home_cite, shownAs)) {
                            onCite(id)
                            true
                        }
                    } + toNote
                if (actions.isNotEmpty()) customActions = actions
            },
    )
}

// A sentence's [runs] as a screen reader reads them: the text without its Markdown, each citation,
// in brackets, by what it points at.
private fun spoken(
    runs: List<ShownRun>,
    policy: CitePolicy,
    resources: Resources,
    unsupported: Boolean = false,
): String =
    runs
        .joinToString("") { segment ->
            when (segment) {
                is ShownRun.Text -> segment.text
                is ShownRun.Cite -> {
                    val said =
                        when (citeKind(segment.id, policy.evidence, policy.modelAllowed, policy.held)) {
                            CiteKind.LINE -> {
                                val cite = if (unsupported) R.string.home_cite_unsupported else R.string.home_cite
                                policy.label(segment.id)?.let { resources.getString(cite, it) }
                            }

                            CiteKind.MODEL -> resources.getString(R.string.home_cite_model)
                            CiteKind.UNKNOWN -> resources.getString(R.string.home_cite_unknown, segment.id)
                        }
                    said?.let { " ($it) " } ?: " "
                }
            }
        }.replace(SPACES, " ")
        .replace(SPACE_BEFORE_STOP, "$1")
        .trim()

// The lines [runs] cite that a chip scrolls to, in order, each once, at most [MAX_LINE_ACTIONS]: each
// becomes an accessibility action of the paragraph.
private fun lines(
    runs: List<ShownRun>,
    policy: CitePolicy,
): List<String> =
    runs
        .filterIsInstance<ShownRun.Cite>()
        .map { it.id }
        .filter { citeKind(it, policy.evidence, policy.modelAllowed, policy.held) == CiteKind.LINE }
        .filter { policy.label(it) != null }
        .distinct()
        .take(MAX_LINE_ACTIONS)

// What a citation is read against: the evidence, whether the model's own knowledge may be cited, and
// the ids the prompt held when it left some out (#239); and how a line's citation is labelled (#522).
private class CitePolicy(
    val evidence: EvidenceSet?,
    val modelAllowed: Boolean,
    val held: Set<String>? = null,
    val label: (String) -> String? = { it },
)

// A sentence's [runs], its titles in italics (#555), dimmed when [flagged], its line chips outlined
// when its lines do not back it ([unsupported], #1036).
@Suppress("LongParameterList") // The sentence, how its citations read and show, and how it is marked.
private fun AnnotatedString.Builder.appendSentence(
    runs: List<ShownRun>,
    policy: CitePolicy,
    styles: CiteStyles,
    onCite: (String) -> Unit,
    flagged: SpanStyle?,
    unsupported: Boolean = false,
) {
    // A line's citation with no label (#1434) is not shown at all.
    val unshown = { id: String ->
        citeKind(id, policy.evidence, policy.modelAllowed, policy.held) == CiteKind.LINE && policy.label(id) == null
    }
    for (segment in withoutCites(runs, unshown)) {
        when (segment) {
            is ShownRun.Text -> appendText(segment, flagged)
            is ShownRun.Cite -> {
                val kind = citeKind(segment.id, policy.evidence, policy.modelAllowed, policy.held)
                val shownAs = if (kind == CiteKind.LINE) policy.label(segment.id) else segment.id
                shownAs?.let { appendCite(segment.id, it, kind, styles, onCite, unsupported) }
            }
        }
    }
}

/**
 * The `?` chip after a dimmed sentence's [before] runs (#954), kept on the line of the word or chip
 * before it ([CHIP_GLUE]: a word joiner, then a no-break space unless they end in a space), so it
 * never wraps onto a line alone. On a tap, it goes to the note under the reply ([NoteFocus.go]); with
 * no [note] to go to, it only shows.
 */
internal fun AnnotatedString.Builder.appendUnbacked(
    before: List<ShownRun>,
    styles: CiteStyles,
    note: NoteFocus?,
) {
    val spaced = (before.lastOrNull() as? ShownRun.Text)?.text?.lastOrNull()?.isWhitespace() == true
    if (before.isNotEmpty()) append(if (spaced) WORD_JOINER else CHIP_GLUE)
    val label = UNBACKED_LABEL
    if (note == null) {
        withStyle(styles.unbacked) { append(label) }
    } else {
        val link = LinkAnnotation.Clickable(UNBACKED_LINK_TAG, TextLinkStyles(styles.unbacked)) { note.go() }
        withLink(link) { append(label) }
    }
}

/**
 * A citation as a chip, shown as [shownAs]: a line's scrolls to it ([onCite] with [id]), with no
 * fill and an outline the paragraph draws ([OUTLINE_TAG]) when it does not back its sentence
 * ([unsupported], #1036); the model's and a missing line's only say what they are. Also *Explain the
 * song*'s (#908), whose chip of a page opens it.
 */
@Suppress("LongParameterList") // The citation, how it shows, and what a tap does.
internal fun AnnotatedString.Builder.appendCite(
    id: String,
    shownAs: String,
    kind: CiteKind,
    styles: CiteStyles,
    onCite: (String) -> Unit,
    unsupported: Boolean = false,
) {
    val label = citeLabel(shownAs)
    when (kind) {
        CiteKind.LINE -> {
            val style = if (unsupported) styles.unsupported else styles.line
            val link = LinkAnnotation.Clickable(CITE_PREFIX + id, TextLinkStyles(style)) { onCite(id) }
            if (unsupported) pushStringAnnotation(OUTLINE_TAG, id)
            withLink(link) { append(label) }
            if (unsupported) pop()
        }

        CiteKind.MODEL -> withStyle(styles.model) { append(label) }
        CiteKind.UNKNOWN -> withStyle(styles.unknown) { append(label) }
    }
}

/** The prefix of a citation chip's link tag, before the evidence id. */
const val CITE_PREFIX: String = "cite:"

/** The tag of the annotation over a chip drawn as an outline (#1036), its evidence id the item. */
const val OUTLINE_TAG: String = "outline"

// No break before the `?` chip (#954): a word joiner, which holds even after a citation chip's thin
// space, where a line may break before a no-break space; then a no-break space, the space shown.
private const val WORD_JOINER = "\u2060"
private const val CHIP_GLUE = "$WORD_JOINER\u00A0"

/** The link tag of the `?` chip after a dimmed sentence (#954), which goes to the note under the reply. */
const val UNBACKED_LINK_TAG: String = "unbacked"

private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?])")

// The closing quotes a sentence's final punctuation may carry after it.
internal const val QUOTES = "\"'”’"

// A sentence's closing punctuation, with any closing quotes or brackets after it (the group).
internal val FINAL_PUNCTUATION = Regex("[.!?…]+([$QUOTES)\\]]*)$")

// The accessibility actions a paragraph offers for its lines. Compose (1.12.1) throws when one
// node has 32 or more custom actions ("Can't have more than 32 custom actions for one widget",
// `AndroidComposeViewAccessibilityDelegateCompat`), and a menu that long is hard to use anyway.
// Lines past these are reached under *Sources*, which lists every line: their chips' links may not
// reach a screen reader, which reads the paragraph's content description in place of its text.
private const val MAX_LINE_ACTIONS = 16
