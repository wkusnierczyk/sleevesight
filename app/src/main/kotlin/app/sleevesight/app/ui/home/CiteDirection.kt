package app.sleevesight.app.ui.home

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection

// Citation chips in text of either direction (#1350): the paragraph takes its own text's direction,
// and each chip's id is isolated from the text round it.

/**
 * The style of a paragraph with citation chips (#1350): [base] in the direction of its own text, not
 * the layout's, so an English paragraph in a right-to-left layout ends its sentence after its last
 * chip, as in a left-to-right one, rather than with its full stop at the line's other end.
 */
internal fun citedTextStyle(base: TextStyle): TextStyle = base.copy(textDirection = TextDirection.Content)

// A citation's id set apart from the text round it (#1350): its direction is its own first strong
// letter's, and it takes no part in how the paragraph orders the text either side of it.
private const val FIRST_STRONG_ISOLATE = '\u2068'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'

/**
 * A citation chip's text: [shownAs] between thin spaces, isolated (#1350) with its spaces outside, so
 * that in a right-to-left paragraph a chip's id, a left-to-right word, keeps its spaces round it, and
 * chips side by side keep their order.
 */
internal fun citeLabel(shownAs: String): String = " $FIRST_STRONG_ISOLATE$shownAs$POP_DIRECTIONAL_ISOLATE "

/**
 * [text] without its chips' isolates ([citeLabel]), its styles, links and annotations kept on the
 * same characters: the text a paragraph with chips exports to accessibility services (#1350), which
 * would otherwise hand them the drawn text, isolates and all. Its offsets are the drawn text's less
 * the isolates before them, so a service that asks where a character is drawn (Select to Speak's
 * word highlight, by the layout) is off by up to two characters for each chip before it; the screen
 * reader itself reads the paragraph's content description.
 */
internal fun withoutIsolates(text: AnnotatedString): AnnotatedString {
    if (text.none(::isIsolate)) return text
    // Each offset of [text] as an offset of the text without them.
    val at = IntArray(text.length + 1)
    for (i in text.indices) at[i + 1] = at[i] + if (isIsolate(text[i])) 0 else 1
    return buildAnnotatedString {
        append(text.text.filterNot(::isIsolate))
        text.spanStyles.forEach { addStyle(it.item, at[it.start], at[it.end]) }
        text.paragraphStyles.forEach { addStyle(it.item, at[it.start], at[it.end]) }
        text
            .getStringAnnotations(
                0,
                text.length,
            ).forEach { addStringAnnotation(it.tag, it.item, at[it.start], at[it.end]) }
        text.getLinkAnnotations(0, text.length).forEach { range ->
            val (start, end) = at[range.start] to at[range.end]
            when (val link = range.item) {
                is LinkAnnotation.Clickable -> addLink(link, start, end)
                is LinkAnnotation.Url -> addLink(link, start, end)
                // Compose (1.12.1) has no other kind; one added later would show here as no link.
                else -> Unit
            }
            // A link's look as a span too, as the text's own semantics carry it.
            val look = range.item.styles?.style
            if (look != null) addStyle(look, start, end)
        }
    }
}

private fun isIsolate(c: Char): Boolean = c == FIRST_STRONG_ISOLATE || c == POP_DIRECTIONAL_ISOLATE
