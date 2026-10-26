package app.sleevesight.app.ui.theme

/** A piece of a [LinkedText]: [Words], a [Link] to a web page, or an [Action] run on a tap. */
internal sealed interface TextPart {
    val text: String

    /** A piece that is a link: a [Link] or an [Action]; also a [LinkLabel] of its own. */
    sealed interface Linked : TextPart

    /** Plain text. */
    data class Words(
        override val text: String,
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
 * [links] (#541), so a translation may put the links where its grammar needs them.
 */
internal fun linkedParts(
    template: String,
    vararg links: TextPart.Linked,
): List<TextPart> {
    val parts = mutableListOf<TextPart>()
    var at = 0
    for (match in PLACEHOLDER.findAll(template)) {
        if (match.range.first > at) parts += TextPart.Words(template.substring(at, match.range.first))
        parts += links[match.groupValues[1].toInt() - 1]
        at = match.range.last + 1
    }
    if (at < template.length) parts += TextPart.Words(template.substring(at))
    return parts
}

/** A positional argument in a string resource: `%1$s`. */
private val PLACEHOLDER = Regex("%(\\d+)\\\$s")
