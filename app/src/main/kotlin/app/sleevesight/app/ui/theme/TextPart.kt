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
