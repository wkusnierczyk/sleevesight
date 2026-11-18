package app.sleevesight.app.ui.home

import androidx.compose.ui.text.TextStyle
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
private const val FIRST_STRONG_ISOLATE = "\u2068"
private const val POP_DIRECTIONAL_ISOLATE = "\u2069"

/**
 * A citation chip's text: [shownAs] between thin spaces, isolated (#1350) with its spaces outside, so
 * that in a right-to-left paragraph a chip's id, a left-to-right word, keeps its spaces round it, and
 * chips side by side keep their order.
 */
internal fun citeLabel(shownAs: String): String = " $FIRST_STRONG_ISOLATE$shownAs$POP_DIRECTIONAL_ISOLATE "
