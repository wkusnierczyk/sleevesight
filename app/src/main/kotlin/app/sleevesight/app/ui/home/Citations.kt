package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import app.sleevesight.core.synthesis.CheckedSentence
import app.sleevesight.core.synthesis.SentenceStatus

// The insight's prose as text, with its citations as chips: plain text only (architecture,
// *Synthesis and grounding*), its one interactive part the chips the app builds from the evidence
// ids it assigned.

/** The paragraphs of text not checked yet, split as the grounding check splits them: on a blank line. */
internal fun paragraphsOf(text: String): List<String> =
    text.split(BLANK_LINE).map(String::trim).filter(String::isNotEmpty)

/** A checked paragraph: a sentence uncited, or citing a line that does not exist, is underlined. */
@Composable
internal fun checkedProse(
    paragraph: List<CheckedSentence>,
    context: InsightContext,
    onCite: (String) -> Unit,
): AnnotatedString {
    val styles = citeStyles()
    val flagged = SpanStyle(textDecoration = TextDecoration.Underline, color = MaterialTheme.colorScheme.error)
    return buildAnnotatedString {
        paragraph.forEachIndexed { i, sentence ->
            if (i > 0) append(' ')
            val doubtful = sentence.status == SentenceStatus.UNCITED || sentence.status == SentenceStatus.UNKNOWN
            appendSentence(sentence.text, context, styles, onCite, if (doubtful) flagged else null)
        }
    }
}

/** A paragraph still being written: its citations show, the underlines wait for the check. */
@Composable
internal fun uncheckedProse(
    paragraph: String,
    context: InsightContext,
    onCite: (String) -> Unit,
): AnnotatedString {
    val styles = citeStyles()
    return buildAnnotatedString { appendSentence(paragraph, context, styles, onCite, null) }
}

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
    context: InsightContext,
    styles: CiteStyles,
    onCite: (String) -> Unit,
    flagged: SpanStyle?,
) {
    for (segment in segments(text)) {
        when (segment) {
            is Segment.Text ->
                if (flagged !=
                    null
                ) {
                    withStyle(flagged) { append(segment.text) }
                } else {
                    append(segment.text)
                }
            is Segment.Cite -> {
                val kind = citeKind(segment.id, context.track?.evidence, context.modelAllowed)
                appendCite(segment.id, kind, styles, onCite)
            }
        }
    }
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
