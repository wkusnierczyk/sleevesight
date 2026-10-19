package app.sleevesight.app.ui.home

import android.content.res.Resources
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
// ids it assigned.

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
 */
@Composable
internal fun CheckedProse(
    paragraph: List<CheckedSentence>,
    grounded: GroundedText,
    evidence: EvidenceSet?,
    onCite: (String) -> Unit,
) {
    val styles = citeStyles()
    val resources = LocalResources.current
    val flagged = SpanStyle(textDecoration = TextDecoration.Underline, color = MaterialTheme.colorScheme.error)
    val policy = CitePolicy(evidence, grounded.allowModel)
    val sentences = paragraph.map { it.text }
    val uncited = resources.getString(R.string.home_cite_none)
    val said =
        paragraph.joinToString(" ") { sentence ->
            val words = spoken(sentence.text, policy, resources)
            if (sentence.status == SentenceStatus.UNCITED) "$words ($uncited)" else words
        }
    val text =
        buildAnnotatedString {
            paragraph.forEachIndexed { i, sentence ->
                if (i > 0) append(' ')
                val doubtful = sentence.status == SentenceStatus.UNCITED || sentence.status == SentenceStatus.UNKNOWN
                appendSentence(sentence.text, policy, styles, onCite, if (doubtful) flagged else null)
            }
        }
    Prose(ProseText(text, said, lines(sentences, policy)), onCite)
}

/**
 * A paragraph still being written: its citations show, read as the settings allow them now, under
 * which it is written; the underlines wait for the check.
 */
@Composable
internal fun UncheckedProse(
    paragraph: String,
    context: InsightContext,
    onCite: (String) -> Unit,
) {
    val styles = citeStyles()
    val policy = CitePolicy(context.track?.evidence, context.modelAllowed)
    val text = buildAnnotatedString { appendSentence(paragraph, policy, styles, onCite, null) }
    Prose(ProseText(text, spoken(paragraph, policy, LocalResources.current), lines(listOf(paragraph), policy)), onCite)
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

// [text] as a screen reader reads it: each citation, in brackets, by what it points at.
private fun spoken(
    text: String,
    policy: CitePolicy,
    resources: Resources,
): String =
    segments(text)
        .joinToString("") { segment ->
            when (segment) {
                is Segment.Text -> segment.text
                is Segment.Cite -> {
                    val said =
                        when (citeKind(segment.id, policy.evidence, policy.modelAllowed)) {
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
        .filter { citeKind(it, policy.evidence, policy.modelAllowed) == CiteKind.LINE }
        .distinct()
        .take(MAX_LINE_ACTIONS)

// What a citation is read against: the evidence, and whether the model's own knowledge may be cited.
private class CitePolicy(
    val evidence: EvidenceSet?,
    val modelAllowed: Boolean,
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

private fun AnnotatedString.Builder.appendSentence(
    text: String,
    policy: CitePolicy,
    styles: CiteStyles,
    onCite: (String) -> Unit,
    flagged: SpanStyle?,
) {
    for (segment in segments(text)) {
        when (segment) {
            is Segment.Text -> appendText(segment.text, flagged)
            is Segment.Cite -> {
                val kind = citeKind(segment.id, policy.evidence, policy.modelAllowed)
                appendCite(segment.id, kind, styles, onCite)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendText(
    text: String,
    flagged: SpanStyle?,
) {
    if (flagged == null) append(text) else withStyle(flagged) { append(text) }
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

// The accessibility actions a paragraph offers for its lines. Compose (1.11.4) throws when one
// node has 32 or more custom actions ("Can't have more than 32 custom actions for one widget",
// `AndroidComposeViewAccessibilityDelegateCompat`), and a menu that long is hard to use anyway.
// Lines past these are still reached by their chips' links, and listed under *Sources*.
private const val MAX_LINE_ACTIONS = 16
