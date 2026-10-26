package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
// than its bare id, and an uncited sentence said to be so, as the underline shows it), with the
// lines its chips scroll to, in order, each once, at most [MAX_LINE_ACTIONS].
private class ProseText(
    val text: AnnotatedString,
    val spoken: String,
    val lines: List<String>,
)

/** The paragraphs of text not checked yet, split as the grounding check splits them: on a blank line. */
internal fun paragraphsOf(text: String): List<String> =
    text.split(BLANK_LINE).map(String::trim).filter(String::isNotEmpty)

/**
 * A checked paragraph of [grounded]: a sentence uncited, or citing a line that does not exist, is
 * underlined. Its citations are read as the check read them, against the card's own evidence and
 * with the model's own knowledge as it was allowed when the card was written
 * ([GroundedText.allowModel]), not as the settings now allow it: a card kept stays as it was checked.
 * A line outside [held], the ids its prompt held when it left some out (#239), reads as no line.
 */
@Composable
internal fun CheckedProse(
    paragraph: List<CheckedSentence>,
    grounded: GroundedText,
    evidence: EvidenceSet?,
    onCite: (String) -> Unit,
    held: Set<String>? = null,
) {
    val styles = citeStyles()
    val resources = LocalResources.current
    val flagged = SpanStyle(textDecoration = TextDecoration.Underline, color = MaterialTheme.colorScheme.error)
    val policy = CitePolicy(evidence, grounded.allowModel, held)
    val sentences = paragraph.map { it.text }
    // A sentence of Markdown alone (a rule, a heading's `#`) shows as nothing, and is not read out.
    val shown = remember(paragraph) { paragraph.zip(shown(sentences)).filterNot { (_, runs) -> runs.isBlank() } }
    if (shown.isEmpty()) return
    val uncited = resources.getString(R.string.home_cite_none)
    val said =
        shown.joinToString(" ") { (sentence, runs) ->
            val words = spoken(runs, policy, resources)
            // An uncited sentence is said to be so before its final punctuation, where a citation
            // would be: "It is great (not cited)." A closing quote keeps the note out of the
            // quotation: 'They called it "great." (not cited)'. A closing bracket alone keeps it
            // inside, as a citation would be: "(See the sleeve (not cited).)"; a closing quote among
            // the closers still keeps it out: '(They called it "great.") (not cited)'.
            val end = FINAL_PUNCTUATION.find(words)
            val at = if (end == null || end.groupValues[1].any { it in QUOTES }) words.length else end.range.first
            if (sentence.status == SentenceStatus.UNCITED) "${words.take(at)} ($uncited)${words.drop(at)}" else words
        }
    val text =
        buildAnnotatedString {
            shown.forEachIndexed { i, (sentence, runs) ->
                if (i > 0) append(' ')
                val doubtful = sentence.status == SentenceStatus.UNCITED || sentence.status == SentenceStatus.UNKNOWN
                appendSentence(runs, policy, styles, onCite, if (doubtful) flagged else null)
            }
        }
    Prose(ProseText(text, said, lines(sentences, policy)), onCite)
}

/**
 * A paragraph still being written: its citations show, read as the settings allow them now, under
 * which it is written, and against the ids its prompt [held]; the underlines wait for the check.
 */
@Composable
internal fun UncheckedProse(
    paragraph: String,
    context: InsightContext,
    onCite: (String) -> Unit,
    held: Set<String>? = null,
) {
    val styles = citeStyles()
    val policy = CitePolicy(context.track?.evidence, context.modelAllowed, held)
    val shown = remember(paragraph) { shown(listOf(paragraph)).single() }
    if (shown.isBlank()) return
    val text = buildAnnotatedString { appendSentence(shown, policy, styles, onCite, null) }
    Prose(ProseText(text, spoken(shown, policy, LocalResources.current), lines(listOf(paragraph), policy)), onCite)
}

// A paragraph with citation chips, read by a screen reader with each chip said in words, and each
// line a chip scrolls to offered as an action too: a description read in place of the text may hide
// the links in it.
@Composable
private fun Prose(
    prose: ProseText,
    onCite: (String) -> Unit,
) {
    val resources = LocalResources.current
    Text(
        prose.text,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier.semantics {
                contentDescription = prose.spoken
                if (prose.lines.isNotEmpty()) {
                    customActions =
                        prose.lines.map { id ->
                            CustomAccessibilityAction(resources.getString(R.string.home_cite, id)) {
                                onCite(id)
                                true
                            }
                        }
                }
            },
    )
}

// A sentence's [runs] as a screen reader reads them: the text without its Markdown, each citation,
// in brackets, by what it points at.
private fun spoken(
    runs: List<ShownRun>,
    policy: CitePolicy,
    resources: Resources,
): String =
    runs
        .joinToString("") { segment ->
            when (segment) {
                is ShownRun.Text -> segment.text
                is ShownRun.Cite -> {
                    val said =
                        when (citeKind(segment.id, policy.evidence, policy.modelAllowed, policy.held)) {
                            CiteKind.LINE -> resources.getString(R.string.home_cite, segment.id)
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
// the ids the prompt held when it left some out (#239).
private class CitePolicy(
    val evidence: EvidenceSet?,
    val modelAllowed: Boolean,
    val held: Set<String>? = null,
)

private class CiteStyles(
    val line: SpanStyle,
    val model: SpanStyle,
    val unknown: SpanStyle,
)

@Composable
private fun citeStyles(): CiteStyles {
    val colors = MaterialTheme.colorScheme

    fun chip(
        background: Color,
        foreground: Color,
    ) = SpanStyle(background = background, color = foreground, fontWeight = FontWeight.Medium, fontSize = CITE_SIZE)
    return CiteStyles(
        line = chip(colors.secondaryContainer, colors.onSecondaryContainer),
        model = chip(colors.tertiaryContainer, colors.onTertiaryContainer),
        unknown = chip(colors.errorContainer, colors.onErrorContainer),
    )
}

// A sentence's [runs], its titles in italics (#555), underlined when [flagged].
private fun AnnotatedString.Builder.appendSentence(
    runs: List<ShownRun>,
    policy: CitePolicy,
    styles: CiteStyles,
    onCite: (String) -> Unit,
    flagged: SpanStyle?,
) {
    for (segment in runs) {
        when (segment) {
            is ShownRun.Text -> appendText(segment, flagged)
            is ShownRun.Cite -> {
                val kind = citeKind(segment.id, policy.evidence, policy.modelAllowed, policy.held)
                appendCite(segment.id, kind, styles, onCite)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendText(
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

// A citation as a chip: a line's scrolls to it; the model's and a missing line's only say what they are.
private fun AnnotatedString.Builder.appendCite(
    id: String,
    kind: CiteKind,
    styles: CiteStyles,
    onCite: (String) -> Unit,
) {
    val label = " $id "
    when (kind) {
        CiteKind.LINE -> {
            val link = LinkAnnotation.Clickable(CITE_PREFIX + id, TextLinkStyles(styles.line)) { onCite(id) }
            withLink(link) { append(label) }
        }

        CiteKind.MODEL -> withStyle(styles.model) { append(label) }
        CiteKind.UNKNOWN -> withStyle(styles.unknown) { append(label) }
    }
}

/** The prefix of a citation chip's link tag, before the evidence id. */
const val CITE_PREFIX: String = "cite:"

private val CITE_SIZE = 0.8.em
private val BLANK_LINE = Regex("\\n\\s*\\n")
private val SPACES = Regex("\\s+")
private val SPACE_BEFORE_STOP = Regex(" ([.,;:!?])")

// The closing quotes a sentence's final punctuation may carry after it.
private const val QUOTES = "\"'”’"

// A sentence's closing punctuation, with any closing quotes or brackets after it (the group).
private val FINAL_PUNCTUATION = Regex("[.!?…]+([$QUOTES)\\]]*)$")

// The accessibility actions a paragraph offers for its lines. Compose (1.12.1) throws when one
// node has 32 or more custom actions ("Can't have more than 32 custom actions for one widget",
// `AndroidComposeViewAccessibilityDelegateCompat`), and a menu that long is hard to use anyway.
// Lines past these are reached under *Sources*, which lists every line: their chips' links may not
// reach a screen reader, which reads the paragraph's content description in place of its text.
private const val MAX_LINE_ACTIONS = 16
