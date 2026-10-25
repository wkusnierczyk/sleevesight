package app.sleevesight.app.ui.theme

/** A piece of a [LinkedText]: [Words], a [Link] to a web page, or an [Action] run on a tap. */
internal sealed interface TextPart {
    val text: String

    /** Plain text. */
    data class Words(
        override val text: String,
    ) : TextPart

    /** A link that opens [url] in the browser. */
    data class Link(
        override val text: String,
        val url: String,
    ) : TextPart

    /** A link that runs [onClick] in the app; [tag] names it to tests and to accessibility. */
    class Action(
        override val text: String,
        val tag: String,
        val onClick: () -> Unit,
    ) : TextPart
}
