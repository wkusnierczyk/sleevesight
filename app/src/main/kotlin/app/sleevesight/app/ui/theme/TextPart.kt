package app.sleevesight.app.ui.theme

import androidx.compose.ui.text.SpanStyle

/** A piece of a [LinkedText]: [Words], [Styled] words, a [Link] to a web page, or an [Action] run on a tap. */
internal sealed interface TextPart {
    val text: String

    /** A piece that is a link: a [Link] or an [Action]; also a [LinkLabel] of its own. */
    sealed interface Linked : TextPart

    /** Plain text. */
    data class Words(
        override val text: String,
    ) : TextPart

    /** Plain text in [style] (#762: the small **AI** mark after the model among a genre's tag sources). */
    data class Styled(
        override val text: String,
        val style: SpanStyle,
    ) : TextPart

    /** A link that opens [url] in the browser. */
    data class Link(
        override val text: String,
        val url: String,
    ) : Linked

    /** A link that runs [onClick] in the app; [tag] names it to tests. */
    class Action(
        override val text: String,
        val tag: String,
        val onClick: () -> Unit,
    ) : Linked
}

/**
 * [template] as parts: its words, and in place of each `%1$s`, `%2$s`… the link of that number in
 * [links] (#541), so a translation may put the links where its grammar needs them. A placeholder
 * with no link of its number stays as written.
 */
internal fun linkedParts(
    template: String,
    vararg links: TextPart.Linked,
): List<TextPart> {
    val parts = mutableListOf<TextPart>()
    var at = 0
    for (match in PLACEHOLDER.findAll(template)) {
        if (match.range.first > at) parts += TextPart.Words(template.substring(at, match.range.first))
        // A number with no link given stays as written, rather than fail.
        parts += links.getOrNull(match.groupValues[1].toInt() - 1) ?: TextPart.Words(match.value)
        at = match.range.last + 1
    }
    if (at < template.length) parts += TextPart.Words(template.substring(at))
    return parts
}

/** A positional argument in a string resource: `%1$s`. */
private val PLACEHOLDER = Regex("%(\\d+)\\\$s")
