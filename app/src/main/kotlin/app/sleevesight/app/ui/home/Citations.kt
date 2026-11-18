package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
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
// than its bare id, and an uncited sentence said to be so, as the dimmed text shows it), with the
// lines its chips scroll to, in order, each once, at most [MAX_LINE_ACTIONS].
private class ProseText(
    val text: AnnotatedString,
    val spoken: String,
    val lines: List<String>,
    val label: (String) -> String = { it },
    // The way to the note on its dimmed text, when it has any (#954).
    val note: NoteFocus? = null,
)

/**
 * A checked paragraph's sentences that are dimmed: uncited, citing a line that does not exist, or
 * citing lines that do not back them (#1036).
 */
internal val DIMMED_STATUSES: Set<SentenceStatus> =
    setOf(SentenceStatus.UNCITED, SentenceStatus.UNKNOWN, SentenceStatus.UNSUPPORTED)

/**
 * A checked paragraph of [grounded]: a sentence uncited, citing a line that does not exist, or citing
 * lines that do not back it ([SentenceStatus.UNSUPPORTED], #1036), is dimmed: upright, in the text's
 * colour at 60 % ([dimmedText], #954; in italics until then, #819), its titles in italics as anywhere.
 * After it, after its own chips, a `?` chip ([CiteStyles.unbacked]; one for a run of dimmed sentences,
 * after the last, [runEnds]) goes to the note under the reply
 * ([UnbackedNote], by [LocalNoteFocus]), as a citation's chip goes to its line. An unsupported
 * sentence's line chips are a dim outline with no fill
 * ([CiteStyles.unsupported]), and still open their lines; a screen reader hears each as "W2: does not
 * say this", and the sentence as "(not backed by its source)" before its final stop, as an uncited
 * one's "(not cited)", a `?` chip then as "(Not backed, see the note)", which the
 * paragraph also offers as an action. Its citations are read as the check read them,
 * against the card's own evidence and with the model's own knowledge as it was allowed when the
 * card was written ([GroundedText.allowModel]), not as the settings now allow it: a card kept stays
 * as it was checked.
 * A line outside [held], the ids its prompt held when it left some out (#239), reads as no line.
 * A citation shows, and is read, by [label] of its id: the id *Sources* shows its line under, where
 * that differs (the song's, the artist's and the album's insights, #522, cite their own evidence's
 * ids); its own id by default.
 */
@Suppress("LongParameterList") // The paragraph, what it is read against, and how its citations show.
@Composable
internal fun CheckedProse(
    paragraph: List<CheckedSentence>,
    grounded: GroundedText,
    evidence: EvidenceSet?,
    onCite: (String) -> Unit,
    held: Set<String>? = null,
    label: (String) -> String = { it },
) {
    val styles = citeStyles()
    val resources = LocalResources.current
    val flagged = dimmedText()
    val note = LocalNoteFocus.current
    val policy = CitePolicy(evidence, grounded.allowModel, held, label)
    val sentences = paragraph.map { it.text }
    // A sentence of Markdown alone (a rule, a heading's `#`) shows as nothing, and is not read out;
    // a line break before it passes to the sentence shown next (#574). Each shown sentence with its
    // runs and whether it starts a line.
    val shown =
        remember(paragraph) {
            var newLine = false
            paragraph.zip(shown(sentences, checked = true)).mapNotNull { (sentence, runs) ->
                newLine = newLine || sentence.afterLineBreak
                if (runs.isBlank()) null else Triple(sentence, runs, newLine).also { newLine = false }
            }
        }
    if (shown.isEmpty()) return
    // Only the last of a run of dimmed sentences ends with a `?` chip (#954): a sentence not dimmed,
    // the model's own knowledge's among them, or the paragraph's end, ends a run.
    val marked = runEnds(shown.map { (sentence, _, _) -> sentence.status in DIMMED_STATUSES })
    val notes = SpokenNotes(resources)
    val said =
        shown.withIndex().joinToString(" ") { (i, sentenceRuns) ->
            val (sentence, runs, _) = sentenceRuns
            val words = spoken(runs, policy, resources, sentence.status == SentenceStatus.UNSUPPORTED)
            notes.said(words, sentence.status, chip = marked[i])
        }
    val text =
        buildAnnotatedString {
            shown.forEachIndexed { i, (sentence, runs, newLine) ->
                // A sentence the reply began on a new line (a list's item, a heading) shows on one (#574).
                if (i > 0) append(if (newLine) '\n' else ' ')
                val doubtful = sentence.status in DIMMED_STATUSES
                val unsupported = sentence.status == SentenceStatus.UNSUPPORTED
                if (marked[i]) {
                    // The `?` chip after the sentence's own chips, before its final stop (#954).
                    val (before, after) = unbackedAt(runs)
                    appendSentence(before, policy, styles, onCite, flagged, unsupported)
                    appendUnbacked(before, styles, note)
                    appendSentence(after, policy, styles, onCite, flagged, unsupported)
                } else {
                    appendSentence(runs, policy, styles, onCite, flagged.takeIf { doubtful }, unsupported)
                }
            }
        }
    val dimmed = shown.any { (sentence, _, _) -> sentence.status in DIMMED_STATUSES }
    Prose(ProseText(text, said, lines(sentences, policy), label, note.takeIf { dimmed }), onCite)
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
    label: (String) -> String = { it },
) {
    val styles = citeStyles()
    val policy = CitePolicy(context.track?.evidence, context.modelAllowed, held, label)
    val shown = remember(paragraph) { shown(listOf(paragraph)).single() }
    if (shown.isBlank()) return
    val text = buildAnnotatedString { appendSentence(shown, policy, styles, onCite, null) }
    Prose(
        ProseText(text, spoken(shown, policy, LocalResources.current), lines(listOf(paragraph), policy), label),
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
                // Each line's, then the note's on the dimmed text (#954).
                val note =
                    prose.note?.let { focus ->
                        CustomAccessibilityAction(resources.getString(R.string.home_cite_see_note)) {
                            focus.go()
                            true
                        }
                    }
                val actions =
                    prose.lines.map { id ->
                        CustomAccessibilityAction(resources.getString(R.string.home_cite, prose.label(id))) {
                            onCite(id)
                            true
                        }
                    } + listOfNotNull(note)
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
                                resources.getString(cite, policy.label(segment.id))
                            }

                            CiteKind.MODEL -> resources.getString(R.string.home_cite_model)
                            CiteKind.UNKNOWN -> resources.getString(R.string.home_cite_unknown, segment.id)
                        }
                    " ($said) "
                }
            }
        }.replace(SPACES, " ")
        .replace(SPACE_BEFORE_STOP, "$1")
        .trim()

// The lines [sentences] cite that a chip scrolls to, in order, each once, at most
// [MAX_LINE_ACTIONS]: each becomes an accessibility action of the paragraph.
private fun lines(
    sentences: List<String>,
    policy: CitePolicy,
): List<String> =
    sentences
        .flatMap(::segments)
        .filterIsInstance<Segment.Cite>()
        .map { it.id }
        .filter { citeKind(it, policy.evidence, policy.modelAllowed, policy.held) == CiteKind.LINE }
        .distinct()
        .take(MAX_LINE_ACTIONS)

// What a citation is read against: the evidence, whether the model's own knowledge may be cited, and
// the ids the prompt held when it left some out (#239); and how a line's citation is labelled (#522).
private class CitePolicy(
    val evidence: EvidenceSet?,
    val modelAllowed: Boolean,
    val held: Set<String>? = null,
    val label: (String) -> String = { it },
)

/**
 * The citation chips' looks: a line's (a link), the model's knowledge's, a missing line's, a line's
 * that does not back its sentence ([unsupported], #1036: no fill, in the muted text colour, the
 * paragraph drawing its outline), and the `?` chip after a dimmed sentence ([unbacked], #954: on grey,
 * the text colour at a low opacity, in the muted text colour).
 */
internal class CiteStyles(
    val line: SpanStyle,
    val model: SpanStyle,
    val unknown: SpanStyle,
    val unsupported: SpanStyle = line,
    val unbacked: SpanStyle = line,
)

/** The citation chips' looks in the theme's colours, as the insight shows them. */
@Composable
internal fun citeStyles(): CiteStyles {
    val colors = MaterialTheme.colorScheme

    fun chip(
        background: Color,
        foreground: Color,
    ) = SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Medium, fontSize = CITE_SIZE)
    return CiteStyles(
        line = chip(colors.secondaryContainer, colors.onSecondaryContainer),
        model = chip(colors.tertiaryContainer, colors.onTertiaryContainer),
        unknown = chip(colors.errorContainer, colors.onErrorContainer),
        unsupported = chip(Color.Unspecified, colors.onSurfaceVariant),
        unbacked = chip(colors.onSurface.copy(alpha = UNBACKED_GREY), colors.onSurfaceVariant),
    )
}

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
    for (segment in runs) {
        when (segment) {
            is ShownRun.Text -> appendText(segment, flagged)
            is ShownRun.Cite -> {
                val kind = citeKind(segment.id, policy.evidence, policy.modelAllowed, policy.held)
                val shownAs = if (kind == CiteKind.LINE) policy.label(segment.id) else segment.id
                appendCite(segment.id, shownAs, kind, styles, onCite, unsupported)
            }
        }
    }
}

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

private val CITE_SIZE = 0.8.em
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
